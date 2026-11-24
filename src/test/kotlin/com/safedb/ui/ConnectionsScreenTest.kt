package com.safedb.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.safedb.AppState
import com.safedb.ConnectionFormRequest
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.secrets.MissingPasswordReason
import com.safedb.secrets.PasswordNotFoundException
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
import kotlinx.coroutines.CompletableDeferred
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
            var formMode: ConnectionFormMode? = null

            withComposeScene(
                width = 800,
                height = 600,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreenContent(
                            viewModel = viewModel,
                            connections = listOf(alpha, beta),
                            loading = false,
                            formMode = formMode,
                            onFormModeChange = { formMode = it },
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
                assertNull(formMode)

                // Clicking Edit icon opens edit without firing onSelectConnection.
                val editButton = scene.requireExact("Edit Alpha DB")
                assertTrue(editButton.boundsInRoot.width >= 40f)
                assertTrue(editButton.boundsInRoot.height >= 40f)
                scene.click(editButton.boundsInRoot.center)
                scene.settle()
                assertEquals(ConnectionFormMode.Edit(alpha), formMode)
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
                            connectionFormRequest = ConnectionFormRequest.Edit(alpha.id),
                            onConnectionFormRequestConsumed = { consumed = true },
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
    fun editConnectionTestBannerSitsAboveFooterWithoutClippingAndMissingPasswordOpensEntry() {
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
        var testFailure: PasswordNotFoundException? =
            PasswordNotFoundException(
                "Password not found for this connection because credentials are held in " +
                    "memory only for this session. Edit the connection to re-enter the password.",
                reason = MissingPasswordReason.MemoryStore,
            )
        val service =
            object : FakeSafeDbServiceSupport() {
                override suspend fun listConnections(): List<ConnectionDef> = listOf(alpha)

                override suspend fun testConnection(def: ConnectionDef, password: String?): String {
                    if (password == null) {
                        testFailure?.let { throw it }
                    }
                    return "PostgreSQL 16.7 on x86_64-pc-linux-gnu"
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = ConnectionsViewModel(service, scope)
            runBlocking { viewModel.load() }

            withComposeScene(
                width = 1000,
                height = 760,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreen(
                            viewModel = viewModel,
                            onDeleted = {},
                            onConnectionChanged = {},
                            connectionFormRequest = ConnectionFormRequest.Edit(alpha.id),
                            onConnectionFormRequestConsumed = {},
                        )
                    }
                },
            ) { scene ->
                scene.settle()

                // Missing password in memory store switches directly into focused password entry.
                scene.click(scene.requireExact("Test Connection").boundsInRoot.center)
                scene.settle()

                val passwordField = scene.requireExact("Password")
                assertEquals(true, passwordField.config.getOrNull(SemanticsProperties.Focused))
                assertNull(scene.findExact("Keep saved password"))
                val missingBanner =
                    scene.requireContaining("Enter the password above to test or save.")
                assertNull(scene.findContaining("Edit the connection"))
                val testButton = scene.requireExact("Test Connection")
                assertTrue(missingBanner.boundsInRoot.bottom <= testButton.boundsInRoot.top - 12f)
                assertTrue(missingBanner.boundsInRoot.height >= 14f)

                // Re-entering password and testing shows the unclipped success banner above footer.
                passwordField.config[SemanticsActions.SetText]
                    .action!!
                    .invoke(AnnotatedString("pw"))
                scene.settle()
                scene.click(testButton.boundsInRoot.center)
                scene.settle()

                val successBanner = scene.requireContaining("Connected - PostgreSQL 16.7")
                val refreshedButton = scene.requireExact("Test Connection")
                assertTrue(
                    successBanner.boundsInRoot.bottom <= refreshedButton.boundsInRoot.top - 12f,
                )
                assertTrue(successBanner.boundsInRoot.height >= 14f)
            }

            // Disabled keychain backend override keeps "Saved password" and preserves env guidance.
            testFailure =
                PasswordNotFoundException(
                    "Password not found for this connection because SAFEDB_KEYCHAIN_BACKEND=disabled is overriding the persisted credential store.",
                    reason = MissingPasswordReason.DisabledOverride,
                )
            withComposeScene(
                width = 1000,
                height = 760,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreen(
                            viewModel = viewModel,
                            onDeleted = {},
                            onConnectionChanged = {},
                            connectionFormRequest = ConnectionFormRequest.Edit(alpha.id),
                            onConnectionFormRequestConsumed = {},
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                scene.click(scene.requireExact("Test Connection").boundsInRoot.center)
                scene.settle()

                assertNotNull(scene.findContaining("Saved password"))
                assertNotNull(scene.findContaining("SAFEDB_KEYCHAIN_BACKEND=disabled"))
            }

            // Keychain missing password outside memory/disabled modes also opens password entry.
            testFailure = PasswordNotFoundException("Password not found for this connection.")
            withComposeScene(
                width = 1000,
                height = 760,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreen(
                            viewModel = viewModel,
                            onDeleted = {},
                            onConnectionChanged = {},
                            connectionFormRequest = ConnectionFormRequest.Edit(alpha.id),
                            onConnectionFormRequestConsumed = {},
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                scene.click(scene.requireExact("Test Connection").boundsInRoot.center)
                scene.settle()

                assertNotNull(
                    scene.findContaining(
                        "Password not found for this connection. Enter the password above to test or save.",
                    ),
                )
                assertNull(scene.findExact("Keep saved password"))
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

    @Test
    fun switchingEditTargetCancelsPriorSaveCompletionFromClosingNewForm() {
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
        val alphaSaveGate = CompletableDeferred<Unit>()
        val service =
            object : FakeSafeDbServiceSupport() {
                override suspend fun listConnections(): List<ConnectionDef> = listOf(alpha, beta)

                override suspend fun updateConnection(def: ConnectionDef, password: String?) {
                    if (def.id == alpha.id) alphaSaveGate.await()
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = ConnectionsViewModel(service, scope)
            runBlocking { viewModel.load() }
            val formRequest =
                mutableStateOf<ConnectionFormRequest?>(ConnectionFormRequest.Edit(alpha.id))

            withComposeScene(
                width = 1000,
                height = 760,
                content = {
                    SafeDbTheme(isDark = false) {
                        ConnectionsScreen(
                            viewModel = viewModel,
                            onDeleted = {},
                            onConnectionChanged = {},
                            connectionFormRequest = formRequest.value,
                            onConnectionFormRequestConsumed = { formRequest.value = null },
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                assertNotNull(scene.findContaining("Alpha DB"))

                scene.click(scene.requireExact("Save Changes").boundsInRoot.center)
                scene.settle()

                formRequest.value = ConnectionFormRequest.Edit(beta.id)
                Snapshot.sendApplyNotifications()
                scene.settle()
                scene.settle()
                assertNotNull(scene.findContaining("Beta DB"))

                alphaSaveGate.complete(Unit)
                scene.settle()

                assertNotNull(scene.findExact("Edit Connection"))
                assertNotNull(scene.findContaining("Beta DB"))
                assertNull(scene.findContaining("Alpha DB"))
            }
        } finally {
            alphaSaveGate.complete(Unit)
            scope.cancel()
        }
    }
}
