package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.canvas.CanvasPoint
import com.safedb.canvas.CanvasTableLike
import com.safedb.canvas.routeJoinEdge
import com.safedb.canvas.tableBounds
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.JoinSpec
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class BuilderScreenJoinClickTest {

    // BuilderScreen lays out a 288.dp sidebar plus a 1.dp divider left of the canvas viewport.
    private val canvasViewportLeftPx = 289f

    @Test
    fun clickingDashedJoinLineThroughFullBuilderChromeCreatesJoin() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val queryViewModel = QueryViewModel(BuilderJoinClickService, scope)
        val schemaViewModel = SchemaViewModel(BuilderJoinClickService, scope)
        val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
        schemaViewModel.load("c1", selection = selection)

        queryViewModel.addTable(ordersTable)
        queryViewModel.addTable(customersTable)
        queryViewModel.moveTable("t1", 480f, 40f)

        val tables =
            queryViewModel.canvasTables.map { table ->
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
                    val midpoint = CanvasPoint((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    midpoint.x >= 0f &&
                        midpoint.y >= 0f &&
                        tables.none { tableBounds(it).contains(midpoint) }
                }
                .maxBy { (start, end) ->
                    (end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y)
                }
        val worldMidpoint =
            Offset(
                (segment.first.x + segment.second.x) / 2f,
                (segment.first.y + segment.second.y) / 2f,
            )

        ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    BuilderScreen(
                        connection = connectionDef,
                        connections = listOf(connectionDef),
                        queryViewModel = queryViewModel,
                        savedQueriesViewModel =
                            SavedQueriesViewModel(BuilderJoinClickService, scope),
                        recipesViewModel = RecipesViewModel(BuilderJoinClickService, scope),
                        schemaViewModel = schemaViewModel,
                        schemaSelection = selection,
                        schemaHistoryError = null,
                        settings = Settings(),
                        onConnectionSelected = {},
                        onSchemaSelected = {},
                        onUnavailableSchemaSelection = {},
                        onDismissSchemaHistoryError = {},
                        onOpenExplore = {},
                        onOpenSettings = {},
                        onApplyRecipe = { _, _ -> },
                    )
                }
            }
            .use { scene ->
                scene.render(0L)
                // With zoom 1 and no pan, world x maps directly to viewport x. The vertical
                // position of world y=0 is the Builder top bar height plus the measured
                // query-controls inset, which is not observable from outside the composition,
                // so probe downward from the world y. Each miss lands on empty canvas (the
                // probe x sits in the gap between tables) and is a no-op; the hit tolerance
                // is 10px so a 6px step cannot skip over the line.
                val clickX = canvasViewportLeftPx + worldMidpoint.x
                var renderTime = 0L
                var insetOffset = 0f
                while (queryViewModel.joins.isEmpty() && insetOffset <= 400f) {
                    val position = Offset(clickX, worldMidpoint.y + insetOffset)
                    scene.sendPointerEvent(
                        PointerEventType.Press,
                        position,
                        button = PointerButton.Primary,
                    )
                    scene.sendPointerEvent(
                        PointerEventType.Release,
                        position,
                        button = PointerButton.Primary,
                    )
                    renderTime += 50_000_000L
                    scene.render(renderTime)
                    insetOffset += 6f
                }
            }

        assertEquals(
            listOf(
                JoinSpec(
                    leftAlias = "t0",
                    leftColumn = "customer_id",
                    rightAlias = "t1",
                    rightColumn = "id",
                )
            ),
            queryViewModel.joins.toList(),
        )
        assertEquals(Offset.Zero, queryViewModel.canvasViewport.pan)
    }
}

private fun builderTable(
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

private val ordersTable =
    builderTable("orders", "customer_id", referencedTable = "customers", referencedColumn = "id")

private val customersTable = builderTable("customers", "id")

private val connectionDef =
    ConnectionDef(
        id = "c1",
        name = "Test Connection",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "testdb",
        username = "readonly",
    )

private object BuilderJoinClickService : FakeSafeDbServiceSupport() {
    override suspend fun getSchema(connectionId: String) =
        Schema(listOf(ordersTable, customersTable))
}
