package com.safedb.store

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
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.model.TableRef
import com.safedb.model.TransportSecurity
import com.safedb.model.normalizeSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(100_000.0, defaults.explainCostThreshold)
        assertTrue(defaults.blockedSchemas.isEmpty())

        Files.writeString(dir.resolve("settings.json"), """{"blocked_schemas":["audit"]}""")
        val loaded = store.load()
        assertEquals(listOf("audit"), loaded.blockedSchemas)
        assertEquals("light", loaded.theme)
        assertEquals(100_000.0, loaded.explainCostThreshold)
    }

    @Test
    fun settingsStoreSaveRoundTripsNonDefaultValues() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        val saved = Settings(
            blockedSchemas = listOf("pg_catalog", "information_schema"),
            explainCostThreshold = 42.5,
            theme = "dark",
        )
        store.save(saved)
        val loaded = store.load()
        assertEquals(saved.blockedSchemas, loaded.blockedSchemas)
        assertEquals(saved.explainCostThreshold, loaded.explainCostThreshold)
        assertEquals("dark", loaded.theme)
    }

    @Test
    fun settingsStoreLoadReturnsDefaultsForMissingOrEmptyFile() {
        val dir = tempDir()
        val store = SettingsStore.new(dir)
        assertEquals(100_000.0, store.load().explainCostThreshold)
        Files.writeString(dir.resolve("settings.json"), "   \n")
        assertEquals(100_000.0, store.load().explainCostThreshold)
    }

    @Test
    fun normalizeSettingsLowerercasesBlockedSchemasAndClampsThreshold() {
        val normalized = normalizeSettings(
            Settings(
                blockedSchemas = listOf("Audit", "audit"),
                explainCostThreshold = 0.5,
                theme = "dark",
            ),
        )
        assertEquals(listOf("audit"), normalized.blockedSchemas)
        assertEquals(1.0, normalized.explainCostThreshold)
        assertEquals("dark", normalized.theme)
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
}
