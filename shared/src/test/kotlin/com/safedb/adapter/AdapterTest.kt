package com.safedb.adapter

import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.ResultCell
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.io.InputStream
import java.io.Reader
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdapterTest {
    @Test
    fun columnsFromCompiledSqlHandlesDialectQuotesAndMssqlTop() {
        val pg =
            """
            SELECT "t0"."id" AS "t0__id", "t0"."name" AS "t0__name"
            FROM "public"."users" AS "t0"
            LIMIT 101
            """
                .trimIndent()
        assertEquals(listOf("t0__id", "t0__name"), columnsFromCompiledSql(pg, Dialect.Postgres))

        val mysql = "SELECT `t0`.`id` AS `t0__id`\nFROM `app`.`users` AS `t0`\nLIMIT 101"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mysql, Dialect.MySql))

        val mssql = "SELECT TOP 101 [t0].[id] AS [t0__id]\nFROM [dbo].[users] AS [t0]"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mssql, Dialect.Mssql))
    }

    @Test
    fun mssqlShowplanSqlDeclaresTypedParametersWithoutExposingTextSyntax() {
        val sql =
            mssqlShowplanSql(
                CompiledQuery(
                    "SELECT * FROM [orders] WHERE [status] = @P1 AND [created_at] >= @P2",
                    listOf(
                        BindValue.Text("delivered'; DROP TABLE orders; --"),
                        BindValue.DateTime(LocalDateTime.of(2025, 1, 2, 3, 4, 5)),
                    ),
                ),
            )

        assertTrue(
            sql.startsWith(
                "DECLARE @P1 nvarchar(max) = N'delivered''; DROP TABLE orders; --';\n" +
                    "DECLARE @P2 datetime2 = '2025-01-02T03:04:05';\n",
            ),
        )
        assertTrue(sql.endsWith("[status] = @P1 AND [created_at] >= @P2"))
    }

    @Test
    fun mysqlDisabledTransportAllowsPublicKeyRetrievalForLocalAuth() {
        val url = buildJdbcUrl(mysqlConnection(TransportSecurityMode.Disabled))

        assertEquals(
            "jdbc:mysql://localhost:3306/safedb_test?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            url,
        )
    }

    @Test
    fun mysqlVerifiedTransportDoesNotAllowPublicKeyRetrieval() {
        val url = buildJdbcUrl(mysqlConnection(TransportSecurityMode.VerifyIdentity))

        assertEquals(
            "jdbc:mysql://localhost:3306/safedb_test?sslMode=VERIFY_IDENTITY",
            url,
        )
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
    fun jdbcDecoderKeepsNullsAndZeroSentinelsDistinct() {
        for (type in
            listOf("BOOL", "INTEGER", "DOUBLE PRECISION", "BYTEA", "VARCHAR", "NUMERIC", "OTHER")) {
            assertEquals(ResultCell.Null, decodeJdbcValue(fakeResultSet(null), 1, type), type)
        }
        assertEquals(ResultCell.BoolCell(false), decodeJdbcValue(fakeResultSet(false), 1, "BOOL"))
        assertEquals(ResultCell.IntegerCell(0), decodeJdbcValue(fakeResultSet(0L), 1, "INTEGER"))
        assertEquals(ResultCell.FloatCell(0.0), decodeJdbcValue(fakeResultSet(0.0), 1, "FLOAT8"))
    }

    @Test
    fun jdbcDecoderReadsAnIntegerWithOneTypedGetter() {
        val calls = mutableListOf<String>()
        val rs =
            Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, _ ->
                if (method.name != "toString") calls += method.name
                when (method.name) {
                    "getLong" -> 4L
                    "wasNull" -> false
                    "toString" -> "rs"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSet

        val cell = assertIs<ResultCell.IntegerCell>(decodeJdbcValue(rs, 1, "BIGINT"))

        assertEquals(4L, cell.value)
        assertEquals(listOf("getLong", "wasNull"), calls)
    }

    @Test
    fun jdbcDecoderReadsDateAndDecimalWithGetString() {
        for ((type, value) in listOf("DATE" to "2026-01-02", "DECIMAL" to "123.45")) {
            val calls = mutableListOf<String>()
            val rs =
                Proxy.newProxyInstance(
                    ResultSet::class.java.classLoader,
                    arrayOf(ResultSet::class.java),
                ) { _, method, _ ->
                    if (method.name != "toString") calls += method.name
                    when (method.name) {
                        "getString" -> value
                        "toString" -> "rs"
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as ResultSet

            val cell = assertIs<ResultCell.TextCell>(decodeJdbcValue(rs, 1, type))

            assertEquals(value, cell.value.text)
            assertEquals(listOf("getString"), calls)
        }
    }

    @Test
    fun jdbcDecoderStopsTextAndBinaryStreamsAfterOneExtraUnit() {
        val cap = com.safedb.model.MAX_CELL_BYTES
        val ascii = BoundedSourceReader("x".repeat(cap + 50))
        val asciiCell =
            assertIs<ResultCell.TextCell>(
                decodeJdbcValue(streamResultSet(reader = ascii), 1, "VARCHAR"),
            )
        assertTrue(asciiCell.value.truncated)
        assertEquals(cap, asciiCell.value.text.toByteArray(Charsets.UTF_8).size)
        assertEquals(cap + 1, ascii.supplied)

        val exact = BoundedSourceReader("x".repeat(cap))
        val exactCell =
            assertIs<ResultCell.TextCell>(
                decodeJdbcValue(streamResultSet(reader = exact), 1, "TEXT"),
            )
        assertFalse(exactCell.value.truncated)
        assertEquals(cap, exact.supplied)

        val accented = BoundedSourceReader("é".repeat(cap / 2 + 5))
        val accentedCell =
            assertIs<ResultCell.TextCell>(
                decodeJdbcValue(streamResultSet(reader = accented), 1, "CLOB"),
            )
        assertTrue(accentedCell.value.truncated)
        assertEquals(cap, accentedCell.value.text.toByteArray(Charsets.UTF_8).size)
        assertEquals(cap / 2 + 1, accented.supplied)

        val bytes = BoundedSourceStream(ByteArray(cap + 50) { 7 })
        val binaryCell =
            assertIs<ResultCell.BinaryCell>(
                decodeJdbcValue(streamResultSet(stream = bytes), 1, "BLOB"),
            )
        assertTrue(binaryCell.value.truncated)
        assertEquals(cap, Base64.getDecoder().decode(binaryCell.value.base64).size)
        assertEquals(cap + 1, bytes.supplied)
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
                connection(Dialect.Oracle, 1521, TransportSecurityMode.VerifyIdentity)
                    .copy(
                        transportSecurity =
                            TransportSecurity(
                                mode = TransportSecurityMode.VerifyIdentity,
                                oracleWalletLocation = "/tmp/safe wallet",
                            ),
                    ),
            ),
        )
    }

    @Test
    fun datasourcePropertiesEncodeMssqlSecurityChoices() {
        val verified =
            createDataSourceConfig(
                    connection(Dialect.Mssql, 1433, TransportSecurityMode.VerifyIdentity),
                    "pw",
                )
                .dataSourceProperties
        assertEquals("true", verified.getProperty("encrypt"))
        assertEquals("false", verified.getProperty("trustServerCertificate"))
        assertEquals("db.example.com", verified.getProperty("hostNameInCertificate"))
        assertNull(verified.getProperty("trustStore"))
        assertNull(verified.getProperty("trustStoreType"))

        val encryptOnly =
            createDataSourceConfig(
                    connection(Dialect.Mssql, 1433, TransportSecurityMode.EncryptOnly),
                    "pw",
                )
                .dataSourceProperties
        assertEquals("true", encryptOnly.getProperty("encrypt"))
        assertEquals("true", encryptOnly.getProperty("trustServerCertificate"))
    }

    @Test
    fun postgresVerifiedModesPreservePgjdbcDefaultsWithoutConfiguredCa() {
        for (mode in listOf(TransportSecurityMode.VerifyIdentity, TransportSecurityMode.VerifyCa)) {
            val properties =
                createDataSourceConfig(connection(Dialect.Postgres, 5432, mode), "pw")
                    .dataSourceProperties
            assertNull(properties.getProperty("sslfactory"))
            assertNull(properties.getProperty("sslrootcert"))
        }
        for (mode in listOf(TransportSecurityMode.EncryptOnly, TransportSecurityMode.Disabled)) {
            val properties =
                createDataSourceConfig(connection(Dialect.Postgres, 5432, mode), "pw")
                    .dataSourceProperties
            assertNull(properties.getProperty("sslfactory"))
        }
    }

    @Test
    fun postgresLaunchProfileTrustUsesRootCertWithoutReplacingPgjdbcFactory() {
        val launchRootCert = Files.createTempFile("safedb-launch-root", ".pem")
        try {
            val properties =
                createDataSourceConfig(
                        connection(Dialect.Postgres, 5432, TransportSecurityMode.VerifyIdentity),
                        "pw",
                        launchProfilePostgresRootCert = launchRootCert.toString(),
                    )
                    .dataSourceProperties

            assertNull(properties.getProperty("sslfactory"))
            assertEquals(launchRootCert.toString(), properties.getProperty("sslrootcert"))
        } finally {
            Files.deleteIfExists(launchRootCert)
        }
    }

    @Test
    fun postgresLaunchProfileRootIsIgnoredForModesWithoutCertificateVerification() {
        for (mode in listOf(TransportSecurityMode.EncryptOnly, TransportSecurityMode.Disabled)) {
            val properties =
                createDataSourceConfig(
                        connection(Dialect.Postgres, 5432, mode),
                        "pw",
                        launchProfilePostgresRootCert = "/managed/roots.pem",
                    )
                    .dataSourceProperties

            assertNull(properties.getProperty("sslrootcert"))
        }
    }

    @Test
    fun mysqlVerifiedTransportUsesDriverOrLaunchProfileTrust() {
        val properties =
            createDataSourceConfig(
                    connection(Dialect.MySql, 3306, TransportSecurityMode.VerifyIdentity),
                    "pw",
                )
                .dataSourceProperties

        assertNull(properties.getProperty("trustCertificateKeyStoreUrl"))
        assertNull(properties.getProperty("trustCertificateKeyStoreType"))
    }

    @Test
    fun sqlServerVerifiedTransportDoesNotSetAConnectionTrustStore() {
        val properties =
            createDataSourceConfig(
                    connection(Dialect.Mssql, 1433, TransportSecurityMode.VerifyIdentity),
                    "pw",
                )
                .dataSourceProperties

        assertEquals("false", properties.getProperty("trustServerCertificate"))
        assertNull(properties.getProperty("trustStore"))
        assertNull(properties.getProperty("trustStoreType"))
        assertNull(properties.getProperty("trustStorePassword"))
    }

    @Test
    fun oracleIdentityModeEnablesServerDnMatching() {
        val identity =
            connection(Dialect.Oracle, 1521, TransportSecurityMode.VerifyIdentity)
                .copy(
                    transportSecurity =
                        TransportSecurity(
                            TransportSecurityMode.VerifyIdentity,
                            oracleWalletLocation = "/wallet",
                        ),
                )
        val caOnly =
            identity.copy(
                transportSecurity =
                    identity.transportSecurity.copy(mode = TransportSecurityMode.VerifyCa),
            )

        assertEquals(
            "true",
            createDataSourceConfig(identity, "pw")
                .dataSourceProperties
                .getProperty("oracle.net.ssl_server_dn_match"),
        )
        assertEquals(
            "false",
            createDataSourceConfig(caOnly, "pw")
                .dataSourceProperties
                .getProperty("oracle.net.ssl_server_dn_match"),
        )
    }

    @Test
    fun datasourceReceivesCustomDriverPropertiesAndManagedSecurityWins() {
        val config =
            createDataSourceConfig(
                connection(Dialect.Mssql, 1433, TransportSecurityMode.VerifyIdentity)
                    .copy(
                        driverProperties =
                            listOf(
                                DriverProperty("applicationName", "Enterprise Reporting"),
                                DriverProperty("encrypt", "false"),
                            ),
                    ),
                "pw",
            )

        assertEquals(
            "Enterprise Reporting",
            config.dataSourceProperties.getProperty("applicationName"),
        )
        assertEquals("true", config.dataSourceProperties.getProperty("encrypt"))
        assertEquals("10", config.dataSourceProperties.getProperty("loginTimeout"))
        assertEquals("30000", config.dataSourceProperties.getProperty("socketTimeout"))
    }

    @Test
    fun datasourcePinsConnectTimeoutsForEveryDialect() {
        val connectSeconds = (CONNECT_TIMEOUT_MS / 1000L).toString()
        val connectMillis = CONNECT_TIMEOUT_MS.toString()
        val readSeconds = (INTROSPECTION_TIMEOUT_MS / 1000L).toString()
        val readMillis = INTROSPECTION_TIMEOUT_MS.toString()

        val postgres =
            createDataSourceConfig(
                    connection(Dialect.Postgres, 5432, TransportSecurityMode.Disabled)
                        .copy(
                            driverProperties =
                                listOf(
                                    DriverProperty("connectTimeout", "0"),
                                    DriverProperty("socketTimeout", "0"),
                                ),
                        ),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectSeconds, postgres.getProperty("connectTimeout"))
        assertEquals(connectSeconds, postgres.getProperty("loginTimeout"))
        assertEquals(readSeconds, postgres.getProperty("socketTimeout"))

        val mysql =
            createDataSourceConfig(
                    mysqlConnection(TransportSecurityMode.Disabled)
                        .copy(driverProperties = listOf(DriverProperty("connectTimeout", "0"))),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectMillis, mysql.getProperty("connectTimeout"))
        assertEquals(readMillis, mysql.getProperty("socketTimeout"))

        val mssql =
            createDataSourceConfig(
                    connection(Dialect.Mssql, 1433, TransportSecurityMode.Disabled),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectSeconds, mssql.getProperty("loginTimeout"))
        assertEquals(readMillis, mssql.getProperty("socketTimeout"))

        val oracle =
            createDataSourceConfig(
                    connection(Dialect.Oracle, 1521, TransportSecurityMode.Disabled)
                        .copy(
                            transportSecurity =
                                TransportSecurity(
                                    mode = TransportSecurityMode.Disabled,
                                    oracleWalletLocation = null,
                                ),
                        ),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectMillis, oracle.getProperty("oracle.net.CONNECT_TIMEOUT"))
        assertEquals(readMillis, oracle.getProperty("oracle.jdbc.ReadTimeout"))
        assertEquals(readMillis, oracle.getProperty("oracle.net.READ_TIMEOUT"))

        val db2 =
            createDataSourceConfig(
                    connection(Dialect.Db2, 50000, TransportSecurityMode.Disabled),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectSeconds, db2.getProperty("loginTimeout"))
        assertEquals(readSeconds, db2.getProperty("blockingReadConnectionTimeout"))
    }

    @Test
    fun prepareStatementRewritesPlaceholdersAndBindsEveryValueKind() {
        val calls = mutableListOf<Pair<String, Any?>>()
        var preparedSql = ""
        var fetchSize: Int? = null
        val statement =
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setFetchSize" -> fetchSize = args?.first() as Int
                    else ->
                        if (method.name.startsWith("set")) {
                            calls += method.name to args?.getOrNull(1)
                        }
                }
                when (method.name) {
                    "toString" -> "FakePreparedStatement"
                    else -> null
                }
            } as PreparedStatement
        val connection =
            Proxy.newProxyInstance(
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
        val compiled =
            CompiledQuery(
                sql =
                    "SELECT * FROM [t] WHERE a=@P1 AND b=@P2 AND c=@P3 AND d=@P4 AND e=@P5 AND f=@P6 AND g=@P7 AND h=@P8",
                params =
                    listOf(
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

        assertEquals(128, fetchSize)
        assertEquals(8, preparedSql.count { it == '?' })
        assertEquals(
            listOf(
                "setString",
                "setLong",
                "setBigDecimal",
                "setDouble",
                "setBoolean",
                "setObject",
                "setObject",
                "setNull",
            ),
            calls.map { it.first },
        )
        // Temporal values bind as java.time objects so the JVM default zone can never shift them.
        assertEquals(LocalDate.of(2026, 1, 2), calls[5].second)
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), calls[6].second)
        assertTrue(calls.last().second is Int)
    }

    @Test
    fun mysqlPrepareStatementStreamsOneRowAtATime() {
        var fetchSize: Int? = null
        val statement =
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
            ) { _, method, args ->
                if (method.name == "setFetchSize") fetchSize = args?.first() as Int
                when (method.name) {
                    "toString" -> "FakePreparedStatement"
                    else -> null
                }
            } as PreparedStatement
        val connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "prepareStatement" -> statement
                    "toString" -> "FakeConnection"
                    else -> null
                }
            } as Connection

        prepareStatement(connection, CompiledQuery("SELECT 1", emptyList()), Dialect.MySql)

        assertEquals(Int.MIN_VALUE, fetchSize)
    }

    @Test
    fun decodeQueryResultStopsAtByteBudgetWithoutReadingLaterRows() {
        val totalRows = 40
        val payload = "x".repeat(512 * 1024)
        var cursor = -1
        var nextCalls = 0
        var getterCalls = 0
        val meta =
            Proxy.newProxyInstance(
                ResultSetMetaData::class.java.classLoader,
                arrayOf(ResultSetMetaData::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getColumnCount" -> 1
                    "getColumnLabel" -> "payload"
                    "getColumnTypeName" -> "VARCHAR"
                    "toString" -> "meta"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSetMetaData
        val rs =
            Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getMetaData" -> meta
                    "next" -> {
                        nextCalls += 1
                        cursor += 1
                        cursor < totalRows
                    }
                    "getCharacterStream" -> {
                        getterCalls += 1
                        check(cursor < totalRows)
                        payload.reader()
                    }
                    "toString" -> "rs"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSet

        val result = decodeQueryResult(rs, "SELECT payload FROM t", Dialect.Postgres)

        assertTrue(result.truncated)
        assertTrue(result.rowCount in 1 until totalRows)
        assertEquals(result.rowCount + 1, nextCalls)
        assertEquals(result.rowCount + 1, getterCalls)
        assertTrue(result.warnings.any { it.contains("encoded bytes") })
    }

    @Test
    fun decodeQueryResultKeepsJdbcTypesWhenTheSampleIsEmpty() {
        val meta =
            Proxy.newProxyInstance(
                ResultSetMetaData::class.java.classLoader,
                arrayOf(ResultSetMetaData::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getColumnCount" -> 2
                    "getColumnLabel" ->
                        when (args!![0] as Int) {
                            1 -> "customers__id"
                            2 -> "customers__email"
                            else -> error("unexpected column ${args[0]}")
                        }
                    "getColumnTypeName" ->
                        when (args!![0] as Int) {
                            1 -> "int4"
                            2 -> "text"
                            else -> error("unexpected column ${args[0]}")
                        }
                    "toString" -> "meta"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSetMetaData
        val rs =
            Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getMetaData" -> meta
                    "next" -> false
                    "toString" -> "rs"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSet

        val result =
            decodeQueryResult(
                rs,
                """SELECT "customers"."id" AS "customers__id" FROM "public"."customers"""",
                Dialect.Postgres,
            )

        assertEquals(0, result.rowCount)
        assertEquals(
            listOf(
                com.safedb.model.ResultColumn("customers__id", "int4"),
                com.safedb.model.ResultColumn("customers__email", "text"),
            ),
            result.columns,
        )
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

    private fun mysqlConnection(mode: TransportSecurityMode) =
        ConnectionDef(
            id = "mysql-test",
            name = "MySQL test",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb_test",
            username = "testuser",
            transportSecurity = TransportSecurity(mode = mode),
        )

    private fun connection(dialect: Dialect, port: Int, mode: TransportSecurityMode) =
        ConnectionDef(
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
    Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) {
        _,
        method,
        _ ->
        when (method.name) {
            "getObject" -> value
            "getBoolean" -> value as? Boolean ?: false
            "getLong" -> (value as? Number)?.toLong() ?: 0L
            "getDouble" -> (value as? Number)?.toDouble() ?: 0.0
            "getBytes" -> value as? ByteArray
            "getString" -> value?.toString()
            "getCharacterStream" -> value?.toString()?.reader()
            "getBinaryStream" -> (value as? ByteArray)?.inputStream()
            "wasNull" -> value == null
            "toString" -> "FakeResultSet($value)"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet

private fun streamResultSet(reader: Reader? = null, stream: InputStream? = null): ResultSet =
    Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) {
        _,
        method,
        _ ->
        when (method.name) {
            "getCharacterStream" -> reader
            "getBinaryStream" -> stream
            "toString" -> "StreamResultSet"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet

private class BoundedSourceReader(private val data: String) : Reader() {
    var supplied = 0
        private set

    private var index = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (index >= data.length) return -1
        val n = minOf(len, data.length - index)
        for (i in 0 until n) cbuf[off + i] = data[index + i]
        index += n
        supplied += n
        return n
    }

    override fun close() {}
}

private class BoundedSourceStream(private val data: ByteArray) : InputStream() {
    var supplied = 0
        private set

    private var index = 0

    override fun read(): Int {
        if (index >= data.size) return -1
        supplied += 1
        return data[index++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (index >= data.size) return -1
        val n = minOf(len, data.size - index)
        data.copyInto(b, off, index, index + n)
        index += n
        supplied += n
        return n
    }
}
