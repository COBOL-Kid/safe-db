package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.canvas.CANVAS_CARD_WIDTH
import com.safedb.canvas.MIN_TABLE_WIDTH
import com.safedb.model.ColumnInfo
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class TableCardLayoutTest {
    @Test
    fun defaultWidthShowsFullColumnNamesWithoutSqueezingActionTargets() {
        val viewModel = ordersViewModel()
        withTableCard(viewModel) { scene ->
            assertNotNull(scene.findExact("customer_id"))
            assertNotNull(scene.findExact("order_date"))
            assertNotNull(scene.findExact("signed_up_at"))
            assertNull(scene.findExact("bigint"))
            assertNull(scene.findExact("timestamp"))
            assertNotNull(scene.findExact("customer_id, bigint"))
            val filter = scene.requireExact("Filter customer_id")
            assertTrue(filter.boundsInRoot.width >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            assertTrue(filter.boundsInRoot.height >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            val name = scene.requireExact("customer_id")
            assertTrue(
                name.boundsInRoot.width >= TABLE_CARD_COLUMN_NAME_MIN_WIDTH_DP,
                "column name was only ${name.boundsInRoot.width}px at default card width",
            )
        }
    }

    @Test
    fun minWidthKeepsFilterAndJoinActionsUsable() {
        val viewModel = ordersViewModel()
        viewModel.resizeTable("t0", width = MIN_TABLE_WIDTH, height = 280f)
        withTableCard(viewModel, sceneWidth = 220) { scene ->
            val filter = scene.requireExact("Filter customer_id")
            assertTrue(filter.boundsInRoot.width >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            assertTrue(filter.boundsInRoot.height >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            assertNotNull(scene.findContaining("Join from customer_id"))
        }
    }

    @Test
    fun widerCardsRevealColumnTypesBesideNames() {
        val viewModel = ordersViewModel()
        viewModel.resizeTable("t0", width = 320f, height = 280f)
        withTableCard(viewModel, sceneWidth = 360) { scene ->
            assertNotNull(scene.findExact("customer_id"))
            assertNotNull(scene.findExact("bigint"))
            assertNotNull(scene.findExact("timestamp"))
            assertNull(scene.findExact("customer_id, bigint"))
        }
    }
}

private fun ordersViewModel(): QueryViewModel {
    val viewModel =
        QueryViewModel(
            object : FakeSafeDbServiceSupport() {},
            CoroutineScope(Dispatchers.Unconfined),
        )
    viewModel.addTable(
        TableInfo(
            schema = "public",
            name = "orders",
            columns =
                listOf(
                    ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                    ColumnInfo("customer_id", "bigint", nullable = false, isIndexed = true),
                    ColumnInfo("order_date", "timestamp", nullable = true),
                    ColumnInfo("status", "text", nullable = true),
                    ColumnInfo("signed_up_at", "timestamp", nullable = true),
                ),
            indexes = emptyList(),
        )
    )
    return viewModel
}

@OptIn(ExperimentalComposeUiApi::class)
private fun withTableCard(
    viewModel: QueryViewModel,
    sceneWidth: Int = CANVAS_CARD_WIDTH.toInt() + 24,
    body: (ImageComposeScene) -> Unit,
) {
    val canvasTable = viewModel.canvasTables.single()
    ImageComposeScene(width = sceneWidth, height = 320, density = Density(1f)) {
            SafeDbTheme(isDark = false) {
                TableCard(
                    canvasTable = canvasTable,
                    queryViewModel = viewModel,
                    onStartDrag = {},
                    onDragTable = {},
                    onStartJoin = {},
                    onDragJoin = {},
                    onJoinClick = {},
                    onJoinTargetClick = { _, _ -> },
                    onStartResize = {},
                    onResizeTable = {},
                    onEndGesture = {},
                )
            }
        }
        .use { scene ->
            scene.render(0L)
            body(scene)
        }
}
