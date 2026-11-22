package com.safedb.ui

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.ConnectionsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ConnectionsScreenTest {
    @Test
    fun clickingCardSelectsConnectionWhileEditAndDeleteDoNotTriggerSelection() {
        val alpha =
            ConnectionDef(
                id = "c1",
                name = "Alpha DB",
                dialect = Dialect.Postgres,
                host = "localhost",
                port = 5432,
                database = "alpha",
                username = "reader",
            )
        val beta =
            ConnectionDef(
                id = "c2",
                name = "Beta DB",
                dialect = Dialect.MySql,
                host = "localhost",
                port = 3306,
                database = "beta",
                username = "reader",
            )
        val service =
            object : FakeSafeDbServiceSupport() {
                override suspend fun listConnections(): List<ConnectionDef> = listOf(alpha, beta)
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = ConnectionsViewModel(service, scope)
            runBlocking { viewModel.load() }
            val selected = mutableListOf<ConnectionDef>()
            var editing: ConnectionDef? = null

            withComposeScene(
                width = 800,
                height = 600,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreenContent(
                            viewModel = viewModel,
                            showConnectionForm = false,
                            editingConnection = editing,
                            onShowConnectionFormChange = {},
                            onEditingConnectionChange = { editing = it },
                            onDeleted = {},
                            onConnectionChanged = {},
                            onSelectConnection = { selected += it },
                            activeConnectionId = alpha.id,
                        )
                    }
                },
            ) { scene ->
                // Clicking card content invokes onSelectConnection.
                scene.click(scene.requireContaining("Beta DB").boundsInRoot.center)
                scene.settle()
                assertEquals(listOf(beta), selected)
                assertNull(editing)

                // Clicking Edit icon opens edit without firing onSelectConnection.
                scene.click(scene.requireExact("Edit Alpha DB").boundsInRoot.center)
                scene.settle()
                assertEquals(alpha, editing)
                assertEquals(listOf(beta), selected)

                // Clicking Delete icon opens confirm dialog without firing onSelectConnection.
                editing = null
                scene.settle()
                val deleteButtons = scene.nodesLabeled("Delete")
                assertEquals(2, deleteButtons.size)
                scene.click(deleteButtons[0].boundsInRoot.center)
                scene.settle()
                assertNotNull(scene.findContaining("Delete connection \"Alpha DB\"?"))
                assertEquals(listOf(beta), selected)
            }
        } finally {
            scope.cancel()
        }
    }
}
