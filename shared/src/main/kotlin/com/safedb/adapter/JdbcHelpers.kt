package com.safedb.adapter

import com.safedb.launch.POSTGRES_LAUNCH_ROOT_CERT_PROPERTY
import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

const val DEFAULT_TIMEOUT_MS = 10_000
const val CONNECT_TIMEOUT_MS = 10_000L
const val INTROSPECTION_TIMEOUT_MS = 30_000L
const val SERVICE_NAME = "safe-db"

fun buildJdbcUrl(def: ConnectionDef): String =
    when (def.dialect) {
        Dialect.Postgres -> buildPostgresUrl(def)
        Dialect.MySql -> buildMySqlUrl(def)
        Dialect.Mssql -> buildMssqlUrl(def)
        Dialect.Oracle -> buildOracleUrl(def)
    }

fun createDataSource(def: ConnectionDef, password: String): HikariDataSource =
    HikariDataSource(createDataSourceConfig(def, password))

fun closeDataSource(dataSource: HikariDataSource) = dataSource.close()

internal fun createDataSourceConfig(
    def: ConnectionDef,
    password: String,
    launchProfilePostgresRootCert: String? = System.getProperty(POSTGRES_LAUNCH_ROOT_CERT_PROPERTY),
): HikariConfig {
    val config = HikariConfig()
    config.jdbcUrl = buildJdbcUrl(def)
    config.username = def.username
    config.password = password
    config.maximumPoolSize = 1
    config.minimumIdle = 0
    config.connectionTimeout = CONNECT_TIMEOUT_MS
    config.isReadOnly = true
    def.driverProperties.forEach { property ->
        config.addDataSourceProperty(property.name, property.value)
    }
    when (def.dialect) {
        Dialect.Postgres -> applyPostgresSsl(config, def, launchProfilePostgresRootCert)
        Dialect.MySql -> Unit
        Dialect.Mssql -> applyMssqlSsl(config, def)
        Dialect.Oracle -> applyOracleSsl(config, def)
    }
    return config
}

private fun buildPostgresUrl(def: ConnectionDef): String {
    val base = "jdbc:postgresql://${def.host}:${def.port}/${def.database}"
    val sslMode =
        when (def.transportSecurity.mode) {
            TransportSecurityMode.VerifyIdentity -> "verify-full"
            TransportSecurityMode.VerifyCa -> "verify-ca"
            TransportSecurityMode.EncryptOnly -> "require"
            TransportSecurityMode.Disabled -> "disable"
        }
    return "$base?sslmode=$sslMode"
}

private fun applyPostgresSsl(
    config: HikariConfig,
    def: ConnectionDef,
    launchProfileRootCert: String?,
) {
    when (def.transportSecurity.mode) {
        TransportSecurityMode.VerifyIdentity,
        TransportSecurityMode.VerifyCa -> {
            if (!launchProfileRootCert.isNullOrBlank()) {
                // Keep pgjdbc's LibPQFactory so its standard client certificate and key locations
                // still work.
                config.addDataSourceProperty("sslrootcert", launchProfileRootCert)
            }
        }
        TransportSecurityMode.EncryptOnly,
        TransportSecurityMode.Disabled -> Unit
    }
}

private fun buildMySqlUrl(def: ConnectionDef): String {
    val sslMode =
        when (def.transportSecurity.mode) {
            TransportSecurityMode.VerifyIdentity -> "VERIFY_IDENTITY"
            TransportSecurityMode.VerifyCa -> "VERIFY_CA"
            TransportSecurityMode.EncryptOnly -> "REQUIRED"
            TransportSecurityMode.Disabled -> "DISABLED"
        }
    val params = mutableListOf("sslMode=$sslMode")
    if (def.transportSecurity.mode == TransportSecurityMode.Disabled) {
        params += "allowPublicKeyRetrieval=true"
    }
    return "jdbc:mysql://${def.host}:${def.port}/${def.database}?${params.joinToString("&")}"
}

private fun buildMssqlUrl(def: ConnectionDef): String =
    "jdbc:sqlserver://${def.host}:${def.port};databaseName=${def.database};applicationName=$SERVICE_NAME"

private fun applyMssqlSsl(config: HikariConfig, def: ConnectionDef) {
    when (def.transportSecurity.mode) {
        TransportSecurityMode.VerifyIdentity -> {
            config.addDataSourceProperty("encrypt", "true")
            config.addDataSourceProperty("trustServerCertificate", "false")
            config.addDataSourceProperty("hostNameInCertificate", def.host)
        }
        TransportSecurityMode.VerifyCa -> {
            config.addDataSourceProperty("encrypt", "true")
            config.addDataSourceProperty("trustServerCertificate", "false")
        }
        TransportSecurityMode.EncryptOnly -> {
            config.addDataSourceProperty("encrypt", "true")
            config.addDataSourceProperty("trustServerCertificate", "true")
        }
        TransportSecurityMode.Disabled -> {
            config.addDataSourceProperty("encrypt", "false")
            config.addDataSourceProperty("trustServerCertificate", "true")
        }
    }
}

private fun buildOracleUrl(def: ConnectionDef): String {
    OracleAdapter.validateConnectField(def.host, "Host").getOrThrow()
    OracleAdapter.validateConnectField(def.database, "Database").getOrThrow()
    if (def.port == 0) error("Port must be between 1 and 65535")
    return when (def.transportSecurity.mode) {
        TransportSecurityMode.Disabled ->
            "jdbc:oracle:thin:@//${def.host}:${def.port}/${def.database}"
        else -> {
            val wallet =
                def.transportSecurity.oracleWalletLocation
                    ?: error("Oracle TCPS requires a wallet location")
            val encoded = OracleAdapter.encodeConnectQueryValue(wallet)
            "jdbc:oracle:thin:@tcps://${def.host}:${def.port}/${def.database}?wallet_location=$encoded"
        }
    }
}

private fun applyOracleSsl(config: HikariConfig, def: ConnectionDef) {
    def.transportSecurity.oracleWalletLocation?.let { wallet ->
        config.addDataSourceProperty("oracle.net.wallet_location", wallet)
    }
    when (def.transportSecurity.mode) {
        TransportSecurityMode.VerifyIdentity ->
            config.addDataSourceProperty("oracle.net.ssl_server_dn_match", "true")
        TransportSecurityMode.VerifyCa,
        TransportSecurityMode.EncryptOnly ->
            config.addDataSourceProperty("oracle.net.ssl_server_dn_match", "false")
        TransportSecurityMode.Disabled -> Unit
    }
}

fun decodeQueryResult(
    rs: ResultSet,
    compiledSql: String,
    dialect: Dialect,
): com.safedb.model.QueryResult {
    val meta = rs.metaData
    if (!rs.next()) {
        val columns =
            columnsFromCompiledSql(compiledSql, dialect).map {
                com.safedb.model.ResultColumn(it, "unknown")
            }
        return com.safedb.model.QueryResult.fromRows(columns, emptyList())
    }
    val columns =
        (1..meta.columnCount).map { index ->
            com.safedb.model.ResultColumn(meta.getColumnLabel(index), meta.getColumnTypeName(index))
        }
    val rows = mutableListOf<List<com.safedb.model.ResultCell>>()
    do {
        rows +=
            (1..meta.columnCount).map { index ->
                decodeJdbcValue(rs, index, meta.getColumnTypeName(index))
            }
    } while (rs.next())
    return com.safedb.model.QueryResult.fromRows(columns, rows)
}

fun jdbcSql(compiled: CompiledQuery, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> compiled.sql.replace(Regex("\\$(\\d+)"), "?")
        Dialect.MySql -> compiled.sql
        Dialect.Mssql -> compiled.sql.replace(Regex("@P(\\d+)"), "?")
        Dialect.Oracle -> compiled.sql.replace(Regex(":(\\d+)"), "?")
    }

fun prepareStatement(
    connection: Connection,
    compiled: CompiledQuery,
    dialect: Dialect,
): PreparedStatement {
    val sql = jdbcSql(compiled, dialect)
    val stmt = connection.prepareStatement(sql)
    compiled.params.forEachIndexed { index, param -> bindParam(stmt, index + 1, param) }
    return stmt
}

private fun bindParam(stmt: PreparedStatement, index: Int, param: BindValue) {
    when (param) {
        is BindValue.Text -> stmt.setString(index, param.value)
        is BindValue.Int -> stmt.setLong(index, param.value)
        is BindValue.Decimal -> stmt.setBigDecimal(index, param.value)
        is BindValue.Float -> stmt.setDouble(index, param.value)
        is BindValue.Bool -> stmt.setBoolean(index, param.value)
        is BindValue.Date -> stmt.setDate(index, java.sql.Date.valueOf(param.value))
        is BindValue.DateTime -> stmt.setTimestamp(index, java.sql.Timestamp.valueOf(param.value))
        BindValue.Null -> stmt.setNull(index, Types.NULL)
    }
}

fun readString(rs: ResultSet, column: String): String = rs.getString(column) ?: ""

fun readString(rs: ResultSet, index: Int): String = rs.getString(index) ?: ""

internal fun <T> Connection.metadataRows(sql: String, transform: (ResultSet) -> T): List<T> =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            buildList { while (result.next()) add(transform(result)) }
        }
    }

fun decodeJdbcValue(rs: ResultSet, index: Int, typeName: String): com.safedb.model.ResultCell {
    if (rs.getObject(index) == null) return com.safedb.model.ResultCell.Null
    val normalizedType = typeName.uppercase().substringBefore('(')
    return when (normalizedType) {
        "BOOL",
        "BOOLEAN",
        "BIT" -> com.safedb.model.ResultCell.BoolCell(rs.getBoolean(index))
        "INT2",
        "SMALLINT",
        "TINYINT",
        "MEDIUMINT",
        "INT",
        "INTEGER",
        "INT4",
        "BIGINT",
        "INT8" -> com.safedb.model.ResultCell.IntegerCell(rs.getLong(index))
        "FLOAT",
        "REAL",
        "FLOAT4",
        "DOUBLE",
        "FLOAT8",
        "DOUBLE PRECISION" -> com.safedb.model.ResultCell.FloatCell(rs.getDouble(index))
        "BYTEA",
        "BINARY",
        "VARBINARY",
        "BLOB",
        "TINYBLOB",
        "MEDIUMBLOB",
        "LONGBLOB",
        "RAW" -> {
            val bytes = rs.getBytes(index) ?: return com.safedb.model.ResultCell.Null
            com.safedb.model.ResultCell.binary(bytes)
        }
        "DECIMAL",
        "NEWDECIMAL",
        "NUMERIC",
        "NUMBER",
        "MONEY",
        "SMALLMONEY",
        "DATE",
        "TIME",
        "TIMESTAMP",
        "TIMESTAMPTZ",
        "TIMESTAMP WITH TIME ZONE",
        "TIMESTAMP WITHOUT TIME ZONE",
        "DATETIME",
        "SMALLDATETIME",
        "DATETIME2",
        "DATETIMEOFFSET",
        "JSON",
        "JSONB",
        "UUID",
        "XML" -> com.safedb.model.ResultCell.text(rs.getObject(index)?.toString().orEmpty())
        else -> {
            when (val value = rs.getObject(index)) {
                is Boolean -> com.safedb.model.ResultCell.BoolCell(value)
                is ByteArray -> com.safedb.model.ResultCell.binary(value)
                else -> com.safedb.model.ResultCell.text(value?.toString() ?: "")
            }
        }
    }
}

class QueryTimedOutException(val timeoutMs: Int) :
    SQLException("Query timed out after ${timeoutMs}ms")

fun <T> withQueryTimeout(connection: Connection, timeoutMs: Int, block: (Connection) -> T): T {
    val previous = connection.networkTimeout
    connection.setNetworkTimeout({ _ -> }, timeoutMs)
    return try {
        block(connection)
    } finally {
        connection.setNetworkTimeout({ _ -> }, previous)
    }
}
