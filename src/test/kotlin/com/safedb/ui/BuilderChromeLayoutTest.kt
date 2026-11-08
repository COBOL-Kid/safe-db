package com.safedb.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
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
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryRunRequest
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.queryRunResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
            width = APP_DEFAULT_WINDOW_WIDTH_DP,
            height = APP_DEFAULT_WINDOW_HEIGHT_DP,
            sidebarExpanded = true,
            prepare = { _, viewModel -> addCustomerFilters(viewModel) },
        ) { scene ->
            assertFilterChromeReadable(scene, APP_DEFAULT_WINDOW_WIDTH_DP)
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
    fun betweenTimestampFiltersStayReadableAtTheMinimumWindow() {
        listOf(
                APP_MIN_WINDOW_WIDTH_PX to APP_MIN_WINDOW_HEIGHT_PX,
                APP_DEFAULT_WINDOW_WIDTH_DP to APP_DEFAULT_WINDOW_HEIGHT_DP,
            )
            .forEach { (width, height) ->
                listOf(true, false).forEach { sidebarExpanded ->
                    withBuilderChrome(
                        width = width,
                        height = height,
                        sidebarExpanded = sidebarExpanded,
                        prepare = { _, viewModel ->
                            viewModel.query.addTable(customersTable)
                            viewModel.query.addFilter(
                                FilterSpec(
                                    id = "f-between",
                                    tableAlias = "t0",
                                    column = "created_at",
                                    op = FilterOp.Between,
                                    value =
                                        FilterValue.Pair(
                                            FilterLiteral(
                                                LiteralKind.DateTime,
                                                "2025-07-01 00:00:00",
                                            ),
                                            FilterLiteral(
                                                LiteralKind.DateTime,
                                                "2025-07-31 23:59:59",
                                            ),
                                        ),
                                )
                            )
                        },
                    ) { scene ->
                        val from = scene.requireContaining("Filter value from created_at")
                        val to = scene.requireContaining("Filter value to created_at")
                        val and = scene.requireExact("and")
                        assertNotNull(scene.findContaining("2025-07-01 00:00:00"))
                        assertNotNull(scene.findContaining("2025-07-31 23:59:59"))
                        assertTrue(
                            from.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
                            "from field was ${from.boundsInRoot.width}px at ${width}x${height} " +
                                "sidebarExpanded=$sidebarExpanded",
                        )
                        assertTrue(
                            to.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
                            "to field was ${to.boundsInRoot.width}px at ${width}x${height} " +
                                "sidebarExpanded=$sidebarExpanded",
                        )
                        assertBetweenRelationship(
                            from,
                            and,
                            to,
                            detail = "at ${width}x${height} sidebarExpanded=$sidebarExpanded",
                        )
                        scene.assertNoHorizontalOverflow(width)
                    }
                }
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

    @Test
    fun queryOptionsStayReachableAtTheMinimumWindow() {
        withBuilderChrome(
            width = APP_MIN_WINDOW_WIDTH_PX,
            height = APP_MIN_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            prepare = { _, viewModel ->
                viewModel.query.addTable(customersTable)
                viewModel.query.addTable(ordersTable)
                viewModel.query.addJoin(JoinSpec("t0", "id", "t1", "customer_id"))
                viewModel.query.addFilter(
                    FilterSpec(
                        id = "f-name",
                        tableAlias = "t0",
                        column = "full_name",
                        op = FilterOp.Eq,
                        value = FilterValue.Single(FilterLiteral(LiteralKind.Text, "alice")),
                    )
                )
                viewModel.query.setSorts(listOf(SortSpec("t0", "full_name", SortDirection.Asc)))
            },
        ) { scene ->
            val options = scene.requireByTag("builder-query-options")
            assertTrue(
                options.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                "Query options button was not reachable: ${options.boundsInRoot}",
            )
            val filters = scene.requireContaining("Query filters")
            assertTrue(
                filters.boundsInRoot.height > 0f,
                "Query filters were clipped away: ${filters.boundsInRoot}",
            )
            assertTrue(
                filters.boundsInRoot.bottom <= options.boundsInRoot.top + 0.5f,
                "Query options ${options.boundsInRoot} was inside the scrolling filters ${filters.boundsInRoot}",
            )

            scene.click(options.boundsInRoot.center)
            scene.settle()
            val distinct = scene.requireContaining("Distinct rows")
            assertTrue(
                distinct.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                "Query options dialog was not reachable: ${distinct.boundsInRoot}",
            )
            assertNotNull(scene.findContaining("join: customers.id = orders.customer_id"))

            val canvasTable = scene.requireExact("Move customers table")
            assertTrue(
                canvasTable.boundsInRoot.bottom > 0f &&
                    canvasTable.boundsInRoot.top < APP_MIN_WINDOW_HEIGHT_PX,
                "canvas table was pushed off-screen: ${canvasTable.boundsInRoot}",
            )
        }
    }

    @Test
    fun stackedBetweenAndInFiltersDoNotOverlapCanvasTables() {
        listOf(
                APP_MIN_WINDOW_WIDTH_PX to APP_MIN_WINDOW_HEIGHT_PX,
                APP_DEFAULT_WINDOW_WIDTH_DP to APP_DEFAULT_WINDOW_HEIGHT_DP,
            )
            .forEach { (width, height) ->
                listOf(true, false).forEach { sidebarExpanded ->
                    withBuilderChrome(
                        width = width,
                        height = height,
                        sidebarExpanded = sidebarExpanded,
                        prepare = { _, viewModel -> addBetweenAndInFilters(viewModel) },
                    ) { scene ->
                        assertStackedFiltersUsable(
                            scene,
                            width = width,
                            height = height,
                            sidebarExpanded = sidebarExpanded,
                        )
                    }
                }
            }
    }

    @Test
    fun betweenOperatorLabelFitsAtDefaultAndMinimumWidth() {
        listOf(
                APP_MIN_WINDOW_WIDTH_PX to APP_MIN_WINDOW_HEIGHT_PX,
                APP_DEFAULT_WINDOW_WIDTH_DP to APP_DEFAULT_WINDOW_HEIGHT_DP,
            )
            .forEach { (width, height) ->
                listOf(true, false).forEach { sidebarExpanded ->
                    withBuilderChrome(
                        width = width,
                        height = height,
                        sidebarExpanded = sidebarExpanded,
                        prepare = { _, viewModel -> addBetweenAndInFilters(viewModel) },
                    ) { scene ->
                        val between = scene.requireContaining("BETWEEN")
                        assertTrue(
                            between.boundsInRoot.width >= BETWEEN_OP_LABEL_MIN_WIDTH_PX,
                            "BETWEEN was truncated to ${between.boundsInRoot.width}px at ${width}x${height} " +
                                "sidebarExpanded=$sidebarExpanded",
                        )
                    }
                }
            }
    }

    @Test
    fun stackedFiltersStayReachableAfterResultsOpen() {
        listOf(true, false).forEach { sidebarExpanded ->
            withBuilderChrome(
                width = APP_MIN_WINDOW_WIDTH_PX,
                height = APP_MIN_WINDOW_HEIGHT_PX,
                sidebarExpanded = sidebarExpanded,
                prepare = { _, viewModel ->
                    addBetweenAndInFilters(viewModel)
                    viewModel.query.run("c2")
                },
            ) { scene ->
                scene.settle()
                assertNotNull(scene.findContaining("15 rows"))
                val detail = "after results at 960x600 sidebarExpanded=$sidebarExpanded"
                val filters = scene.requireByTag("builder-filter-panel")
                val options = scene.requireByTag("builder-query-options")
                val orders = scene.requireExact("Move orders table")
                assertTrue(
                    filters.boundsInRoot.height >= BuilderFilterPanelMinHeight.value,
                    "filters collapsed $detail: ${filters.boundsInRoot}",
                )
                assertTrue(
                    options.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                    "Query options was not reachable $detail: ${options.boundsInRoot}",
                )
                assertTrue(
                    filters.boundsInRoot.bottom <= options.boundsInRoot.top + 0.5f,
                    "Query options was inside the scrolling filters $detail: " +
                        "options=${options.boundsInRoot} filters=${filters.boundsInRoot}",
                )
                assertFalse(
                    rectsOverlap(filters.boundsInRoot, orders.boundsInRoot),
                    "filters overlapped orders $detail: filters=${filters.boundsInRoot} " +
                        "orders=${orders.boundsInRoot}",
                )
                scene.click(options.boundsInRoot.center)
                scene.settle()
                val distinct = scene.requireExact("Distinct rows")
                assertTrue(
                    distinct.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                    "Query options dialog was not reachable $detail: ${distinct.boundsInRoot}",
                )
            }
        }
    }

    @Test
    fun queryOptionsDialogAndInRemoveStayOperableAtTheMinimumWindow() {
        withBuilderChrome(
            width = APP_MIN_WINDOW_WIDTH_PX,
            height = APP_MIN_WINDOW_HEIGHT_PX,
            sidebarExpanded = true,
            prepare = { _, viewModel -> addBetweenAndInFilters(viewModel) },
        ) { scene ->
            val options = scene.requireByTag("builder-query-options")
            assertTrue(
                options.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                "Query options button was not reachable: ${options.boundsInRoot}",
            )
            scene.click(options.boundsInRoot.center)
            scene.settle()
            val distinct = scene.requireExact("Distinct rows")
            assertTrue(
                distinct.isInsideWindow(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX),
                "Distinct rows was not reachable: ${distinct.boundsInRoot}",
            )
            scene.click(distinct.boundsInRoot.center)
            scene.settle()
            assertNotNull(scene.findContaining("Distinct rows: on"))
            scene.click(scene.requireExact("Close").boundsInRoot.center)
            scene.settle()

            val panel = scene.requireByTag("builder-filter-panel")
            val lastRemove = scene.requireContaining("Remove value 6 for status")
            var attempts = 0
            while (!lastRemove.isVisibleInside(panel) && attempts < 24) {
                scene.scrollTagged("builder-filter-panel", 40f, times = 1)
                attempts++
            }
            assertTrue(
                lastRemove.isVisibleInside(panel),
                "IN remove was not reachable after scrolling: ${lastRemove.boundsInRoot} panel=${panel.boundsInRoot}",
            )
            scene.click(lastRemove.visibleBoundsIn(panel).center)
            scene.settle()
            assertTrue(
                scene.findContaining("delivered") == null,
                "removing the last IN value did not take effect",
            )
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
    // "2025-07-03 09:12:00" at 12sp monospace needs ~168px after the field's inner padding.
    assertTrue(
        timestamp.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
        "timestamp field was ${timestamp.boundsInRoot.width}px",
    )
    scene.assertNoHorizontalOverflow(sceneWidth)
}

private fun addBetweenAndInFilters(viewModel: AppViewModel) {
    viewModel.query.addTable(customersTable)
    viewModel.query.addTable(ordersTable)
    viewModel.query.addFilter(
        FilterSpec(
            id = "f-between",
            tableAlias = "t1",
            column = "order_date",
            op = FilterOp.Between,
            value =
                FilterValue.Pair(
                    FilterLiteral(LiteralKind.DateTime, "2025-07-01 00:00:00"),
                    FilterLiteral(LiteralKind.DateTime, "2025-07-31 23:59:59"),
                ),
        )
    )
    viewModel.query.addFilter(
        FilterSpec(
            id = "f-in-status",
            tableAlias = "t1",
            column = "status",
            op = FilterOp.In,
            value =
                FilterValue.ListValue(
                    listOf(
                        FilterLiteral(LiteralKind.Text, "pending"),
                        FilterLiteral(LiteralKind.Text, "shipped"),
                        FilterLiteral(LiteralKind.Text, "returned"),
                        FilterLiteral(LiteralKind.Text, "cancelled"),
                        FilterLiteral(LiteralKind.Text, "held"),
                        FilterLiteral(LiteralKind.Text, "delivered"),
                    )
                ),
        )
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun assertStackedFiltersUsable(
    scene: ImageComposeScene,
    width: Int,
    height: Int,
    sidebarExpanded: Boolean,
) {
    val detail = "at ${width}x${height} sidebarExpanded=$sidebarExpanded"
    val filters = scene.requireContaining("Query filters")
    val options = scene.requireByTag("builder-query-options")
    val orders = scene.requireExact("Move orders table")
    val customers = scene.requireExact("Move customers table")
    val chrome = scene.requireByTag("builder-query-chrome")

    assertFalse(
        rectsOverlap(filters.boundsInRoot, orders.boundsInRoot),
        "filters overlapped orders $detail: filters=${filters.boundsInRoot} orders=${orders.boundsInRoot}",
    )
    assertFalse(
        rectsOverlap(filters.boundsInRoot, customers.boundsInRoot),
        "filters overlapped customers $detail: filters=${filters.boundsInRoot} customers=${customers.boundsInRoot}",
    )
    assertFalse(
        rectsOverlap(chrome.boundsInRoot, orders.boundsInRoot),
        "query chrome overlapped orders $detail: chrome=${chrome.boundsInRoot} orders=${orders.boundsInRoot}",
    )
    assertTrue(
        options.isInsideWindow(width, height),
        "Query options was not reachable $detail: ${options.boundsInRoot}",
    )
    assertTrue(
        filters.boundsInRoot.bottom <= options.boundsInRoot.top + 0.5f,
        "Query options was inside the scrolling filters $detail: options=${options.boundsInRoot} filters=${filters.boundsInRoot}",
    )
    assertTrue(
        orders.boundsInRoot.bottom > 0f && orders.boundsInRoot.top < height - 40f,
        "orders table was not usable $detail: ${orders.boundsInRoot}",
    )
    assertNotNull(scene.findContaining("2025-07-01 00:00:00"))
    assertNotNull(scene.findContaining("2025-07-31 23:59:59"))
    val from = scene.requireContaining("Filter value from order_date")
    val to = scene.requireContaining("Filter value to order_date")
    assertTrue(
        from.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
        "from field was ${from.boundsInRoot.width}px $detail",
    )
    assertTrue(
        to.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
        "to field was ${to.boundsInRoot.width}px $detail",
    )
    val between = scene.requireContaining("BETWEEN")
    assertTrue(
        between.boundsInRoot.width >= BETWEEN_OP_LABEL_MIN_WIDTH_PX,
        "BETWEEN was truncated to ${between.boundsInRoot.width}px $detail",
    )
    scene.assertNoHorizontalOverflow(width)
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
                ColumnInfo("order_date", "timestamp", nullable = true),
                ColumnInfo("status", "text", nullable = true),
            ),
        indexes = emptyList(),
    )
