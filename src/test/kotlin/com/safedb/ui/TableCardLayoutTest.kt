package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.safedb.canvas.CANVAS_CARD_WIDTH
import com.safedb.canvas.MIN_TABLE_WIDTH
import com.safedb.model.ColumnInfo
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            assertNotNull(scene.findVisualByTag("column-name-customer_id"))
            assertNotNull(scene.findVisualByTag("column-name-order_date"))
            assertNotNull(scene.findVisualByTag("column-name-signed_up_at"))
            assertNotNull(scene.findVisualByTag("column-name-occurred_at"))
            assertNull(scene.findVisualByTag("column-type-customer_id"))
            assertNull(scene.findVisualByTag("column-type-order_date"))
            assertNull(scene.findVisualByTag("column-type-occurred_at"))
            assertEquals(1, scene.nodesLabeled("customer_id, bigint").size)
            val filter = scene.requireExact("Filter customer_id")
            assertTrue(filter.boundsInRoot.width >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            assertTrue(filter.boundsInRoot.height >= TABLE_CARD_COLUMN_ACTION_SLOT_DP)
            val name = scene.requireVisualByTag("column-name-customer_id")
            assertTrue(
                name.boundsInRoot.width >= MIN_READABLE_NAME_WIDTH,
                "column name was only ${name.boundsInRoot.width}px at default card width",
            )

            val table = viewModel.canvasTables.single()
            assertFalse(viewModel.isColumnSelected(table.alias, "customer_id"))
            scene.click(scene.requireExact("customer_id, bigint").boundsInRoot.center)
            scene.settle()
            assertTrue(viewModel.isColumnSelected(table.alias, "customer_id"))
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
        viewModel.resizeTable("t0", width = 350f, height = 280f)
        withTableCard(viewModel, sceneWidth = 400) { scene ->
            val name = scene.requireVisualByTag("column-name-customer_id")
            val type = scene.requireVisualByTag("column-type-customer_id")
            val timestampType = scene.requireVisualByTag("column-type-order_date")
            // "timestamp" at 12sp monospace is ~65px; "bigint" is ~44px. Independent literals.
            assertTrue(
                timestampType.boundsInRoot.width >= 65f,
                "timestamp type was only ${timestampType.boundsInRoot.width}px at the reveal threshold",
            )
            assertTrue(
                type.boundsInRoot.width >= 43f,
                "bigint type was only ${type.boundsInRoot.width}px at the reveal threshold",
            )
            assertTrue(
                name.boundsInRoot.width >= MIN_READABLE_NAME_WIDTH,
                "name was only ${name.boundsInRoot.width}px at the reveal threshold",
            )
            val occurredAtName = scene.requireVisualByTag("column-name-occurred_at")
            assertNotNull(scene.findVisualByTag("column-type-occurred_at"))
            assertTrue(
                occurredAtName.boundsInRoot.width >= MIN_READABLE_NAME_WIDTH,
                "long-type column name was only ${occurredAtName.boundsInRoot.width}px at the reveal threshold",
            )
            assertEquals(1, scene.nodesLabeled("customer_id, bigint").size)
        }
    }
}

// "customer_id" at 12sp monospace is around 88px wide; keep the name readable even when the
// type is revealed. Deliberately independent of the production constant.
private const val MIN_READABLE_NAME_WIDTH = 88f

// Column texts clear their own semantics so the clickable row is the only announced label.
// Tests still address the visual texts through the unmerged tree.
private fun ImageComposeScene.findVisualByTag(tag: String): SemanticsNode? =
    semanticsOwners
        .flatMap { owner -> owner.unmergedRootSemanticsNode.descendantsUnmerged() }
        .firstOrNull { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag] == tag
        }

private fun ImageComposeScene.requireVisualByTag(tag: String): SemanticsNode =
    findVisualByTag(tag) ?: error("No visual node tagged '$tag'")

private fun SemanticsNode.descendantsUnmerged(): List<SemanticsNode> = buildList {
    add(this@descendantsUnmerged)
    for (child in children) addAll(child.descendantsUnmerged())
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
                    ColumnInfo("occurred_at", "timestamp with time zone", nullable = true),
                ),
            indexes = emptyList(),
        ),
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
    withComposeScene(
        width = sceneWidth,
        height = 320,
        content = {
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
        },
        body = body,
    )
}
