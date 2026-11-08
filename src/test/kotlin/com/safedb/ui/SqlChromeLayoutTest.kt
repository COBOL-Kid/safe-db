package com.safedb.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.input.TextFieldValue
import com.safedb.APP_DEFAULT_WINDOW_HEIGHT_DP
import com.safedb.APP_DEFAULT_WINDOW_WIDTH_DP
import com.safedb.APP_MIN_WINDOW_HEIGHT_PX
import com.safedb.APP_MIN_WINDOW_WIDTH_PX
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryRunRequest
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.queryRunResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SqlChromeLayoutTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sqlHeaderRunStaysReadableAtTheMinimumWindow() {
        listOf(true, false).forEach { sidebarExpanded ->
            withSqlChrome(
                width = APP_MIN_WINDOW_WIDTH_PX,
                height = APP_MIN_WINDOW_HEIGHT_PX,
                sidebarExpanded = sidebarExpanded,
            ) { scene ->
                val run = scene.requireExact("Run")
                val connection = scene.requireExact("Local Postgres")
                val schema = scene.requireExact("public")
                assertTrue(
                    run.boundsInRoot.width >= 40f && run.boundsInRoot.height <= 48f,
                    "Run stacked at sidebarExpanded=$sidebarExpanded: ${run.boundsInRoot}",
                )
                assertTrue(
                    connection.boundsInRoot.width >= 90f && connection.boundsInRoot.height <= 48f,
                    "connection stacked at sidebarExpanded=$sidebarExpanded: ${connection.boundsInRoot}",
                )
                assertTrue(
                    schema.boundsInRoot.height <= 48f,
                    "schema stacked at sidebarExpanded=$sidebarExpanded: ${schema.boundsInRoot}",
                )
                assertNotNull(scene.findExact("SQL"))
                assertNotNull(scene.findContaining("safedb_gui_test"))
                assertTrue(
                    run.boundsInRoot.bottom < APP_MIN_WINDOW_HEIGHT_PX * 0.45f,
                    "header consumed the editor at sidebarExpanded=$sidebarExpanded: ${run.boundsInRoot}",
                )
            }
        }
    }

    @Test
    fun sqlHeaderRunStaysReadableAtDefaultWidth() {
        listOf(true, false).forEach { sidebarExpanded ->
            withSqlChrome(
                width = APP_DEFAULT_WINDOW_WIDTH_DP,
                height = APP_DEFAULT_WINDOW_HEIGHT_DP,
                sidebarExpanded = sidebarExpanded,
            ) { scene ->
                val run = scene.requireExact("Run")
                assertTrue(
                    run.boundsInRoot.width >= 40f && run.boundsInRoot.height <= 48f,
                    "Run stacked at default width sidebarExpanded=$sidebarExpanded: ${run.boundsInRoot}",
                )
                assertNotNull(scene.findExact("Local Postgres"))
                assertNotNull(scene.findExact("public"))
            }
        }
    }

    @Test
    fun runAtMinimumWidthStillExecutesASqlQuery() {
        var ran = false
        withSqlChrome(
            width = APP_MIN_WINDOW_WIDTH_PX,
            height = APP_MIN_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            service = SqlChromeService(onRun = { ran = true }),
        ) { scene ->
            scene.click(scene.requireExact("Run").boundsInRoot.center)
            scene.settle()
            assertTrue(ran, "Run did not execute the SQL query")
            assertNotNull(scene.findContaining("15 rows"))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun withSqlChrome(
    width: Int,
    height: Int,
    sidebarExpanded: Boolean,
    service: SqlChromeService = SqlChromeService(),
    body: (ImageComposeScene) -> Unit,
) {
    val viewModel = AppViewModel(service, Dispatchers.Unconfined)
    val appState = AppState()
    try {
        val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
        appState.setActiveConnection("c-pg", selection)
        appState.navigate(AppRoute.Sql)
        viewModel.schema.load("c-pg", selection = selection) { loaded ->
            if (loaded) {
                viewModel.sqlEditor.onTextChanged(
                    TextFieldValue("SELECT id, full_name FROM customers LIMIT 5")
                )
            }
        }
        withComposeScene(
            width = width,
            height = height,
            content = {
                SafeDbTheme(isDark = false) {
                    val activeConnectionId by appState.activeConnectionId.collectAsState()
                    val connections by viewModel.connections.connections.collectAsState()
                    AppShellContent(
                        appState = appState,
                        viewModel = viewModel,
                        paletteOpen = false,
                        onPaletteOpenChange = {},
                        sqlParseResult =
                            rememberSqlParseResult(
                                sqlText = viewModel.sqlEditor.text.text,
                                connection =
                                    connections.firstOrNull { it.id == activeConnectionId },
                                schemaViewModel = viewModel.schema,
                            ),
                        sidebarCollapsed = !sidebarExpanded,
                        onSidebarCollapsedChange = {},
                    )
                }
            },
            body = body,
        )
    } finally {
        viewModel.close()
    }
}

private class SqlChromeService(private val onRun: () -> Unit = {}) : FakeSafeDbServiceSupport() {
    override suspend fun listConnections() = listOf(localPostgres)

    override suspend fun getSchema(connectionId: String) = Schema(listOf(customersTable))

    override suspend fun runQuery(request: QueryRunRequest) =
        queryRunResult(
                QueryResult(
                    columns =
                        listOf(ResultColumn("id", "bigint"), ResultColumn("full_name", "varchar")),
                    rows =
                        listOf(
                            listOf(ResultCell.IntegerCell(1L), ResultCell.text("alice")),
                            listOf(ResultCell.IntegerCell(2L), ResultCell.text("bob")),
                        ),
                    rowCount = 15,
                    truncated = false,
                    warnings = emptyList(),
                )
            )
            .also { onRun() }
}

private val localPostgres =
    ConnectionDef(
        id = "c-pg",
        name = "Local Postgres",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "safedb_gui_test",
        username = "safedb",
    )

private val customersTable =
    TableInfo(
        schema = "public",
        name = "customers",
        columns =
            listOf(
                ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                ColumnInfo("full_name", "varchar", nullable = true),
            ),
        indexes = emptyList(),
    )
