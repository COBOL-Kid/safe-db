package com.safedb.tools

import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.BuilderScreen
import com.safedb.ui.Canvas
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * Dev-only proof: opens a real window and dispatches genuine AWT mouse events (the real desktop
 * input pipeline, unlike ImageComposeScene) at the dashed suggested-join line, then reports whether
 * the join was created.
 *
 * Configuration via environment variables:
 * - SAFEDB_PROOF_MODE: "canvas" (bare canvas) or "chrome" (full BuilderScreen). Default "canvas".
 * - SAFEDB_PROOF_ZOOM: canvas zoom factor. Default 1.0.
 * - SAFEDB_PROOF_PAN: "x,y" canvas pan in world px. Default "0,0".
 */
fun main() {
    val mode = System.getenv("SAFEDB_PROOF_MODE") ?: "canvas"
    val zoom = (System.getenv("SAFEDB_PROOF_ZOOM") ?: "1.0").toFloat()
    val pan = (System.getenv("SAFEDB_PROOF_PAN") ?: "0,0").split(",").map { it.trim().toFloat() }
    runProof(mode, zoom, Offset(pan[0], pan[1]))
}

private fun runProof(mode: String, zoom: Float, pan: Offset) = application {
    val scope = remember { CoroutineScope(Dispatchers.Unconfined) }
    val viewModel = remember {
        QueryViewModel(JoinProofService, scope).apply {
            addTable(proofTable("orders", "customer_id", "customers", "id"))
            addTable(proofTable("customers", "id"))
            // Cards are density-scaled in world px; keep a wide clear gap between them.
            moveTable("t0", 40f, 60f)
            moveTable("t1", 1100f, 100f)
            canvasViewport.setZoom(zoom)
            canvasViewport.updatePan(pan)
        }
    }
    val schemaViewModel = remember {
        SchemaViewModel(JoinProofService, scope).also {
            it.load("c1", selection = SchemaSelectionIntent("public", SchemaSelectionSource.User))
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "safe-db join click proof",
        undecorated = true,
        state =
            rememberWindowState(
                width = 1400.dp,
                height = 900.dp,
                position = WindowPosition(0.dp, 0.dp),
            ),
    ) {
        val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
        SafeDbTheme(isDark = false) {
            Surface {
                if (mode == "chrome") {
                    BuilderScreen(
                        connection = proofConnection,
                        connections = listOf(proofConnection),
                        queryViewModel = viewModel,
                        savedQueriesViewModel = SavedQueriesViewModel(JoinProofService, scope),
                        recipesViewModel = RecipesViewModel(JoinProofService, scope),
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
                } else {
                    Canvas(viewModel)
                }
            }
        }
        val awtWindow = window
        val density = LocalDensity.current
        LaunchedEffect(Unit) {
            delay(2000)

            fun dispatch(id: Int, px: Int, py: Int, modifiers: Int, button: Int) {
                val target =
                    javax.swing.SwingUtilities.getDeepestComponentAt(awtWindow.contentPane, px, py)
                        ?: awtWindow
                val point =
                    javax.swing.SwingUtilities.convertPoint(
                        awtWindow.contentPane,
                        java.awt.Point(px, py),
                        target,
                    )
                target.dispatchEvent(
                    MouseEvent(
                        target,
                        id,
                        System.currentTimeMillis(),
                        modifiers,
                        point.x,
                        point.y,
                        if (id == MouseEvent.MOUSE_CLICKED) 1 else 0,
                        false,
                        button,
                    )
                )
            }

            if (System.getenv("SAFEDB_PROOF_TABLEDRAG") != null && mode == "canvas") {
                // Real user flow: drag t1's header before clicking the dashed line.
                val t1 = viewModel.canvasTables.first { it.alias == "t1" }
                val headerX =
                    (((t1.x + with(density) { t1.width.dp.toPx() } / 2f) * zoom + pan.x) /
                            density.density)
                        .toInt()
                val headerY = (((t1.y + 30f) * zoom + pan.y) / density.density).toInt()
                dispatch(MouseEvent.MOUSE_MOVED, headerX, headerY, 0, MouseEvent.NOBUTTON)
                delay(60)
                dispatch(
                    MouseEvent.MOUSE_PRESSED,
                    headerX,
                    headerY,
                    InputEvent.BUTTON1_DOWN_MASK,
                    MouseEvent.BUTTON1,
                )
                var dragStep = 0
                while (dragStep <= 60) {
                    dispatch(
                        MouseEvent.MOUSE_DRAGGED,
                        headerX + dragStep,
                        headerY + dragStep / 3,
                        InputEvent.BUTTON1_DOWN_MASK,
                        MouseEvent.BUTTON1,
                    )
                    delay(10)
                    dragStep += 6
                }
                dispatch(
                    MouseEvent.MOUSE_RELEASED,
                    headerX + 60,
                    headerY + 20,
                    0,
                    MouseEvent.BUTTON1,
                )
                delay(300)
                val moved = viewModel.canvasTables.first { it.alias == "t1" }
                println("PROOF: after real drag t1=(${moved.x},${moved.y}) was=(${t1.x},${t1.y})")
            }

            // Compute the dashed-line click point from the current (post-drag) table state.
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
                    routeJoinEdge(tables[0], "customer_id", tables[1], "id", allTables = tables)
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
                        (end.x - start.x) * (end.x - start.x) +
                            (end.y - start.y) * (end.y - start.y)
                    }
            val worldMid =
                Offset(
                    (segment.first.x + segment.second.x) / 2f,
                    (segment.first.y + segment.second.y) / 2f,
                )
            // World px -> window px (zoom/pan) -> AWT points. The chrome adds the sidebar
            // (288.dp + 1.dp divider) horizontally; its top bar and query-controls inset
            // shift y by an amount not observable here, so probe downward.
            val sidebarPts = if (mode == "chrome") 289 else 0
            val x =
                sidebarPts +
                    (((worldMid.x * zoom + viewModel.canvasViewport.pan.x) / density.density))
                        .toInt()
            val yBase =
                ((worldMid.y * zoom + viewModel.canvasViewport.pan.y) / density.density).toInt()
            val maxProbeOffset = if (mode == "chrome") 400 else 0
            println(
                "PROOF: mode=$mode zoom=$zoom pan=${viewModel.canvasViewport.pan} density=${density.density}"
            )
            println("PROOF: worldMid=$worldMid awtX=$x awtYBase=$yBase")

            var probeOffset = 0
            while (viewModel.joins.isEmpty() && probeOffset <= maxProbeOffset) {
                val y = yBase + probeOffset
                // Mimic a real user: hover onto the line, press, drift 1pt, release.
                dispatch(MouseEvent.MOUSE_MOVED, x, y, 0, MouseEvent.NOBUTTON)
                delay(15)
                dispatch(
                    MouseEvent.MOUSE_PRESSED,
                    x,
                    y,
                    InputEvent.BUTTON1_DOWN_MASK,
                    MouseEvent.BUTTON1,
                )
                delay(15)
                if (System.getenv("SAFEDB_PROOF_DRAG") != null) {
                    dispatch(
                        MouseEvent.MOUSE_DRAGGED,
                        x + 1,
                        y,
                        InputEvent.BUTTON1_DOWN_MASK,
                        MouseEvent.BUTTON1,
                    )
                    delay(15)
                }
                dispatch(MouseEvent.MOUSE_RELEASED, x, y, 0, MouseEvent.BUTTON1)
                delay(30)
                probeOffset += 4
            }
            delay(500)
            println("PROOF: joins=${viewModel.joins.toList()}")
            println(if (viewModel.joins.isNotEmpty()) "PROOF: SUCCESS" else "PROOF: FAILURE")
            exitProcess(if (viewModel.joins.isNotEmpty()) 0 else 1)
        }
    }
}

private fun proofTable(
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

private val proofConnection =
    ConnectionDef(
        id = "c1",
        name = "Proof",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "proof",
        username = "readonly",
    )

private object JoinProofService : FakeSafeDbServiceSupport() {
    override suspend fun getSchema(connectionId: String) =
        Schema(
            listOf(
                proofTable("orders", "customer_id", "customers", "id"),
                proofTable("customers", "id"),
            )
        )
}
