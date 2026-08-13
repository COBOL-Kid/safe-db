package com.safedb.store

import com.safedb.model.CURRENT_CONNECTION_VERSION
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.GroupConnector
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.model.TableRef
import com.safedb.model.ThemePalette
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.normalizeSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun tempDir() = Files.createTempDirectory("safedb-store-test")

    private fun assertUnsupportedQuarantine(dir: java.nio.file.Path, original: java.nio.file.Path) {
        assertFalse(Files.exists(original))
        assertEquals(
            1L,
            Files.list(dir).use { files ->
                files
                    .filter {
                        it.fileName
                            .toString()
                            .startsWith(
                                "${original.fileName.toString().substringBeforeLast('.')}.unsupported-"
                            )
                    }
                    .count()
            },
        )
    }

    private fun sampleConnection(id: String) =
        ConnectionDef(
            id = id,
            name = "Conn $id",
            dialect = Dialect.Postgres,
            host = "localhost",
            port = 5432,
            database = "demo",
            username = "readonly",
            transportSecurity = TransportSecurity(),
        )

    private fun sampleSpec(schemaVersion: Int = CURRENT_SCHEMA_VERSION) =
        QuerySpec(
            tables = listOf(TableRef("public", "users", "t0")),
            columns = emptyList(),
            joins = emptyList(),
            filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
            limit = 100,
            schemaVersion = schemaVersion,
        )

    private fun sampleHistory(id: String, schemaVersion: Int = CURRENT_SCHEMA_VERSION) =
        HistoryEntry(
            id = id,
            connectionId = "c1",
            connectionName = "Conn",
            spec = sampleSpec(schemaVersion),
            rowCount = 1,
            warnings = emptyList(),
            timestamp = "1",
        )

    private fun connectionJson(id: String, version: Int, includeTls: Boolean): String {
        val tls =
            if (includeTls) {
                """
                ,
                  "transport_security": { "mode": "Disabled", "legacy_implicit": true },
                  "driver_properties": []
                """
                    .trimIndent()
            } else {
                ""
            }
        return """
            {
              "version": $version,
              "id": "$id",
              "name": "Conn $id",
              "dialect": "Postgres",
              "host": "localhost",
              "port": 5432,
              "database": "demo",
              "username": "readonly"$tls
            }
            """
            .trimIndent()
    }

    private fun savedQueryJson(id: String, schemaVersion: Int) =
        """
        {
          "id": "$id",
          "name": "Query $id",
          "connection_id": "c1",
          "spec": {
            "tables": [], "columns": [], "joins": [],
            "filters": {"id":"root", "connector":"And", "children":[]},
            "limit":100, "schema_version":$schemaVersion, "connector_overrides":{}
          },
          "created_at": "1"
        }
        """
            .trimIndent()

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
    fun configStoreDecodesConnectionMissingTransportSecurityAsDefault() {
        val dir = tempDir()
        Files.writeString(
            dir.resolve("connections.json"),
            """
            [
              {
                "version": 1,
                "id": "c1",
                "name": "Local PG",
                "dialect": "Postgres",
                "host": "localhost",
                "port": 5432,
                "database": "demo",
                "username": "readonly"
              }
            ]
            """
                .trimIndent(),
        )

        val loaded = ConfigStore.new(dir).list().single()

        assertEquals("c1", loaded.id)
        assertEquals(TransportSecurity(), loaded.transportSecurity)
    }

    @Test
    fun configStoreRejectsSavingUnsupportedVersion() {
        val dir = tempDir()
        val store = ConfigStore.new(dir)
        store.save(sampleConnection("c1"))
        val before = Files.readString(dir.resolve("connections.json"))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                store.save(sampleConnection("c2").copy(version = CURRENT_CONNECTION_VERSION + 1))
            }

        assertTrue(failure.message?.contains("Unsupported connection version") == true)
        assertEquals(before, Files.readString(dir.resolve("connections.json")))
        assertEquals(listOf("c1"), store.list().map { it.id })
    }

    @Test
    fun queryStoreRejectsSavingUnsupportedSchemaVersionWithoutReplacing() {
        val dir = tempDir()
        val store = QueryStore.new(dir)
        store.saveQuery(SavedQuery("q1", "Visible", "c1", sampleSpec(), "1"))
        val before = Files.readString(dir.resolve("saved_queries.json"))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                store.saveQuery(
                    SavedQuery("q1", "Hidden", "c1", sampleSpec(CURRENT_SCHEMA_VERSION + 1), "1")
                )
            }

        assertTrue(failure.message?.contains("Unsupported query schema version") == true)
        assertEquals(before, Files.readString(dir.resolve("saved_queries.json")))
        assertEquals("Visible", store.listSaved().single().name)
    }

    @Test
    fun queryStoreRejectsUnsupportedHistoryWithoutEvicting() {
        val dir = tempDir()
        val store = QueryStore.new(dir, maxHistory = 1)
        store.addHistory(sampleHistory("valid"))
        val before = Files.readString(dir.resolve("query_history.json"))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                store.addHistory(sampleHistory("unsupported", CURRENT_SCHEMA_VERSION + 1))
            }

        assertTrue(failure.message?.contains("Unsupported query schema version") == true)
        assertEquals(before, Files.readString(dir.resolve("query_history.json")))
        assertEquals(listOf("valid"), store.listHistory().map { it.id })
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
                )
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
            HistoryEntry("h1", "c1", "Conn", sampleSpec(), 1, emptyList(), timestamp = "1")
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
        assertTrue(defaults.blockedSchemas.isEmpty())

        Files.writeString(dir.resolve("settings.json"), """{"blocked_schemas":["audit"]}""")
        val loaded = store.load()
        assertEquals(listOf("audit"), loaded.blockedSchemas)
        assertEquals("light", loaded.theme)
        assertEquals(ThemePalette.DEFAULT.id, loaded.colorScheme)
    }

    @Test
    fun settingsStoreNormalizesLegacyThemeValuesToLight() {
        val dir = tempDir()
        Files.writeString(dir.resolve("settings.json"), """{"theme":"system"}""")

        val settings = SettingsStore.new(dir).load()

        assertEquals("light", settings.theme)
    }

    @Test
    fun settingsStoreIgnoresAndRemovesLegacyExplainThresholds() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        Files.writeString(
            dir.resolve("settings.json"),
            """
            {
              "blocked_schemas": ["audit"],
              "explain_cost_threshold": 42.0,
              "explain_cost_thresholds": {
                "Postgres": 10.0,
                "MySql": 20.0
              },
              "theme": "dark"
            }
            """
                .trimIndent(),
        )

        val loaded = store.load()

        assertEquals(listOf("audit"), loaded.blockedSchemas)
        assertEquals("dark", loaded.theme)

        store.save(loaded)
        val persistedSettings = Files.readString(dir.resolve("settings.json"))
        assertFalse(persistedSettings.contains("\"explain_cost_threshold\""))
        assertFalse(persistedSettings.contains("\"explain_cost_thresholds\""))
        assertEquals(loaded, store.load())
    }

    @Test
    fun settingsStoreSaveRoundTripsNonDefaultValues() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        val saved =
            Settings(
                blockedSchemas = listOf("pg_catalog", "information_schema"),
                theme = "dark",
                colorScheme = ThemePalette.Oxide.id,
                queryRiskGate = QueryRiskGate.Flexible,
                defaultConnectionId = "connection-1",
                defaultSchema = "Reporting",
                lastSelectedSchemas =
                    mapOf("connection-1" to "Operational", "connection-2" to "Analytics"),
            )
        store.save(saved)
        val loaded = store.load()
        assertEquals(saved.blockedSchemas, loaded.blockedSchemas)
        assertEquals("dark", loaded.theme)
        assertEquals(ThemePalette.Oxide.id, loaded.colorScheme)
        assertEquals(QueryRiskGate.Flexible, loaded.queryRiskGate)
        assertEquals("connection-1", loaded.defaultConnectionId)
        assertEquals("Reporting", loaded.defaultSchema)
        assertEquals(saved.lastSelectedSchemas, loaded.lastSelectedSchemas)
    }

    @Test
    fun settingsStoreLoadsLegacySettingsWithoutDefaultLocation() {
        val dir = tempDir()
        Files.writeString(dir.resolve("settings.json"), """{"theme":"dark"}""")

        val settings = SettingsStore.new(dir).load()

        assertNull(settings.defaultConnectionId)
        assertNull(settings.defaultSchema)
        assertTrue(settings.lastSelectedSchemas.isEmpty())
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
                "version": 1,
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
                },
                "driver_properties": []
              }
            ]
            """
                .trimIndent(),
        )
        val specJson =
            """
            {
              "tables": [{"schema": "public", "name": "users", "alias": "t0"}],
              "columns": [{"table_alias": "t0", "column": "name"}],
              "joins": [],
              "filters": {"id": "root", "connector": "And", "children": []},
              "limit": 100,
              "schema_version": 1,
              "connector_overrides": {}
            }
            """
                .trimIndent()
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
            """
                .trimIndent(),
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
            """
                .trimIndent(),
        )
        Files.writeString(
            dir.resolve("settings.json"),
            """
            {
              "blocked_schemas": ["audit"],
              "explain_cost_threshold": 42.0,
              "theme": "dark"
            }
            """
                .trimIndent(),
        )

        val connections = ConfigStore.new(dir).list()
        val queries = QueryStore.new(dir)
        val settings = SettingsStore.new(dir).load()

        assertEquals("Local PG", connections.single().name)
        assertEquals(
            TransportSecurity(mode = TransportSecurityMode.Disabled),
            connections.single().transportSecurity,
        )
        assertEquals("Saved from existing data", queries.listSaved().single().name)
        assertEquals("Local PG", queries.listHistory().single().connectionName)
        assertEquals(listOf("audit"), settings.blockedSchemas)
        assertEquals("dark", settings.theme)
    }

    @Test
    fun normalizeSettingsRepairsBlockedSchemasAndTheme() {
        val normalized =
            normalizeSettings(
                Settings(
                    blockedSchemas = listOf("Audit", "audit"),
                    theme = "dark",
                    colorScheme = " SIGNAL-TEAL ",
                )
            )
        assertEquals(listOf("audit"), normalized.blockedSchemas)
        assertEquals("dark", normalized.theme)
        assertEquals(ThemePalette.SignalTeal.id, normalized.colorScheme)
    }

    @Test
    fun normalizeSettingsTrimsDefaultLocationAndClearsOrphanedSchema() {
        val normalized =
            normalizeSettings(
                Settings(defaultConnectionId = " connection-1 ", defaultSchema = " Reporting ")
            )
        assertEquals("connection-1", normalized.defaultConnectionId)
        assertEquals("Reporting", normalized.defaultSchema)

        val orphaned = normalizeSettings(Settings(defaultSchema = "Reporting"))
        assertNull(orphaned.defaultConnectionId)
        assertNull(orphaned.defaultSchema)
    }

    @Test
    fun normalizeSettingsRepairsAndOrdersSchemaHistory() {
        val normalized =
            normalizeSettings(
                Settings(
                    lastSelectedSchemas =
                        linkedMapOf(
                            " connection-2 " to " Analytics ",
                            "" to "ignored",
                            "connection-1" to " Reporting ",
                            "connection-3" to "   ",
                        )
                )
            )

        assertEquals(
            linkedMapOf("connection-1" to "Reporting", "connection-2" to "Analytics"),
            normalized.lastSelectedSchemas,
        )
        assertEquals(
            listOf("connection-1", "connection-2"),
            normalized.lastSelectedSchemas.keys.toList(),
        )
    }

    @Test
    fun queryStoreQuarantinesMalformedWholeFile() {
        val dir = tempDir()
        val path = dir.resolve("saved_queries.json")
        Files.writeString(path, "not-json")

        val failure = assertFailsWith<IllegalStateException> { QueryStore.new(dir).listSaved() }

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
    fun settingsStoreSurfacesMalformedFileWithoutOverwritingIt() {
        val dir = tempDir()
        val settings = dir.resolve("settings.json")
        Files.writeString(settings, "{")

        assertFailsWith<Exception> { SettingsStore.new(dir).load() }
        assertEquals("{", Files.readString(settings))
    }

    @Test
    fun configStoreQuarantinesMalformedWholeFileAndRecovers() {
        val dir = tempDir()
        val path = dir.resolve("connections.json")
        Files.writeString(path, "{")
        val store = ConfigStore.new(dir)

        val failure = assertFailsWith<IllegalStateException> { store.list() }

        assertTrue(failure.message?.contains("connections.json was corrupt") == true)
        assertFalse(Files.exists(path))
        assertEquals(
            1L,
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith("connections.corrupt-") }.count()
            },
        )
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun configStoreQuarantinesUnsupportedVersionThenAcceptsSave() {
        val dir = tempDir()
        val path = dir.resolve("connections.json")
        Files.writeString(
            path,
            """
            [
              ${connectionJson("old", CURRENT_CONNECTION_VERSION + 1, includeTls = true)}
            ]
            """
                .trimIndent(),
        )
        val store = ConfigStore.new(dir)

        val failure = assertFailsWith<IllegalStateException> { store.list() }

        assertTrue(failure.message?.contains("used an unsupported schema") == true)
        assertTrue(failure.message?.contains("moved to") == true)
        assertUnsupportedQuarantine(dir, path)
        assertTrue(store.list().isEmpty())

        store.save(sampleConnection("c1"))
        assertEquals(listOf("c1"), store.list().map { it.id })
    }

    @Test
    fun queryStoreQuarantinesUnsupportedSchemaVersionOnList() {
        val dir = tempDir()
        val path = dir.resolve("saved_queries.json")
        Files.writeString(
            path,
            """
            [
              ${savedQueryJson("old", CURRENT_SCHEMA_VERSION + 1)}
            ]
            """
                .trimIndent(),
        )

        val failure = assertFailsWith<IllegalStateException> { QueryStore.new(dir).listSaved() }

        assertTrue(failure.message?.contains("used an unsupported schema") == true)
        assertUnsupportedQuarantine(dir, path)
    }

    @Test
    fun queryStoreQuarantinesWhenAnyEntryIsUnreadable() {
        val dir = tempDir()
        val path = dir.resolve("saved_queries.json")
        val validSpec =
            """
            {
              "tables": [], "columns": [], "joins": [],
              "filters": {"id":"root", "connector":"And", "children":[]},
              "limit":100, "schema_version":$CURRENT_SCHEMA_VERSION, "connector_overrides":{}
            }
            """
                .trimIndent()
        Files.writeString(
            path,
            """
            [
              {"id":"good", "name":"Good", "connection_id":"c1", "spec":$validSpec, "created_at":"1"},
              {"id":42, "broken":true}
            ]
            """
                .trimIndent(),
        )

        val failure = assertFailsWith<IllegalStateException> { QueryStore.new(dir).listSaved() }

        assertTrue(failure.message?.contains("used an unsupported schema") == true)
        assertUnsupportedQuarantine(dir, path)
    }

    @Test
    fun queryStoreQuarantinesUnsupportedHistoryOnList() {
        val dir = tempDir()
        val path = dir.resolve("query_history.json")
        Files.writeString(
            path,
            """
            [
              {
                "id": "old",
                "connection_id": "c1",
                "connection_name": "Conn",
                "spec": {
                  "tables": [], "columns": [], "joins": [],
                  "filters": {"id":"root", "connector":"And", "children":[]},
                  "limit":100, "schema_version":${CURRENT_SCHEMA_VERSION + 1}, "connector_overrides":{}
                },
                "row_count": 0,
                "warnings": [],
                "timestamp": "1"
              }
            ]
            """
                .trimIndent(),
        )

        val failure = assertFailsWith<IllegalStateException> { QueryStore.new(dir).listHistory() }

        assertTrue(failure.message?.contains("used an unsupported schema") == true)
        assertUnsupportedQuarantine(dir, path)
    }
}
