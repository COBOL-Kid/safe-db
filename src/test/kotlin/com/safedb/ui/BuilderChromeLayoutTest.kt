package com.safedb.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.APP_DEFAULT_WINDOW_HEIGHT_PX
import com.safedb.APP_DEFAULT_WINDOW_WIDTH_PX
import com.safedb.APP_MIN_WINDOW_HEIGHT_PX
import com.safedb.APP_MIN_WINDOW_WIDTH_PX
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.LiteralKind
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
class BuilderChromeLayoutTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun queryBuilderToolbarStaysReadableAtTheMinimumWindow() {
        listOf(true, false).forEach { sidebarExpanded ->
            withBuilderChrome(
                width = APP_MIN_WINDOW_WIDTH_PX,
                height = APP_MIN_WINDOW_HEIGHT_PX,
                sidebarExpanded = sidebarExpanded,
                prepare = { _, viewModel ->
                    viewModel.query.addTable(customersTable)
                    viewModel.query.addTable(ordersTable)
                },
            ) { scene ->
                val name = scene.requireExact("Local MySQL")
                val run = scene.requireExact("Run")
                assertTrue(
                    name.boundsInRoot.width >= 90f,
                    "connection name stacked at sidebarExpanded=$sidebarExpanded: ${name.boundsInRoot}",
                )
                assertTrue(
                    name.boundsInRoot.height <= 32f,
                    "connection name wrapped vertically at sidebarExpanded=$sidebarExpanded: ${name.boundsInRoot}",
                )
                assertTrue(
                    run.boundsInRoot.width >= 40f && run.boundsInRoot.height <= 48f,
                    "Run stacked at sidebarExpanded=$sidebarExpanded: ${run.boundsInRoot}",
                )
                assertNotNull(scene.findExact("Recipes"))
                assertNotNull(scene.findExact("Save"))
                assertNotNull(scene.findExact("Clear"))
                assertNotNull(scene.findExact("Limit"))
                assertTrue(
                    name.boundsInRoot.bottom < APP_MIN_WINDOW_HEIGHT_PX * 0.45f,
                    "toolbar consumed the canvas at sidebarExpanded=$sidebarExpanded: ${name.boundsInRoot}",
                )
                val canvasTable = scene.nodesContaining("customers").firstOrNull()
                assertNotNull(
                    canvasTable,
                    "canvas table missing at sidebarExpanded=$sidebarExpanded",
                )
                assertTrue(
                    canvasTable.boundsInRoot.top < APP_MIN_WINDOW_HEIGHT_PX - 80f,
                    "canvas was pushed off-screen at sidebarExpanded=$sidebarExpanded: ${canvasTable.boundsInRoot}",
                )
            }
        }
    }

    @Test
    fun filterChipsStayCompleteAtDefaultWidthWithSidebarExpanded() {
        withBuilderChrome(
            width = APP_DEFAULT_WINDOW_WIDTH_PX,
            height = APP_DEFAULT_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            prepare = { _, viewModel -> addCustomerFilters(viewModel) },
        ) { scene ->
            assertFilterChromeReadable(scene, APP_DEFAULT_WINDOW_WIDTH_PX)
        }
    }

    @Test
    fun filterChipsStayInsideTheCanvasAtTheMinimumWindow() {
        withBuilderChrome(
            width = APP_MIN_WINDOW_WIDTH_PX,
            height = APP_MIN_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            prepare = { _, viewModel -> addCustomerFilters(viewModel) },
        ) { scene ->
            assertFilterChromeReadable(scene, APP_MIN_WINDOW_WIDTH_PX)
        }
    }

    @Test
    fun runAtMinimumWidthStillExecutesABuilderQuery() {
        var ran = false
        withBuilderChrome(
            width = APP_MIN_WINDOW_WIDTH_PX,
            height = APP_MIN_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            service = BuilderChromeService(onRun = { ran = true }),
            prepare = { _, viewModel -> viewModel.query.addTable(customersTable) },
        ) { scene ->
            scene.click(scene.requireExact("Run").boundsInRoot.center)
            scene.settle()
            assertTrue(ran, "Run did not execute the builder query")
            assertNotNull(scene.findContaining("15 rows"))
        }
    }
}

private fun addCustomerFilters(viewModel: AppViewModel) {
    viewModel.query.addTable(customersTable)
    viewModel.query.addFilter(
        FilterSpec(
            id = "f-in-names",
            tableAlias = "t0",
            column = "full_name",
            op = FilterOp.In,
            value =
                FilterValue.ListValue(
                    listOf(
                        FilterLiteral(LiteralKind.Text, "alice"),
                        FilterLiteral(LiteralKind.Text, "bob"),
                        FilterLiteral(LiteralKind.Text, "carol"),
                    )
                ),
        )
    )
    viewModel.query.addFilter(
        FilterSpec(
            id = "f-ts",
            tableAlias = "t0",
            column = "created_at",
            op = FilterOp.Gte,
            value = FilterValue.Single(FilterLiteral(LiteralKind.DateTime, "2025-07-03 09:12:00")),
        )
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun assertFilterChromeReadable(scene: ImageComposeScene, sceneWidth: Int) {
    val filters = scene.requireContaining("Query filters")
    assertTrue(filters.boundsInRoot.left >= 0f, "filters clipped: ${filters.boundsInRoot}")
    val customersInFilters =
        scene.nodesContaining("customers").filter { node ->
            node.boundsInRoot.left >= filters.boundsInRoot.left - 0.5f &&
                node.boundsInRoot.top >= filters.boundsInRoot.top - 0.5f &&
                node.boundsInRoot.bottom <= filters.boundsInRoot.bottom + 0.5f
        }
    assertTrue(
        customersInFilters.any { it.boundsInRoot.width >= 56f },
        "customers chip clipped: ${customersInFilters.map { it.boundsInRoot }}",
    )
    val alice = scene.requireContaining("Filter value 1 for full_name")
    val bob = scene.requireContaining("Filter value 2 for full_name")
    val carol = scene.requireContaining("Filter value 3 for full_name")
    for (value in listOf(alice, bob, carol)) {
        assertTrue(
            value.boundsInRoot.left >= filters.boundsInRoot.left - 0.5f,
            "IN-list clipped on the left: ${value.boundsInRoot} filters=${filters.boundsInRoot}",
        )
        assertTrue(
            value.boundsInRoot.right <= sceneWidth + 0.5f,
            "IN-list overflowed ${sceneWidth}px: ${value.boundsInRoot}",
        )
    }
    assertNotNull(scene.findContaining("alice"))
    assertNotNull(scene.findContaining("bob"))
    assertNotNull(scene.findContaining("carol"))
    val timestamp = scene.requireContaining("Filter value for created_at")
    assertNotNull(scene.findContaining("2025-07-03 09:12:00"))
    assertTrue(
        timestamp.boundsInRoot.width >= FILTER_SINGLE_VALUE_WIDTH_DP - 8,
        "timestamp field was ${timestamp.boundsInRoot.width}px",
    )
    val overflows =
        scene.allSemanticsNodes().filter {
            it.boundsInRoot.left < -0.5f || it.boundsInRoot.right > sceneWidth + 0.5f
        }
    assertTrue(
        overflows.isEmpty(),
        "nodes overflowed ${sceneWidth}px: ${overflows.map { "${it.label()} ${it.boundsInRoot}" }}",
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun withBuilderChrome(
    width: Int,
    height: Int,
    sidebarExpanded: Boolean,
    service: BuilderChromeService = BuilderChromeService(),
    prepare: (AppState, AppViewModel) -> Unit,
    body: (ImageComposeScene) -> Unit,
) {
    val viewModel = AppViewModel(service, Dispatchers.Unconfined)
    val appState = AppState()
    try {
        val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
        appState.setActiveConnection("c2", selection)
        appState.navigate(AppRoute.Builder)
        viewModel.schema.load("c2", selection = selection) { loaded ->
            if (loaded) prepare(appState, viewModel)
        }
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
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
            }
            .use { scene ->
                scene.render(0L)
                body(scene)
            }
    } finally {
        viewModel.close()
    }
}

private class BuilderChromeService(private val onRun: () -> Unit = {}) :
    FakeSafeDbServiceSupport() {
    override suspend fun listConnections() = listOf(localMysql)

    override suspend fun getSchema(connectionId: String) =
        Schema(listOf(customersTable, ordersTable))

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

private val localMysql =
    ConnectionDef(
        id = "c2",
        name = "Local MySQL",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "root",
    )

private val customersTable =
    TableInfo(
        schema = "public",
        name = "customers",
        columns =
            listOf(
                ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                ColumnInfo("full_name", "varchar", nullable = true),
                ColumnInfo("created_at", "timestamp", nullable = false),
            ),
        indexes = emptyList(),
    )

private val ordersTable =
    TableInfo(
        schema = "public",
        name = "orders",
        columns =
            listOf(
                ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                ColumnInfo("customer_id", "bigint", nullable = false, isIndexed = true),
            ),
        indexes = emptyList(),
    )
