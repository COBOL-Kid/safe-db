package com.safedb.store

import com.safedb.model.CURRENT_CONNECTION_VERSION
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.HistoryEntry
import com.safedb.model.LiteralKind
import com.safedb.model.QuerySpec
import com.safedb.model.QueryRiskGate
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.model.TableRef
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.ThemePalette
import com.safedb.model.normalizeSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun tempDir() = Files.createTempDirectory("safedb-store-test")

    private fun sampleConnection(id: String) = ConnectionDef(
        id = id,
        name = "Conn $id",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "demo",
        username = "readonly",
        transportSecurity = TransportSecurity(),
    )

    private fun sampleSpec() = QuerySpec(
        tables = listOf(TableRef("public", "users", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup(
            connector = GroupConnector.And,
            children = emptyList(),
        ),
        limit = 100,
    )

    @Test
    fun configStoreRoundTripsConnections() {
        val dir = tempDir()
        val store = ConfigStore.new(dir)
        assertTrue(store.list().isEmpty())

        val conn = sampleConnection("c1")
        store.save(conn)
        assertEquals("Conn c1", store.get("c1")?.name)

        store.save(conn.copy(name = "Updated"))
        assertEquals("Updated", store.get("c1")?.name)

        store.delete("c1")
        assertEquals(null, store.get("c1"))
    }

    @Test
    fun configStoreHandlesMissingAndEmptyFiles() {
        val dir = tempDir()
        val store = ConfigStore.new(dir)
        assertTrue(store.list().isEmpty())
        Files.writeString(dir.resolve("connections.json"), "   ")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun queryStoreSavedQueriesUpsertAndDelete() {
        val dir = tempDir()
        val store = QueryStore.new(dir)
        val saved = SavedQuery("q1", "Users", "c1", sampleSpec(), "1")
        store.saveQuery(saved)
        store.saveQuery(saved.copy(name = "All Users"))
        assertEquals("All Users", store.listSaved().single().name)
        store.deleteSaved("q1")
        assertTrue(store.listSaved().isEmpty())
    }

    @Test
    fun queryStoreHistoryPrependsAndCapsAt100() {
        val dir = tempDir()
        val store = QueryStore.new(dir)
        repeat(105) { i ->
            store.addHistory(
                HistoryEntry(
                    id = "h$i",
                    connectionId = "c1",
                    connectionName = "Conn",
                    spec = sampleSpec(),
                    rowCount = i,
                    warnings = emptyList(),
                    timestamp = i.toString(),
                ),
            )
        }
        val history = store.listHistory()
        assertEquals(100, history.size)
        assertEquals("h104", history.first().id)
        assertEquals("h5", history.last().id)
    }

    @Test
    fun queryStoreClearHistory() {
        val dir = tempDir()
        val store = QueryStore.new(dir)
        store.addHistory(
            HistoryEntry("h1", "c1", "Conn", sampleSpec(), 1, emptyList(), timestamp = "1"),
        )
        store.clearHistory()
        assertTrue(store.listHistory().isEmpty())
    }

    @Test
    fun settingsStoreDefaultsAndRoundTrip() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        val defaults = store.load()
        assertEquals("light", defaults.theme)
        assertEquals(ThemePalette.DEFAULT.id, defaults.colorScheme)
        assertEquals(QueryRiskGate.Standard, defaults.queryRiskGate)
        assertEquals(Settings.DEFAULT_COST_THRESHOLD, defaults.explainCostThreshold)
        assertEquals(Settings.defaultDialectThresholds(), defaults.explainCostThresholds)
        assertTrue(defaults.blockedSchemas.isEmpty())

        Files.writeString(dir.resolve("settings.json"), """{"blocked_schemas":["audit"]}""")
        val loaded = store.load()
        assertEquals(listOf("audit"), loaded.blockedSchemas)
        assertEquals("light", loaded.theme)
        assertEquals(ThemePalette.DEFAULT.id, loaded.colorScheme)
        assertEquals(Settings.DEFAULT_COST_THRESHOLD, loaded.costThreshold(Dialect.Postgres))
    }

    @Test
    fun settingsStoreNormalizesLegacyThemeValuesToLight() {
        val dir = tempDir()
        Files.writeString(dir.resolve("settings.json"), """{"theme":"system"}""")

        val settings = SettingsStore.new(dir).load()

        assertEquals("light", settings.theme)
    }

    @Test
    fun settingsStoreMigratesLegacyScalarThresholdToEveryDialect() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        Files.writeString(dir.resolve("settings.json"), """{"explain_cost_threshold":42.0}""")

        val loaded = store.load()

        assertEquals(42.0, loaded.explainCostThreshold)
        assertTrue(Dialect.entries.all { loaded.costThreshold(it) == 42.0 })

        store.save(loaded)
        val persistedSettings = Files.readString(dir.resolve("settings.json"))
        assertTrue(persistedSettings.contains("\"explain_cost_threshold\":"))
        assertTrue(persistedSettings.contains("\"explain_cost_thresholds\":"))
        assertTrue(Dialect.entries.all { store.load().costThreshold(it) == 42.0 })
    }

    @Test
    fun settingsStoreSaveRoundTripsNonDefaultValues() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        val saved = Settings(
            blockedSchemas = listOf("pg_catalog", "information_schema"),
            explainCostThreshold = 42.5,
            explainCostThresholds = mapOf(
                Dialect.Postgres to 42.5,
                Dialect.MySql to 75.0,
                Dialect.Mssql to 125.0,
                Dialect.Oracle to 250.0,
            ),
            theme = "dark",
            colorScheme = ThemePalette.Oxide.id,
            queryRiskGate = QueryRiskGate.Flexible,
            defaultConnectionId = "connection-1",
            defaultSchema = "Reporting",
        )
        store.save(saved)
        val loaded = store.load()
        assertEquals(saved.blockedSchemas, loaded.blockedSchemas)
        assertEquals(saved.explainCostThreshold, loaded.explainCostThreshold)
        assertEquals(saved.explainCostThresholds, loaded.explainCostThresholds)
        assertEquals("dark", loaded.theme)
        assertEquals(ThemePalette.Oxide.id, loaded.colorScheme)
        assertEquals(QueryRiskGate.Flexible, loaded.queryRiskGate)
        assertEquals("connection-1", loaded.defaultConnectionId)
        assertEquals("Reporting", loaded.defaultSchema)
    }

    @Test
    fun settingsStoreLoadsLegacySettingsWithoutDefaultLocation() {
        val dir = tempDir()
        Files.writeString(dir.resolve("settings.json"), """{"theme":"dark"}""")

        val settings = SettingsStore.new(dir).load()

        assertNull(settings.defaultConnectionId)
        assertNull(settings.defaultSchema)
    }

    @Test
    fun settingsStoreRepairsUnknownColorScheme() {
        val dir = tempDir()
        Files.writeString(dir.resolve("settings.json"), """{"color_scheme":"future-scheme"}""")

        val settings = SettingsStore.new(dir).load()

        assertEquals(ThemePalette.DEFAULT.id, settings.colorScheme)
        assertEquals(ThemePalette.DEFAULT, settings.palette())
    }

    @Test
    fun settingsStoreLoadReturnsDefaultsForMissingOrEmptyFile() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        assertEquals(Settings.default(), store.load())
        Files.writeString(dir.resolve("settings.json"), "   \n")
        assertEquals(Settings.default(), store.load())
    }

    @Test
    fun storesReadExistingJsonLayoutTogether() {
        val dir = tempDir()
        Files.writeString(
            dir.resolve("connections.json"),
            """
            [
              {
                "version": 2,
                "id": "c1",
                "name": "Local PG",
                "dialect": "Postgres",
                "host": "localhost",
                "port": 5432,
                "database": "demo",
                "username": "readonly",
                "transport_security": {
                  "mode": "Disabled",
                  "legacy_implicit": true
                }
              }
            ]
            """.trimIndent(),
        )
        val specJson = """
            {
              "tables": [{"schema": "public", "name": "users", "alias": "t0"}],
              "columns": [{"table_alias": "t0", "column": "name"}],
              "joins": [],
              "filters": {"id": "root", "connector": "And", "children": []},
              "limit": 100,
              "schema_version": 3,
              "connector_overrides": {}
            }
        """.trimIndent()
        Files.writeString(
            dir.resolve("saved_queries.json"),
            """
            [
              {
                "id": "q1",
                "name": "Saved from existing data",
                "connection_id": "c1",
                "spec": $specJson,
                "created_at": "1710000000"
              }
            ]
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("query_history.json"),
            """
            [
              {
                "id": "h1",
                "connection_id": "c1",
                "connection_name": "Local PG",
                "spec": $specJson,
                "row_count": 3,
                "warnings": [],
                "timestamp": "1710000001"
              }
            ]
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("settings.json"),
            """
            {
              "blocked_schemas": ["audit"],
              "explain_cost_threshold": 42.0,
              "theme": "dark"
            }
            """.trimIndent(),
        )

        val connections = ConfigStore.new(dir).list()
        val queries = QueryStore.new(dir)
        val settings = SettingsStore.new(dir).load()

        assertEquals("Local PG", connections.single().name)
        assertEquals(
            TransportSecurity().copy(mode = TransportSecurityMode.Disabled, legacyImplicit = true),
            connections.single().transportSecurity,
        )
        assertEquals("Saved from existing data", queries.listSaved().single().name)
        assertEquals("Local PG", queries.listHistory().single().connectionName)
        assertEquals(listOf("audit"), settings.blockedSchemas)
        assertEquals(42.0, settings.explainCostThreshold)
        assertTrue(Dialect.entries.all { settings.costThreshold(it) == 42.0 })
        assertEquals("dark", settings.theme)
    }

    @Test
    fun normalizeSettingsRepairsBlockedSchemasThresholdsAndTheme() {
        val normalized = normalizeSettings(
            Settings(
                blockedSchemas = listOf("Audit", "audit"),
                explainCostThreshold = 0.5,
                explainCostThresholds = mapOf(
                    Dialect.Postgres to 0.5,
                    Dialect.MySql to 20_000_000.0,
                ),
                theme = "dark",
                colorScheme = " SIGNAL-TEAL ",
            ),
        )
        assertEquals(listOf("audit"), normalized.blockedSchemas)
        assertEquals(Settings.MIN_COST_THRESHOLD, normalized.explainCostThreshold)
        assertEquals(Settings.MIN_COST_THRESHOLD, normalized.costThreshold(Dialect.Postgres))
        assertEquals(Settings.MAX_COST_THRESHOLD, normalized.costThreshold(Dialect.MySql))
        assertEquals(Settings.MIN_COST_THRESHOLD, normalized.costThreshold(Dialect.Mssql))
        assertEquals(Settings.MIN_COST_THRESHOLD, normalized.costThreshold(Dialect.Oracle))
        assertEquals(
            Settings.DEFAULT_COST_THRESHOLD,
            Settings(
                explainCostThresholds = mapOf(Dialect.Postgres to Double.NaN),
            ).costThreshold(Dialect.Postgres),
        )
        assertEquals("dark", normalized.theme)
        assertEquals(ThemePalette.SignalTeal.id, normalized.colorScheme)
    }

    @Test
    fun normalizeSettingsTrimsDefaultLocationAndClearsOrphanedSchema() {
        val normalized = normalizeSettings(
            Settings(defaultConnectionId = " connection-1 ", defaultSchema = " Reporting "),
        )
        assertEquals("connection-1", normalized.defaultConnectionId)
        assertEquals("Reporting", normalized.defaultSchema)

        val orphaned = normalizeSettings(Settings(defaultSchema = "Reporting"))
        assertNull(orphaned.defaultConnectionId)
        assertNull(orphaned.defaultSchema)
    }

    @Test
    fun configStoreMigratesLegacyConnectionsMissingTransportSecurity() {
        val dir = tempDir()
        Files.writeString(
            dir.resolve("connections.json"),
            """
            [
              {
                "version": 1,
                "id": "legacy",
                "name": "Legacy MySQL",
                "dialect": "MySql",
                "host": "localhost",
                "port": 3306,
                "database": "safedb_test",
                "username": "root"
              }
            ]
            """.trimIndent(),
        )

        val store = ConfigStore.new(dir)
        val migrated = store.list().single()

        assertEquals(
            TransportSecurity(mode = TransportSecurityMode.Disabled, legacyImplicit = true),
            migrated.transportSecurity,
        )
        assertEquals(CURRENT_CONNECTION_VERSION, migrated.version)
        assertTrue(Files.exists(dir.resolve("connections.migration.bak")))
    }

    @Test
    fun queryStoreMigratesV1SavedQueries() {
        val dir = tempDir()
        val v1 = """
            [
              {
                "id": "q1",
                "name": "Old Users",
                "connection_id": "c1",
                "spec": {
                  "tables": [{"schema": "public", "name": "users", "alias": "t0"}],
                  "columns": [],
                  "joins": [],
                  "filters": [
                    {"table_alias": "t0", "column": "age", "op": "Gt", "value": "21"},
                    {"table_alias": "t0", "column": "deleted_at", "op": "IsNull", "value": null}
                  ],
                  "limit": 50
                },
                "created_at": "1"
              }
            ]
        """.trimIndent()
        Files.writeString(dir.resolve("saved_queries.json"), v1)
        val store = QueryStore.new(dir)
        val saved = store.listSaved()
        assertEquals(1, saved.size)
        val spec = saved.single().spec
        assertEquals(CURRENT_SCHEMA_VERSION, spec.schemaVersion)
        assertEquals(50, spec.limit)
        assertEquals(GroupConnector.And, spec.filters.connector)
        assertEquals(2, spec.filters.children.size)

        val leaf0 = (spec.filters.children[0] as FilterNode.Leaf).spec
        assertEquals("t0", leaf0.tableAlias)
        assertEquals("age", leaf0.column)
        assertEquals(FilterOp.Gt, leaf0.op)
        val value0 = leaf0.value as FilterValue.Single
        assertEquals(LiteralKind.Text, value0.literal.kind)
        assertEquals("21", value0.literal.text)

        val leaf1 = (spec.filters.children[1] as FilterNode.Leaf).spec
        assertEquals(FilterOp.IsNull, leaf1.op)
        assertEquals(null, leaf1.value)

        val rewritten = Files.readString(dir.resolve("saved_queries.json"))
        assertTrue(rewritten.contains("\"connector\""))
        assertFalse(rewritten.contains("\"filters\":["))
        assertTrue(Files.exists(dir.resolve("saved_queries.migration.bak")))
    }

    @Test
    fun queryStoreQuarantinesMalformedWholeFile() {
        val dir = tempDir()
        val path = dir.resolve("saved_queries.json")
        Files.writeString(path, "not-json")

        val failure = assertFailsWith<IllegalStateException> {
            QueryStore.new(dir).listSaved()
        }

        assertTrue(failure.message?.contains("saved_queries.json was corrupt") == true)
        assertFalse(Files.exists(path))
        assertEquals(
            1L,
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith("saved_queries.corrupt-") }.count()
            },
        )
    }

    @Test
    fun queryStoreDropsMalformedEntriesButPreservesValidOnes() {
        val dir = tempDir()
        val validSpec = """
            {
              "tables": [], "columns": [], "joins": [],
              "filters": {"id":"root", "connector":"And", "children":[]},
              "limit":100, "schema_version":3, "connector_overrides":{}
            }
        """.trimIndent()
        Files.writeString(
            dir.resolve("saved_queries.json"),
            """
            [
              {"id":"good", "name":"Good", "connection_id":"c1", "spec":$validSpec, "created_at":"1"},
              {"id":42, "broken":true}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("good"), QueryStore.new(dir).listSaved().map { it.id })
    }

    @Test
    fun configAndSettingsStoresSurfaceMalformedFilesWithoutOverwritingThem() {
        val dir = tempDir()
        val connections = dir.resolve("connections.json")
        val settings = dir.resolve("settings.json")
        Files.writeString(connections, "{")
        Files.writeString(settings, "{")

        assertFailsWith<Exception> { ConfigStore.new(dir).list() }
        assertFailsWith<Exception> { SettingsStore.new(dir).load() }
        assertEquals("{", Files.readString(connections))
        assertEquals("{", Files.readString(settings))
    }
}
