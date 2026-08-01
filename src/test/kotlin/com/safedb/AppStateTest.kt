package com.safedb

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.Settings
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
        assertEquals("public", state.preferredSchema.value)
        assertEquals(ActiveConnectionOrigin.Default, state.activeConnectionOrigin.value)

        state.activateDefaultConnection("c2", "reporting")
        assertEquals("c2", state.activeConnectionId.value)
        assertEquals("reporting", state.preferredSchema.value)

        state.setActiveConnection("c3", preferredSchema = "explicit_schema")
        state.activateDefaultConnection("c1", "public")
        state.clearDefaultConnection()

        assertEquals("c3", state.activeConnectionId.value)
        assertEquals("explicit_schema", state.preferredSchema.value)
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
        assertNull(state.preferredSchema.value)
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
