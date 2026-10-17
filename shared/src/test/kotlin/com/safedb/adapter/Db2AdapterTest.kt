package com.safedb.adapter

import com.safedb.connection.DIALECTS
import com.safedb.connection.formatConnectionString
import com.safedb.connection.parseConnectionString
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.SortDirection
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

        for (name in
            listOf(
                "sslConnection",
                "sslTrustStoreLocation",
                "sslTrustStorePassword",
                "sslKeyStoreLocation",
                "sslKeyStorePassword",
                "securityMechanism",
                "sslClientHostnameValidation",
                "sslCertLocation",
            )) {
            assertTrue(
                base
                    .copy(driverProperties = listOf(DriverProperty(name, "x")))
                    .validate()
                    .isFailure,
                name,
            )
        }
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("currentSchema", "REPORTING")))
                .validate()
                .isSuccess
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
    fun db2IndexEncodesPrimaryDescendingTextAndExpressionKeys() {
        val table = MetadataTableKey("APP", "ORDERS")
        val schema =
            assembleSchema(
                tables = listOf(table),
                columns = emptyList(),
                indexes =
                    listOf(
                        Db2Adapter.db2Index("APP", "ORDERS", "ORDERS_PK", "ID", "P", "A"),
                        Db2Adapter.db2Index("APP", "ORDERS", "ORDERS_DESC", "CREATED", "D", "D"),
                        Db2Adapter.db2Index(
                            "APP",
                            "ORDERS",
                            "ORDERS_TEXT",
                            "NOTES",
                            "D",
                            "A",
                            indexType = "TEXT",
                        ),
                        Db2Adapter.db2Index(
                            "APP",
                            "ORDERS",
                            "ORDERS_EXPR",
                            null,
                            "D",
                            "A",
                            virtual = true,
                        ),
                        Db2Adapter.db2Index(
                            "APP",
                            "ORDERS",
                            "ORDERS_CLUS",
                            "STATUS",
                            "U",
                            "A",
                            indexType = "CLUS",
                        ),
                    ),
                foreignKeys = emptyList(),
            )
        val indexes = schema.tables.single().indexes.associateBy { it.name }

        val primary = indexes.getValue("ORDERS_PK")
        assertEquals(true, primary.isPrimary)
        assertEquals(true, primary.isUnique)
        assertEquals(SortDirection.Asc, primary.keys.single().direction)
        assertEquals(true, primary.capabilities.ordering)

        // UNIQUERULE D means duplicates are allowed. Descending order comes from ORDERING.
        val descending = indexes.getValue("ORDERS_DESC")
        assertEquals(false, descending.isUnique)
        assertEquals(false, descending.isPrimary)
        assertEquals(SortDirection.Desc, descending.keys.single().direction)

        val text = indexes.getValue("ORDERS_TEXT")
        assertEquals("TEXT", text.kind)
        assertEquals(false, text.supportsEquality)
        assertEquals(false, text.capabilities.equality)
        assertEquals(true, text.capabilities.specializedText)
        assertEquals(false, text.capabilities.ordering)

        val expression = indexes.getValue("ORDERS_EXPR")
        assertEquals(true, expression.keys.single().expression)
        assertEquals(null, expression.keys.single().column)
        assertEquals(true, expression.capabilities.expressionKeys)

        val clustered = indexes.getValue("ORDERS_CLUS")
        assertEquals(true, clustered.isUnique)
        assertEquals(false, clustered.isPrimary)
        assertEquals(true, clustered.capabilities.ordering)
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
    fun db2QuotesExplainIdentifiersAndRejectsBreakout() {
        assertEquals("\"APP\"", Db2Adapter.quoteDb2Ident("APP"))
        assertEquals("\"A\"\"B\"", Db2Adapter.quoteDb2Ident("A\"B"))
        assertEquals("\"" + "A".repeat(128) + "\"", Db2Adapter.quoteDb2Ident("A".repeat(128)))
        assertNull(Db2Adapter.quoteDb2Ident("A".repeat(129)))
        assertNull(Db2Adapter.quoteDb2Ident("APP\n"))
    }

    @Test
    fun db2TreatsUndefinedObjectsAsMissingExplainTables() {
        assertTrue(with(Db2Adapter) { SQLException("no", "42704", 0).isUndefinedObject() })
        assertTrue(with(Db2Adapter) { SQLException("no", "HY000", -204).isUndefinedObject() })
        assertTrue(
            with(Db2Adapter) { SQLException("SQLCODE=-204", "HY000", 0).isUndefinedObject() }
        )
        assertTrue(
            with(Db2Adapter) { SQLException("undefined name", "HY000", 0).isUndefinedObject() }
        )
        assertFalse(with(Db2Adapter) { SQLException("other", "HY000", 0).isUndefinedObject() })
        assertFalse(with(Db2Adapter) { RuntimeException("undefined name").isUndefinedObject() })
    }

    @Test
    fun db2SelectsQuotedAuthExplainPrefixAheadOfSystools() {
        assertEquals(
            Db2Adapter.LuwExplainPrefix.Found("\"APP\"."),
            Db2Adapter.selectLuwExplainPrefix(
                "\"APP\"",
                Db2Adapter.ExplainTablePresence.Present,
                Db2Adapter.ExplainTablePresence.Present,
            ),
        )
    }

    @Test
    fun db2UsesSystoolsOnlyWhenAuthExplainTablesAreAbsent() {
        assertEquals(
            Db2Adapter.LuwExplainPrefix.Found("SYSTOOLS."),
            Db2Adapter.selectLuwExplainPrefix(
                "\"APP\"",
                Db2Adapter.ExplainTablePresence.Absent,
                Db2Adapter.ExplainTablePresence.Present,
            ),
        )
        assertEquals(
            Db2Adapter.LuwExplainPrefix.Missing,
            Db2Adapter.selectLuwExplainPrefix(
                "\"APP\"",
                Db2Adapter.ExplainTablePresence.Absent,
                Db2Adapter.ExplainTablePresence.Absent,
            ),
        )
    }

    @Test
    fun db2LeavesExplainPrefixUnreadableWhenAuthLookupFails() {
        assertEquals(
            Db2Adapter.LuwExplainPrefix.Unreadable,
            Db2Adapter.selectLuwExplainPrefix(
                "\"APP\"",
                Db2Adapter.ExplainTablePresence.Failed,
                Db2Adapter.ExplainTablePresence.Present,
            ),
        )
        assertEquals(
            Db2Adapter.LuwExplainPrefix.Unreadable,
            Db2Adapter.selectLuwExplainPrefix(
                null,
                Db2Adapter.ExplainTablePresence.Present,
                Db2Adapter.ExplainTablePresence.Present,
            ),
        )
    }

    @Test
    fun db2KeepsCompleteForeignKeysAndDropsPartialMatches() {
        val orders = MetadataTableKey("APP", "ORDERS")
        val lines = MetadataTableKey("APP", "ORDER_LINES")
        val customer =
            MetadataForeignKey(
                orders,
                "ORDERS_CUSTOMER_FK",
                "CUSTOMER_ID",
                "APP",
                "CUSTOMERS",
                "ID",
            )
        val blankOrder =
            MetadataForeignKey(lines, "LINES_BLANK_FK", "ORDER_ID", "APP", "ORDERS", "ID")
        val blankLine = MetadataForeignKey(lines, "LINES_BLANK_FK", "LINE_NO", "APP", "ORDERS", "")
        val partial =
            MetadataForeignKey(lines, "LINES_PARTIAL_FK", "ORDER_ID", "APP", "ORDERS", "ID")
        val orderId = MetadataForeignKey(lines, "LINES_ORDER_FK", "ORDER_ID", "APP", "ORDERS", "ID")
        val lineNo =
            MetadataForeignKey(lines, "LINES_ORDER_FK", "LINE_NO", "APP", "ORDERS", "LINE_NO")

        assertEquals(
            listOf(customer, orderId, lineNo),
            Db2Adapter.completeDb2ForeignKeys(
                listOf(
                    Db2Adapter.CountedForeignKey(customer, 1),
                    Db2Adapter.CountedForeignKey(blankOrder, 2),
                    Db2Adapter.CountedForeignKey(blankLine, 2),
                    Db2Adapter.CountedForeignKey(partial, 2),
                    Db2Adapter.CountedForeignKey(orderId, 2),
                    Db2Adapter.CountedForeignKey(lineNo, 2),
                )
            ),
        )
    }

    @Test
    fun db2RejectsUrlDelimitersInDatabaseAndHost() {
        val base = connection(TransportSecurityMode.Disabled)

        assertTrue(
            base.copy(database = "app:sslTrustStoreLocation=/tmp/store").validate().isFailure
        )
        assertTrue(base.copy(host = "db.example/app").validate().isFailure)
        assertTrue(base.copy(host = "::1").validate().isSuccess)
        for (character in listOf(':', ';', '/', '?', '#', '@')) {
            assertTrue(
                base.copy(database = "app$character").validate().isFailure,
                "database $character",
            )
        }
        for (character in listOf(';', '/', '?', '#', '@')) {
            assertTrue(
                base.copy(host = "db.example$character").validate().isFailure,
                "host $character",
            )
        }
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
