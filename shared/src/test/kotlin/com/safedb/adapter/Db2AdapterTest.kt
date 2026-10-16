package com.safedb.adapter

import com.safedb.connection.DIALECTS
import com.safedb.connection.formatConnectionString
import com.safedb.connection.parseConnectionString
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Db2AdapterTest {
    @Test
    fun db2JdbcUrlEncodesTlsFlag() {
        assertEquals(
            "jdbc:db2://db.example.com:50000/app:sslConnection=false;",
            buildJdbcUrl(connection(TransportSecurityMode.Disabled)),
        )
        assertEquals(
            "jdbc:db2://db.example.com:50000/app:sslConnection=true;",
            buildJdbcUrl(connection(TransportSecurityMode.VerifyIdentity)),
        )
    }

    @Test
    fun db2DatasourcePinsSslConnectionOverUserProperties() {
        val config =
            createDataSourceConfig(
                connection(TransportSecurityMode.VerifyIdentity)
                    .copy(driverProperties = listOf(DriverProperty("sslConnection", "false"))),
                "pw",
            )

        assertEquals("true", config.dataSourceProperties.getProperty("sslConnection"))
        assertEquals(
            "BASIC",
            config.dataSourceProperties.getProperty("sslClientHostnameValidation"),
        )
    }

    @Test
    fun db2DatasourcePinsHostnameValidationAfterUserProperties() {
        val config =
            createDataSourceConfig(
                connection(TransportSecurityMode.VerifyIdentity)
                    .copy(
                        driverProperties =
                            listOf(DriverProperty("sslClientHostnameValidation", "OFF"))
                    ),
                "pw",
            )

        assertEquals(
            "BASIC",
            config.dataSourceProperties.getProperty("sslClientHostnameValidation"),
        )
    }

    @Test
    fun db2DisabledTransportDoesNotSetHostnameValidation() {
        val config =
            createDataSourceConfig(connection(TransportSecurityMode.Disabled), "pw")
                .dataSourceProperties

        assertEquals("false", config.getProperty("sslConnection"))
        assertEquals(null, config.getProperty("sslClientHostnameValidation"))
    }

    @Test
    fun db2ReservedDriverPropertiesAreRejected() {
        val base = connection(TransportSecurityMode.Disabled)

        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("sslConnection", "true")))
                .validate()
                .isFailure
        )
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("currentSchema", "REPORTING")))
                .validate()
                .isSuccess
        )
        assertTrue(
            base
                .copy(
                    driverProperties = listOf(DriverProperty("sslClientHostnameValidation", "OFF"))
                )
                .validate()
                .isFailure
        )
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("sslCertLocation", "/tmp/cert")))
                .validate()
                .isFailure
        )
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("currentSchema", "A;B")))
                .validate()
                .isFailure
        )
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("bad;name", "x")))
                .validate()
                .isFailure
        )
    }

    @Test
    fun db2Ipv6HostIsBracketedInUrlAndConnectionString() {
        val def = connection(TransportSecurityMode.Disabled).copy(host = "::1")

        assertTrue(buildJdbcUrl(def).contains("jdbc:db2://[::1]:50000/app:"))
        assertTrue(formatConnectionString(def).contains("jdbc:db2://[::1]:50000/app:"))
        val parsed = parseConnectionString("jdbc:db2://[::1]:50000/app:sslConnection=false;")
        assertEquals("::1", parsed.host)
        assertEquals(50000, parsed.port)
        assertEquals("app", parsed.database)
    }

    @Test
    fun db2IncludeColumnsStayOutOfIndexKeys() {
        // A z/OS foreign key whose parent is a non-primary unique index is joined through
        // SYSRELS.IXOWNER and IXNAME. That path needs a live subsystem; this only checks
        // that INCLUDE and blank z/OS ordering stay out of the key list.
        val table = MetadataTableKey("APP", "ORDERS")
        val schema =
            assembleSchema(
                tables = listOf(table),
                columns = emptyList(),
                indexes =
                    listOf(
                        Db2Adapter.db2Index("APP", "ORDERS", "ORDERS_IX", "ID", "U", "A"),
                        Db2Adapter.db2Index("APP", "ORDERS", "ORDERS_IX", "NOTES", "U", "I"),
                        Db2Adapter.db2Index(
                            "APP",
                            "ORDERS",
                            "ORDERS_ZIX",
                            "STATUS",
                            "U",
                            " ",
                            blankOrderMeansNotAKey = true,
                        ),
                    ),
                foreignKeys = emptyList(),
            )
        val indexes = schema.tables.single().indexes
        val index = indexes.single { it.name == "ORDERS_IX" }
        assertEquals(listOf("ID"), index.columns)
        assertEquals(listOf("NOTES"), index.includedColumns)
        assertEquals(listOf("ID"), index.keys.map { it.column })
        val zosInclude = indexes.single { it.name == "ORDERS_ZIX" }
        assertEquals(emptyList(), zosInclude.columns)
        assertEquals(listOf("STATUS"), zosInclude.includedColumns)
        assertEquals(emptyList(), zosInclude.keys)
        assertEquals(false, zosInclude.isPartial)
    }

    @Test
    fun db2VerifiedTransportNeedsNoWallet() {
        assertTrue(connection(TransportSecurityMode.VerifyIdentity).validate().isSuccess)
    }

    @Test
    fun db2PresetUsesDefaultPort() {
        val info = DIALECTS.single { it.value == Dialect.Db2 }

        assertEquals("Db2", info.label)
        assertEquals(50000, info.defaultPort)
    }

    @Test
    fun db2UnquotesDoubleQuotedColumns() {
        val sql =
            "SELECT \"t0\".\"id\" AS \"t0__id\"\nFROM \"APP\".\"users\" AS \"t0\"\nFETCH FIRST 101 ROWS ONLY"

        assertEquals(listOf("t0__id"), columnsFromCompiledSql(sql, Dialect.Db2))
    }

    @Test
    fun db2JdbcSqlPassesCompilerPlaceholdersThrough() {
        val sql = "SELECT * FROM \"orders\" WHERE status=? AND \"col:1\" = ?"

        assertEquals(
            sql,
            jdbcSql(CompiledQuery(sql = sql, params = emptyList()), Dialect.Db2),
        )
    }

    @Test
    fun db2ExplainWithoutConnectionThrows() {
        // EXPLAIN needs a live connection; without explain tables the adapter returns
        // Unavailable instead of failing, so only a missing datasource can be asserted here.
        kotlin.test.assertFailsWith<Exception> {
            Db2Adapter.explain(
                HikariDataSource(),
                CompiledQuery(sql = "SELECT 1 FROM SYSIBM.SYSDUMMY1", params = emptyList()),
            )
        }
    }

    private fun connection(mode: TransportSecurityMode) =
        ConnectionDef(
            id = "db2-test",
            name = "Db2 test",
            dialect = Dialect.Db2,
            host = "db.example.com",
            port = 50000,
            database = "app",
            username = "testuser",
            transportSecurity = TransportSecurity(mode = mode),
        )
}
