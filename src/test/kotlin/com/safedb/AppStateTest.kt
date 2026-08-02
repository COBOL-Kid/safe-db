package com.safedb

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.QuerySpec
import com.safedb.model.Settings
import com.safedb.model.TableRef
import com.safedb.service.SafeDbService
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppStateTest {
    @Test
    fun defaultSelectionCanUpdateUntilExplicitConnectionTakesPrecedence() {
        val state = AppState(unusedService())

        state.activateDefaultConnection("c1", "public")
        assertEquals("c1", state.activeConnectionId.value)
        assertEquals(
            SchemaSelectionIntent("public", SchemaSelectionSource.StartupDefault),
            state.schemaSelection.value,
        )
        assertEquals(ActiveConnectionOrigin.Default, state.activeConnectionOrigin.value)

        state.activateDefaultConnection("c2", "reporting")
        assertEquals("c2", state.activeConnectionId.value)
        assertEquals("reporting", state.schemaSelection.value.schema)

        state.setActiveConnection(
            "c3",
            SchemaSelectionIntent("explicit_schema", SchemaSelectionSource.ConnectionHistory),
        )
        state.activateDefaultConnection("c1", "public")
        state.clearDefaultConnection()

        assertEquals("c3", state.activeConnectionId.value)
        assertEquals("explicit_schema", state.schemaSelection.value.schema)
        assertEquals(ActiveConnectionOrigin.Explicit, state.activeConnectionOrigin.value)
    }

    @Test
    fun clearingDeletedActiveConnectionResetsItsOriginAndSchema() {
        val state = AppState(unusedService())
        state.activateDefaultConnection("c1", "public")

        state.clearActiveConnectionIf("other")
        assertEquals("c1", state.activeConnectionId.value)

        state.clearActiveConnectionIf("c1")
        assertNull(state.activeConnectionId.value)
        assertEquals(SchemaSelectionIntent.Unselected, state.schemaSelection.value)
        assertNull(state.activeConnectionOrigin.value)
    }

    @Test
    fun defaultLocationRequiresBothFieldsAndAnExistingConnection() {
        val connections = listOf(connection("c1"))

        assertEquals(
            DefaultQueryLocation("c1", "public"),
            resolveDefaultQueryLocation(
                Settings(defaultConnectionId = "c1", defaultSchema = "public"),
                connections,
            ),
        )
        assertNull(resolveDefaultQueryLocation(Settings(defaultConnectionId = "missing", defaultSchema = "public"), connections))
        assertNull(resolveDefaultQueryLocation(Settings(defaultConnectionId = "c1"), connections))
    }

    @Test
    fun explicitConnectionSelectionUsesOnlyPerConnectionHistory() {
        val settings = Settings(
            defaultConnectionId = "c1",
            defaultSchema = "default_schema",
            lastSelectedSchemas = mapOf("c1" to "remembered_schema", "c2" to "analytics"),
        )

        assertEquals(
            SchemaSelectionIntent("remembered_schema", SchemaSelectionSource.ConnectionHistory),
            resolveConnectionSchemaSelection("c1", settings),
        )
        assertEquals(
            SchemaSelectionIntent("analytics", SchemaSelectionSource.ConnectionHistory),
            resolveConnectionSchemaSelection("c2", settings),
        )
        assertEquals(SchemaSelectionIntent.Unselected, resolveConnectionSchemaSelection("c3", settings))
    }

    @Test
    fun restoredQueryUsesFirstTableSchemaWithoutHistorySource() {
        val spec = QuerySpec(
            tables = listOf(
                TableRef("reporting", "orders", "t0"),
                TableRef("public", "customers", "t1"),
            ),
            filters = FilterGroup.empty(),
            limit = 100,
        )

        assertEquals(
            SchemaSelectionIntent("reporting", SchemaSelectionSource.RestoredQuery),
            resolveQuerySchemaSelection(spec),
        )
        assertEquals(
            SchemaSelectionIntent.Unselected,
            resolveQuerySchemaSelection(spec.copy(tables = emptyList())),
        )
    }

    private fun connection(id: String) = ConnectionDef(
        id = id,
        name = id,
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "database",
        username = "readonly",
    )

    private fun unusedService(): SafeDbService = Proxy.newProxyInstance(
        SafeDbService::class.java.classLoader,
        arrayOf(SafeDbService::class.java),
    ) { _, method, _ -> error("Unexpected service call: ${method.name}") } as SafeDbService
}
