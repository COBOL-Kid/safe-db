package com.safedb.adapter

import com.safedb.model.ConnectionDef
import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.ResultCell
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Connection
import java.sql.PreparedStatement
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdapterTest {
    @Test
    fun datasourceCreationFailureDeletesTemporaryPemFiles() {
        val temporaryPem = Files.createTempFile("safedb-failed-datasource", ".pem")

        val failure = assertFailsWith<IllegalStateException> {
            createWithTemporaryPemCleanup(listOf(temporaryPem)) {
                error("datasource creation failed")
            }
        }

        assertEquals("datasource creation failed", failure.message)
        assertFalse(Files.exists(temporaryPem))
    }

    @Test
    fun columnsFromCompiledSqlHandlesDialectQuotesAndMssqlTop() {
        val pg = """
            SELECT "t0"."id" AS "t0__id", "t0"."name" AS "t0__name"
            FROM "public"."users" AS "t0"
            LIMIT 101
        """.trimIndent()
        assertEquals(listOf("t0__id", "t0__name"), columnsFromCompiledSql(pg, Dialect.Postgres))

        val mysql = "SELECT `t0`.`id` AS `t0__id`\nFROM `app`.`users` AS `t0`\nLIMIT 101"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mysql, Dialect.MySql))

        val mssql = "SELECT TOP 101 [t0].[id] AS [t0__id]\nFROM [dbo].[users] AS [t0]"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mssql, Dialect.Mssql))
    }

    @Test
    fun mysqlDisabledTransportAllowsPublicKeyRetrievalForLocalAuth() {
        val url = buildJdbcUrl(
            mysqlConnection(TransportSecurityMode.Disabled),
        )

        assertEquals(
            "jdbc:mysql://localhost:3306/safedb_test?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            url,
        )
    }

    @Test
    fun mysqlVerifiedTransportDoesNotAllowPublicKeyRetrieval() {
        val url = buildJdbcUrl(
            mysqlConnection(TransportSecurityMode.VerifyIdentity),
        )

        assertEquals("jdbc:mysql://localhost:3306/safedb_test?sslMode=VERIFY_IDENTITY", url)
    }

    @Test
    fun jdbcDecoderKeepsDecimalValuesAsText() {
        val cell = decodeJdbcValue(fakeResultSet(BigDecimal("1234567890.123456789")), 1, "DECIMAL")

        val text = assertIs<ResultCell.TextCell>(cell)
        assertEquals("1234567890.123456789", text.value.text)
    }

    @Test
    fun jdbcDecoderKeepsUnknownNumbersAsTextRatherThanLossyFloats() {
        val cell = decodeJdbcValue(fakeResultSet(BigDecimal("1.000000000000000001")), 1, "OTHER")

        val text = assertIs<ResultCell.TextCell>(cell)
        assertEquals("1.000000000000000001", text.value.text)
    }

    @Test
    fun jdbcUrlsCoverAllSupportedDialectsAndTransportModes() {
        assertEquals(
            "jdbc:postgresql://db.example.com:5432/app?sslmode=verify-full",
            buildJdbcUrl(connection(Dialect.Postgres, 5432, TransportSecurityMode.VerifyIdentity)),
        )
        assertEquals(
            "jdbc:sqlserver://db.example.com:1433;databaseName=app;applicationName=safe-db",
            buildJdbcUrl(connection(Dialect.Mssql, 1433, TransportSecurityMode.EncryptOnly)),
        )
        assertEquals(
            "jdbc:oracle:thin:@//db.example.com:1521/app",
            buildJdbcUrl(connection(Dialect.Oracle, 1521, TransportSecurityMode.Disabled)),
        )
        assertEquals(
            "jdbc:oracle:thin:@tcps://db.example.com:1521/app?wallet_location=/tmp/safe%20wallet",
            buildJdbcUrl(
                connection(Dialect.Oracle, 1521, TransportSecurityMode.VerifyIdentity).copy(
                    transportSecurity = TransportSecurity(
                        mode = TransportSecurityMode.VerifyIdentity,
                        oracleWalletLocation = "/tmp/safe wallet",
                    ),
                ),
            ),
        )
    }

    @Test
    fun datasourcePropertiesEncodeMssqlSecurityChoices() {
        val verified = createDataSourceConfig(
            connection(Dialect.Mssql, 1433, TransportSecurityMode.VerifyIdentity),
            "pw",
        ).dataSourceProperties
        assertEquals("true", verified.getProperty("encrypt"))
        assertEquals("false", verified.getProperty("trustServerCertificate"))
        assertEquals("db.example.com", verified.getProperty("hostNameInCertificate"))

        val encryptOnly = createDataSourceConfig(
            connection(Dialect.Mssql, 1433, TransportSecurityMode.EncryptOnly),
            "pw",
        ).dataSourceProperties
        assertEquals("true", encryptOnly.getProperty("encrypt"))
        assertEquals("true", encryptOnly.getProperty("trustServerCertificate"))
    }

    @Test
    fun datasourceReceivesCustomDriverPropertiesAndManagedSecurityWins() {
        val config = createDataSourceConfig(
            connection(Dialect.Mssql, 1433, TransportSecurityMode.VerifyIdentity).copy(
                driverProperties = listOf(
                    DriverProperty("applicationName", "Enterprise Reporting"),
                    DriverProperty("encrypt", "false"),
                ),
            ),
            "pw",
        )

        assertEquals("Enterprise Reporting", config.dataSourceProperties.getProperty("applicationName"))
        assertEquals("true", config.dataSourceProperties.getProperty("encrypt"))
    }

    @Test
    fun prepareStatementRewritesPlaceholdersAndBindsEveryValueKind() {
        val calls = mutableListOf<Pair<String, Any?>>()
        var preparedSql = ""
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            if (method.name.startsWith("set")) calls += method.name to args?.getOrNull(1)
            when (method.name) {
                "toString" -> "FakePreparedStatement"
                else -> null
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> {
                    preparedSql = args?.first() as String
                    statement
                }
                "toString" -> "FakeConnection"
                else -> null
            }
        } as Connection
        val compiled = CompiledQuery(
            sql = "SELECT * FROM [t] WHERE a=@P1 AND b=@P2 AND c=@P3 AND d=@P4 AND e=@P5 AND f=@P6 AND g=@P7 AND h=@P8",
            params = listOf(
                BindValue.Text("text"),
                BindValue.Int(2),
                BindValue.Decimal(BigDecimal("3.25")),
                BindValue.Float(4.5),
                BindValue.Bool(true),
                BindValue.Date(LocalDate.of(2026, 1, 2)),
                BindValue.DateTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
                BindValue.Null,
            ),
        )

        prepareStatement(connection, compiled, Dialect.Mssql)

        assertEquals(8, preparedSql.count { it == '?' })
        assertEquals(
            listOf("setString", "setLong", "setBigDecimal", "setDouble", "setBoolean", "setDate", "setTimestamp", "setNull"),
            calls.map { it.first },
        )
        assertTrue(calls.last().second is Int)
    }

    @Test
    fun postgresPreparedStatementsRewriteNumberedPlaceholders() {
        assertEquals(
            "SELECT * FROM \"orders\" WHERE status=? AND created_at>=?",
            jdbcSql(
                CompiledQuery(
                    sql = "SELECT * FROM \"orders\" WHERE status=${'$'}1 AND created_at>=${'$'}2",
                    params = emptyList(),
                ),
                Dialect.Postgres,
            ),
        )
    }

    private fun mysqlConnection(mode: TransportSecurityMode) = ConnectionDef(
        id = "mysql-test",
        name = "MySQL test",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "testuser",
        transportSecurity = TransportSecurity(mode = mode),
    )

    private fun connection(dialect: Dialect, port: Int, mode: TransportSecurityMode) = ConnectionDef(
        id = "${dialect.name}-test",
        name = "${dialect.name} test",
        dialect = dialect,
        host = "db.example.com",
        port = port,
        database = "app",
        username = "testuser",
        transportSecurity = TransportSecurity(mode = mode),
    )
}

private fun fakeResultSet(value: Any?): ResultSet =
    Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getObject" -> value
            "getBoolean" -> value as Boolean
            "getLong" -> (value as Number).toLong()
            "getDouble" -> (value as Number).toDouble()
            "getBytes" -> value as ByteArray?
            "wasNull" -> value == null
            "toString" -> "FakeResultSet($value)"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet
