package com.safedb.adapter

import com.safedb.launch.POSTGRES_LAUNCH_ROOT_CERT_PROPERTY
import com.safedb.model.BinaryValue
import com.safedb.model.BindValue
import com.safedb.model.ColumnInfo
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.MAX_CELL_BYTES
import com.safedb.model.ResultCell
import com.safedb.model.ResultRowBudget
import com.safedb.model.TableSizeEstimate
import com.safedb.model.TextValue
import com.safedb.model.TransportSecurityMode
import com.safedb.model.mySqlSslMode
import com.safedb.model.postgresSslMode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.Reader
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Base64

const val DEFAULT_TIMEOUT_MS = 10_000
const val CONNECT_TIMEOUT_MS = 10_000L
const val INTROSPECTION_TIMEOUT_MS = 30_000L
const val SERVICE_NAME = "safe-db"

// 0 buffers the whole result (pgjdbc's default). A positive size is what opens a forward cursor.
const val QUERY_FETCH_SIZE = 128

fun buildJdbcUrl(def: ConnectionDef): String =
    when (def.dialect) {
        Dialect.Postgres -> buildPostgresUrl(def)
        Dialect.MySql -> buildMySqlUrl(def)
        Dialect.Mssql -> buildMssqlUrl(def)
        Dialect.Oracle -> buildOracleUrl(def)
        Dialect.Db2 -> buildDb2Url(def)
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
        Dialect.Db2 -> applyDb2Ssl(config, def)
    }
    // Applied last so a pasted driver property cannot disable the connect deadline.
    applyConnectTimeouts(config, def.dialect)
    return config
}

internal fun applyConnectTimeouts(config: HikariConfig, dialect: Dialect) {
    val connectSeconds = (CONNECT_TIMEOUT_MS / 1000L).toString()
    val connectMillis = CONNECT_TIMEOUT_MS.toString()
    val readSeconds = (INTROSPECTION_TIMEOUT_MS / 1000L).toString()
    val readMillis = INTROSPECTION_TIMEOUT_MS.toString()
    when (dialect) {
        Dialect.Postgres -> {
            config.addDataSourceProperty("connectTimeout", connectSeconds)
            config.addDataSourceProperty("loginTimeout", connectSeconds)
            config.addDataSourceProperty("socketTimeout", readSeconds)
        }
        Dialect.MySql -> {
            config.addDataSourceProperty("connectTimeout", connectMillis)
            config.addDataSourceProperty("socketTimeout", readMillis)
        }
        Dialect.Mssql -> {
            config.addDataSourceProperty("loginTimeout", connectSeconds)
            config.addDataSourceProperty("socketTimeout", readMillis)
        }
        Dialect.Oracle -> {
            config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", connectMillis)
            config.addDataSourceProperty("oracle.jdbc.ReadTimeout", readMillis)
            config.addDataSourceProperty("oracle.net.READ_TIMEOUT", readMillis)
        }
        Dialect.Db2 -> {
            config.addDataSourceProperty("loginTimeout", connectSeconds)
            config.addDataSourceProperty("blockingReadConnectionTimeout", readSeconds)
        }
    }
}

private fun buildPostgresUrl(def: ConnectionDef): String {
    val base = "jdbc:postgresql://${def.host}:${def.port}/${def.database}"
    return "$base?sslmode=${def.transportSecurity.mode.postgresSslMode()}"
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
                // Keep pgjdbc's LibPQFactory so its standard client certificate and key
                // locations still work.
                config.addDataSourceProperty("sslrootcert", launchProfileRootCert)
            }
        }
        TransportSecurityMode.EncryptOnly,
        TransportSecurityMode.Disabled -> Unit
    }
}

private fun buildMySqlUrl(def: ConnectionDef): String {
    val params = mutableListOf("sslMode=${def.transportSecurity.mode.mySqlSslMode()}")
    if (def.transportSecurity.mode == TransportSecurityMode.Disabled) {
        params += "allowPublicKeyRetrieval=true"
    }
    return "jdbc:mysql://${def.host}:${def.port}/${def.database}?${params.joinToString("&")}"
}

private fun buildMssqlUrl(def: ConnectionDef): String =
    "jdbc:sqlserver://${def.host}:${def.port};databaseName=${def.database};applicationName=$SERVICE_NAME"

// Deliberately not shared with formatConnectionString: connecting pins the certificate host name,
// while an exported string carries only the properties a driver needs to reproduce the mode.
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

internal fun db2JdbcAuthority(host: String, port: Int): String {
    val literal = if (host.contains(':')) "[$host]" else host
    return "$literal:$port"
}

// JCC 12 rejects Driver.connect when sslConnection is in both the URL and the Properties
// object (ERRORCODE=-4461). The flag is applied only in applyDb2Ssl.
private fun buildDb2Url(def: ConnectionDef): String =
    "jdbc:db2://${db2JdbcAuthority(def.host, def.port)}/${def.database}"

// sslConnection and hostname validation are pinned after user properties so a pasted
// driver property cannot turn TLS or BASIC hostname checks off. They stay out of the
// JDBC URL because JCC 12 rejects a property that appears in both places. Encrypt-only
// and certificate-only modes are not distinct on JCC: anything other than Disabled is SSL on.
private fun applyDb2Ssl(config: HikariConfig, def: ConnectionDef) {
    val ssl = def.transportSecurity.mode != TransportSecurityMode.Disabled
    config.addDataSourceProperty("sslConnection", ssl.toString())
    if (ssl) {
        config.addDataSourceProperty("sslClientHostnameValidation", "BASIC")
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
    val budget = ResultRowBudget()
    do {
        val row =
            (1..meta.columnCount).map { index ->
                decodeJdbcValue(rs, index, meta.getColumnTypeName(index))
            }
        // This row was already read to measure it. Stop so the following rows stay on the server.
        if (!budget.accept(row)) break
    } while (rs.next())
    return budget.toResult(columns)
}

fun jdbcSql(compiled: CompiledQuery, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> compiled.sql.replace(Regex("\\$(\\d+)"), "?")
        Dialect.MySql -> compiled.sql
        Dialect.Mssql -> compiled.sql.replace(Regex("@P(\\d+)"), "?")
        Dialect.Oracle -> compiled.sql.replace(Regex(":(\\d+)"), "?")
        Dialect.Db2 -> compiled.sql
    }

fun prepareStatement(
    connection: Connection,
    compiled: CompiledQuery,
    dialect: Dialect,
): PreparedStatement {
    val sql = jdbcSql(compiled, dialect)
    val stmt = connection.prepareStatement(sql)
    // Connector/J streams one row at a time only when fetch size is Integer.MIN_VALUE.
    stmt.fetchSize = if (dialect == Dialect.MySql) Int.MIN_VALUE else QUERY_FETCH_SIZE
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
        // Bind java.time values directly: java.sql.Date/Timestamp.valueOf route through the JVM
        // default zone, which can shift wall-clock values across DST gaps.
        is BindValue.Date -> stmt.setObject(index, param.value)
        is BindValue.DateTime -> stmt.setObject(index, param.value)
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

// Every dialect's foreign-key query aliases its columns to this canonical set.
internal fun foreignKeyRow(rs: ResultSet): MetadataForeignKey =
    MetadataForeignKey(
        MetadataTableKey(readString(rs, "table_schema"), readString(rs, "table_name")),
        readString(rs, "constraint_name"),
        readString(rs, "column_name"),
        readString(rs, "referenced_schema"),
        readString(rs, "referenced_table"),
        readString(rs, "referenced_column"),
    )

internal fun columnRow(
    rs: ResultSet,
    schemaLabel: String = "table_schema",
    tableLabel: String = "table_name",
    columnLabel: String = "column_name",
    typeLabel: String = "data_type",
    nullableLabel: String = "is_nullable",
    nullableValue: String = "YES",
): MetadataColumn =
    MetadataColumn(
        MetadataTableKey(readString(rs, schemaLabel), readString(rs, tableLabel)),
        ColumnInfo(
            readString(rs, columnLabel),
            readString(rs, typeLabel),
            readString(rs, nullableLabel) == nullableValue,
        ),
    )

// Row estimates are best-effort: a dialect that denies access to its statistics views still
// produces a schema, just without size classes.
internal fun Connection.tableSizes(
    sql: String,
    rowEstimateLabel: String,
    confidence: EvidenceConfidence,
    schemaLabel: String = "table_schema",
    tableLabel: String = "table_name",
): Map<MetadataTableKey, TableSizeEstimate> {
    val rows = runCatching {
        metadataRows(sql) { rs ->
            MetadataTableKey(readString(rs, schemaLabel), readString(rs, tableLabel)) to
                normalizeTableSize(
                    (rs.getObject(rowEstimateLabel) as? Number)?.toDouble(),
                    confidence,
                )
        }
    }
    return rows.getOrDefault(emptyList()).toMap()
}

internal fun probeVersion(dataSource: HikariDataSource, sql: String): String =
    dataSource.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                rs.next()
                readString(rs, 1)
            }
        }
    }

fun decodeJdbcValue(rs: ResultSet, index: Int, typeName: String): ResultCell {
    val normalizedType = typeName.uppercase().substringBefore('(')
    return when (normalizedType) {
        in BOOLEAN_TYPES -> readBoolean(rs, index)
        in INTEGER_TYPES -> readInteger(rs, index)
        in FLOAT_TYPES -> readFloat(rs, index)
        in BINARY_TYPES -> readBinary(rs, index)
        in TEMPORAL_AND_DECIMAL_TYPES -> readTemporalOrDecimal(rs, index)
        in TEXT_TYPES -> readText(rs, index)
        else -> readFallback(rs, index)
    }
}

private val BOOLEAN_TYPES = setOf("BOOL", "BOOLEAN", "BIT")

private val INTEGER_TYPES =
    setOf(
        "INT2",
        "SMALLINT",
        "TINYINT",
        "MEDIUMINT",
        "INT",
        "INTEGER",
        "INT4",
        "BIGINT",
        "INT8",
    )

private val FLOAT_TYPES = setOf("FLOAT", "REAL", "FLOAT4", "DOUBLE", "FLOAT8", "DOUBLE PRECISION")

private val BINARY_TYPES =
    setOf(
        "BYTEA",
        "BINARY",
        "VARBINARY",
        "BLOB",
        "TINYBLOB",
        "MEDIUMBLOB",
        "LONGBLOB",
        "RAW",
        "LONG RAW",
        "IMAGE",
        "BINARY LARGE OBJECT",
    )

private val TEMPORAL_AND_DECIMAL_TYPES =
    setOf(
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
    )

private val TEXT_TYPES =
    setOf(
        "JSON",
        "JSONB",
        "UUID",
        "XML",
        "CHAR",
        "CHARACTER",
        "VARCHAR",
        "NVARCHAR",
        "NCHAR",
        "NTEXT",
        "TEXT",
        "TINYTEXT",
        "MEDIUMTEXT",
        "LONGTEXT",
        "CLOB",
        "NCLOB",
        "DBCLOB",
        "LONGVARCHAR",
        "LONGNVARCHAR",
        "VARCHAR2",
        "NVARCHAR2",
        "BPCHAR",
        "LONG",
        "GRAPHIC",
        "VARGRAPHIC",
        "LONG VARCHAR",
        "LONG VARGRAPHIC",
        "CHARACTER VARYING",
        "CHARACTER LARGE OBJECT",
        "CHAR VARYING",
        "NCHAR VARYING",
        "NATIONAL CHARACTER",
        "NATIONAL CHAR",
        "NATIONAL CHARACTER VARYING",
        "NATIONAL CHAR VARYING",
    )

private fun readBoolean(rs: ResultSet, index: Int): ResultCell {
    val value = rs.getBoolean(index)
    return if (rs.wasNull()) ResultCell.Null else ResultCell.BoolCell(value)
}

private fun readInteger(rs: ResultSet, index: Int): ResultCell {
    val value = rs.getLong(index)
    return if (rs.wasNull()) ResultCell.Null else ResultCell.IntegerCell(value)
}

private fun readFloat(rs: ResultSet, index: Int): ResultCell {
    val value = rs.getDouble(index)
    return if (rs.wasNull()) ResultCell.Null else ResultCell.FloatCell(value)
}

private fun readTemporalOrDecimal(rs: ResultSet, index: Int): ResultCell {
    val value = rs.getString(index) ?: return ResultCell.Null
    return ResultCell.text(value)
}

private fun readText(rs: ResultSet, index: Int): ResultCell {
    val reader = rs.getCharacterStream(index) ?: return ResultCell.Null
    reader.use {
        val (text, truncated) = readBoundedUtf8(it, MAX_CELL_BYTES)
        return ResultCell.TextCell(TextValue(text = text, truncated = truncated))
    }
}

private fun readBinary(rs: ResultSet, index: Int): ResultCell {
    val stream = rs.getBinaryStream(index) ?: return ResultCell.Null
    stream.use {
        val bytes = it.readNBytes(MAX_CELL_BYTES + 1)
        val truncated = bytes.size > MAX_CELL_BYTES
        val clipped = if (truncated) bytes.copyOf(MAX_CELL_BYTES) else bytes
        return ResultCell.BinaryCell(
            BinaryValue(
                base64 = Base64.getEncoder().encodeToString(clipped),
                truncated = truncated,
            )
        )
    }
}

private fun readFallback(rs: ResultSet, index: Int): ResultCell =
    when (val value = rs.getObject(index)) {
        null -> ResultCell.Null
        is Boolean -> ResultCell.BoolCell(value)
        is ByteArray -> ResultCell.binary(value)
        else -> ResultCell.text(value.toString())
    }

// A character is at most 4 UTF-8 bytes, so a chunk of room/4 stays under the cap. One extra
// code point is probed after the cap (or rejected when it would not fit) so truncated is set
// here instead of by ResultCell.text.
private fun readBoundedUtf8(reader: Reader, maxBytes: Int): Pair<String, Boolean> {
    val out = StringBuilder()
    var usedBytes = 0
    val buf = CharArray(4096)
    while (usedBytes < maxBytes) {
        val room = maxBytes - usedBytes
        val want = if (room < 4) 1 else minOf(buf.size, room / 4)
        val n = reader.read(buf, 0, want)
        if (n <= 0) return out.toString() to false
        var index = 0
        while (index < n) {
            val highSurrogateAtEnd = Character.isHighSurrogate(buf[index]) && index == n - 1
            val piece: String
            val count: Int
            if (highSurrogateAtEnd) {
                val low = reader.read()
                piece =
                    if (low < 0) buf[index].toString()
                    else String(charArrayOf(buf[index], low.toChar()))
                count = n - index
            } else {
                count = if (Character.isHighSurrogate(buf[index]) && index + 1 < n) 2 else 1
                piece = String(buf, index, count)
            }
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (usedBytes + pieceBytes > maxBytes) return out.toString() to true
            out.append(piece)
            usedBytes += pieceBytes
            if (highSurrogateAtEnd) break
            index += count
        }
    }
    val extra = reader.read()
    return out.toString() to (extra >= 0)
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
