package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.safedb.canvas.CanvasPoint
import com.safedb.canvas.CanvasTableLike
import com.safedb.canvas.routeJoinEdge
import com.safedb.canvas.tableBounds
import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class CanvasInteractionTest {
    @Test
    fun horizontalScrollbarDragWinsOverUnderlyingSuggestedJoin() {
        val viewModel = suggestedJoinViewModel()
        viewModel.moveTable("t0", 0f, 360f)
        viewModel.moveTable("t1", 360f, 360f)

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.drag(Offset(250f, 431f), Offset(330f, 431f))
                scene.render(100_000_000L)
            }

        assertTrue(viewModel.canvasViewport.pan.x < 0f)
        assertEquals(0f, viewModel.canvasViewport.pan.y)
        assertTrue(viewModel.joins.isEmpty())
    }

    @Test
    fun verticalScrollbarDragWinsOverUnderlyingSuggestedJoin() {
        val viewModel = suggestedJoinViewModel()
        viewModel.moveTable("t0", 540f, 0f)
        viewModel.moveTable("t1", 540f, 300f)

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.drag(Offset(796f, 80f), Offset(796f, 160f))
                scene.render(100_000_000L)
            }

        assertEquals(0f, viewModel.canvasViewport.pan.x)
        assertTrue(viewModel.canvasViewport.pan.y < 0f)
        assertTrue(viewModel.joins.isEmpty())
    }

    @Test
    fun clickingIndexedColumnsCreatesAJoin() {
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(indexedTable("orders", "customer_id"))
        viewModel.addTable(indexedTable("customers", "id"))
        viewModel.moveTable("t1", 360f, 0f)

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.click(246f, 71f)
                scene.render(100_000_000L)
                scene.click(450f, 71f)
                scene.render(200_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
        assertEquals("customer_id", viewModel.joins.single().leftColumn)
        assertEquals("id", viewModel.joins.single().rightColumn)
    }

    @Test
    fun clickingIndexedColumnsCreatesAJoinWhenZoomed() {
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(indexedTable("orders", "customer_id"))
        viewModel.addTable(indexedTable("customers", "id"))
        viewModel.moveTable("t1", 360f, 0f)
        viewModel.canvasViewport.setZoom(0.5f)

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.click(123f, 35.5f)
                scene.render(100_000_000L)
                scene.click(283f, 35.5f)
                scene.render(200_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
    }

    @Test
    fun clickingSuggestedRelationshipCreatesAJoin() {
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(
            indexedTable(
                "orders",
                "customer_id",
                referencedTable = "customers",
                referencedColumn = "id",
            )
        )
        viewModel.addTable(indexedTable("customers", "id"))
        viewModel.moveTable("t1", 360f, 0f)
        val tables =
            viewModel.canvasTables.map { table ->
                CanvasTableLike(
                    alias = table.alias,
                    x = table.x,
                    y = table.y,
                    width = table.width,
                    height = table.height,
                    tableInfo = table.tableInfo,
                )
            }
        val edge =
            checkNotNull(
                routeJoinEdge(
                    tables[0],
                    "customer_id",
                    tables[1],
                    "id",
                    allTables = tables,
                )
            )
        val segment =
            edge.points
                .zipWithNext()
                .filter { (start, end) ->
                    val midpoint =
                        CanvasPoint(
                            (start.x + end.x) / 2f,
                            (start.y + end.y) / 2f,
                        )
                    midpoint.x >= 0f &&
                        midpoint.y >= 0f &&
                        tables.none { tableBounds(it).contains(midpoint) }
                }
                .maxBy { (start, end) ->
                    (end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y)
                }
        val click =
            Offset(
                (segment.first.x + segment.second.x) / 2f,
                (segment.first.y + segment.second.y) / 2f,
            )

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.clickWithMotion(click.x, click.y)
                scene.render(100_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
    }

    @Test
    fun clickingSuggestedRelationshipCreatesAJoinAtRetinaDensityWithBuilderInset() {
        val density = Density(2f)
        val inset = 96.dp
        val insetPx = with(density) { inset.toPx() }
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(
            indexedTable(
                "order_items",
                "order_id",
                referencedTable = "orders",
                referencedColumn = "id",
            )
        )
        viewModel.addTable(indexedTable("orders", "id"))
        viewModel.moveTable("t0", 15f, 5f)
        viewModel.moveTable("t1", 763f, -74f)
        val tables =
            viewModel.canvasTables.map { table ->
                CanvasTableLike(
                    alias = table.alias,
                    x = table.x,
                    y = table.y,
                    width = with(density) { table.width.dp.toPx() },
                    height = with(density) { table.height.dp.toPx() },
                    layoutScale = density.density,
                    tableInfo = table.tableInfo,
                )
            }
        val edge =
            checkNotNull(
                routeJoinEdge(
                    tables[0],
                    "order_id",
                    tables[1],
                    "id",
                    allTables = tables,
                )
            )
        val segment =
            edge.points
                .zipWithNext()
                .filter { (start, end) ->
                    val midpoint = CanvasPoint((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    midpoint.x in 0f..1324f &&
                        midpoint.y + insetPx in 0f..760f &&
                        tables.none { tableBounds(it).contains(midpoint) }
                }
                .maxBy { (start, end) ->
                    (end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y)
                }
        val click =
            Offset(
                (segment.first.x + segment.second.x) / 2f,
                (segment.first.y + segment.second.y) / 2f + insetPx,
            )

        ImageComposeScene(width = 1324, height = 760, density = density) {
                SafeDbTheme(isDark = false) { Canvas(viewModel, contentTopInset = inset) }
            }
            .use { scene ->
                scene.render(0L)
                scene.clickWithMotion(click.x, click.y)
                scene.render(100_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
    }

    @Test
    fun clickingSuggestedRelationshipCreatesAJoinAfterTableMovesPostComposition() {
        // Regression: the click handler must not hit-test through closures captured at first
        // composition. Moving a table after the canvas has composed re-routes the suggested
        // line; clicking the new position must still create the join.
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(
            indexedTable(
                "orders",
                "customer_id",
                referencedTable = "customers",
                referencedColumn = "id",
            )
        )
        viewModel.addTable(indexedTable("customers", "id"))

        ImageComposeScene(width = 900, height = 600, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                // Tables start overlapped (default cascade); drag-apart happens after the
                // canvas is already composed, exactly like a real user arranging tables.
                viewModel.moveTable("t1", 420f, 60f)
                scene.render(100_000_000L)

                val tables =
                    viewModel.canvasTables.map { table ->
                        CanvasTableLike(
                            alias = table.alias,
                            x = table.x,
                            y = table.y,
                            width = table.width,
                            height = table.height,
                            tableInfo = table.tableInfo,
                        )
                    }
                val edge =
                    checkNotNull(
                        routeJoinEdge(
                            tables[0],
                            "customer_id",
                            tables[1],
                            "id",
                            allTables = tables,
                        )
                    )
                val segment =
                    edge.points
                        .zipWithNext()
                        .filter { (start, end) ->
                            val midpoint =
                                CanvasPoint((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                            midpoint.x >= 0f &&
                                midpoint.y >= 0f &&
                                tables.none { tableBounds(it).contains(midpoint) }
                        }
                        .maxBy { (start, end) ->
                            (end.x - start.x) * (end.x - start.x) +
                                (end.y - start.y) * (end.y - start.y)
                        }
                val click =
                    Offset(
                        (segment.first.x + segment.second.x) / 2f,
                        (segment.first.y + segment.second.y) / 2f,
                    )
                scene.clickWithMotion(click.x, click.y)
                scene.render(200_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals("customer_id", viewModel.joins.single().leftColumn)
        assertEquals("id", viewModel.joins.single().rightColumn)
    }

    @Test
    fun clickingSuggestedRelationshipStillCreatesAJoinWhenPointerDriftsOffTheLine() {
        val viewModel =
            QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined))
        viewModel.addTable(
            indexedTable(
                "orders",
                "customer_id",
                referencedTable = "customers",
                referencedColumn = "id",
            )
        )
        viewModel.addTable(indexedTable("customers", "id"))
        viewModel.moveTable("t1", 360f, 0f)
        val tables =
            viewModel.canvasTables.map { table ->
                CanvasTableLike(
                    alias = table.alias,
                    x = table.x,
                    y = table.y,
                    width = table.width,
                    height = table.height,
                    tableInfo = table.tableInfo,
                )
            }
        val edge =
            checkNotNull(
                routeJoinEdge(
                    tables[0],
                    "customer_id",
                    tables[1],
                    "id",
                    allTables = tables,
                )
            )
        val segment =
            edge.points
                .zipWithNext()
                .filter { (start, end) ->
                    val midpoint =
                        CanvasPoint(
                            (start.x + end.x) / 2f,
                            (start.y + end.y) / 2f,
                        )
                    midpoint.x >= 0f &&
                        midpoint.y >= 0f &&
                        tables.none { tableBounds(it).contains(midpoint) }
                }
                .maxBy { (start, end) ->
                    (end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y)
                }
        val down =
            Offset(
                (segment.first.x + segment.second.x) / 2f,
                (segment.first.y + segment.second.y) / 2f,
            )
        // Drift far outside join-line tolerance before release.
        val up = Offset(down.x, down.y + 80f)

        ImageComposeScene(width = 800, height = 500, density = Density(1f)) {
                SafeDbTheme(isDark = false) { Canvas(viewModel) }
            }
            .use { scene ->
                scene.render(0L)
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    down,
                    button = PointerButton.Primary,
                )
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    up,
                    buttons = PointerButtons(isPrimaryPressed = true),
                )
                scene.sendPointerEvent(PointerEventType.Release, up, button = PointerButton.Primary)
                scene.render(100_000_000L)
            }

        assertEquals(1, viewModel.joins.size)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
    }
}

private fun suggestedJoinViewModel(): QueryViewModel =
    QueryViewModel(CanvasInteractionService, CoroutineScope(Dispatchers.Unconfined)).apply {
        addTable(
            indexedTable(
                "orders",
                "customer_id",
                referencedTable = "customers",
                referencedColumn = "id",
            )
        )
        addTable(indexedTable("customers", "id"))
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.click(x: Float, y: Float) {
    val position = Offset(x, y)
    sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
    sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.clickWithMotion(x: Float, y: Float) {
    val down = Offset(x, y)
    val up = Offset(x + 1f, y)
    sendPointerEvent(PointerEventType.Press, down, button = PointerButton.Primary)
    sendPointerEvent(
        PointerEventType.Move,
        up,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    sendPointerEvent(PointerEventType.Release, up, button = PointerButton.Primary)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.drag(from: Offset, to: Offset) {
    sendPointerEvent(PointerEventType.Move, from)
    render(5_000_000L)
    sendPointerEvent(
        PointerEventType.Press,
        from,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    render(10_000_000L)
    val midpoint = (from + to) / 2f
    sendPointerEvent(
        PointerEventType.Move,
        midpoint,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    render(20_000_000L)
    sendPointerEvent(
        PointerEventType.Move,
        to,
        buttons = PointerButtons(isPrimaryPressed = true),
    )
    render(30_000_000L)
    sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
}

private fun indexedTable(
    name: String,
    column: String,
    referencedTable: String? = null,
    referencedColumn: String? = null,
): TableInfo =
    TableInfo(
        schema = "public",
        name = name,
        columns =
            listOf(
                ColumnInfo(
                    name = column,
                    dataType = "bigint",
                    nullable = false,
                    isIndexed = true,
                    joinEligible = true,
                )
            ),
        indexes = emptyList(),
        foreignKeys =
            if (referencedTable != null && referencedColumn != null) {
                listOf(
                    ForeignKeyInfo(
                        name = "${name}_${column}_fkey",
                        columns = listOf(column),
                        referencedSchema = "public",
                        referencedTable = referencedTable,
                        referencedColumns = listOf(referencedColumn),
                    )
                )
            } else {
                emptyList()
            },
    )

private object CanvasInteractionService : FakeSafeDbServiceSupport() {
    override suspend fun getSchema(connectionId: String) = Schema(emptyList())
}
