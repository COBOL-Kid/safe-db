package com.safedb.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.safedb.AppState
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.components.CommandPalette
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.ConnectionsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsScreenTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
                val editButton = scene.requireExact("Edit Alpha DB")
                assertTrue(editButton.boundsInRoot.width >= 40f)
                assertTrue(editButton.boundsInRoot.height >= 40f)
                scene.click(editButton.boundsInRoot.center)
                scene.settle()
                assertEquals(alpha, editing)
                assertEquals(listOf(beta), selected)

                // Clicking Delete icon opens confirm dialog without firing onSelectConnection.
                val deleteButtons = scene.nodesLabeled("Delete")
                assertEquals(2, deleteButtons.size)
                assertTrue(deleteButtons[0].boundsInRoot.width >= 40f)
                assertTrue(deleteButtons[0].boundsInRoot.height >= 40f)
                scene.click(deleteButtons[0].boundsInRoot.center)
                scene.settle()
                assertNotNull(scene.findContaining("Delete connection \"Alpha DB\"?"))
                assertEquals(listOf(beta), selected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun editConnectionRequestOpensEditFormAndChangeFocusesPasswordField() {
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
        val service =
            object : FakeSafeDbServiceSupport() {
                override suspend fun listConnections(): List<ConnectionDef> = listOf(alpha)
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = ConnectionsViewModel(service, scope)
            runBlocking { viewModel.load() }
            var consumed = false

            withComposeScene(
                width = 1000,
                height = 800,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreen(
                            viewModel = viewModel,
                            onDeleted = {},
                            onConnectionChanged = {},
                            editConnectionId = alpha.id,
                            onEditConnectionConsumed = { consumed = true },
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                assertTrue(consumed)
                assertNotNull(scene.findExact("Edit Connection"))
                assertNotNull(scene.findContaining("Saved password"))

                scene.click(scene.requireExact("Change").boundsInRoot.center)
                scene.settle()

                assertNotNull(scene.findExact("Keep saved password"))
                val passwordField = scene.requireExact("Password")
                assertEquals(true, passwordField.config.getOrNull(SemanticsProperties.Focused))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commandPaletteListsEditConnectionAndDispatchesWithoutSelecting() {
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
        val appState = AppState()
        val viewModel = AppViewModel(service, Dispatchers.Unconfined)
        try {
            runBlocking { viewModel.connections.load() }
            val selected = mutableListOf<ConnectionDef>()
            val edited = mutableListOf<ConnectionDef>()
            var dismissed = false

            withComposeScene(
                width = 1000,
                height = 800,
                content = {
                    SafeDbTheme(isDark = false) {
                        CommandPalette(
                            open = true,
                            onDismiss = { dismissed = true },
                            appState = appState,
                            viewModel = viewModel,
                            onConnectionSelected = { selected += it },
                            onEditConnection = { edited += it },
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                val searchField = scene.requireContaining("Type a command...")
                searchField.config[SemanticsActions.SetText]
                    .action!!
                    .invoke(AnnotatedString("Alpha"))
                scene.settle()

                val labels = scene.allSemanticsNodes().map { it.label() }
                val exploreIdx = labels.indexOfFirst { it.contains("Explore: Alpha DB") }
                val editIdx = labels.indexOfFirst { it.contains("Edit connection: Alpha DB") }
                assertTrue(exploreIdx >= 0)
                assertTrue(editIdx > exploreIdx)

                scene.click(
                    scene.requireContaining("Edit connection: Alpha DB").boundsInRoot.center,
                )
                scene.settle()
                assertEquals(listOf(alpha), edited)
                assertTrue(dismissed)
                assertTrue(selected.isEmpty())
            }
        } finally {
            viewModel.close()
        }
    }
}
