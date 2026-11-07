package com.safedb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.safedb.model.ColumnInfo
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.LiteralKind
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class FilterRowLayoutTest {
    @Test
    fun singleValueFieldIsWideEnoughForSpaceSeparatedTimestamps() {
        assertEquals(176, FILTER_SINGLE_VALUE_MIN_WIDTH_DP)
        assertTrue(FILTER_SINGLE_VALUE_MIN_WIDTH_DP >= "2025-07-03 09:12:00".length * 8)
    }

    @Test
    fun timestampFilterValueIsFullyVisibleAtDefaultAndNarrowWidths() {
        assertTimestampValueReadable(sceneWidth = 720)
        assertTimestampValueReadable(sceneWidth = 360)
    }

    private fun assertTimestampValueReadable(sceneWidth: Int) {
        val viewModel = timestampFilterViewModel()
        val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
        ImageComposeScene(width = sceneWidth, height = 120, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    Box(Modifier.width(sceneWidth.dp).fillMaxWidth()) {
                        FilterRow(
                            queryViewModel = viewModel,
                            filter = filter,
                            path = listOf(0),
                        )
                    }
                }
            }
            .use { scene ->
                scene.render(0L)
                val value = scene.requireContaining("2025-07-03 09:12:00")
                // Horizontal padding is 8.dp on each side of the 176.dp field.
                val innerMin = FILTER_SINGLE_VALUE_MIN_WIDTH_DP - 16
                assertTrue(
                    value.boundsInRoot.width >= innerMin,
                    "timestamp field was ${value.boundsInRoot.width}px at ${sceneWidth}px",
                )
                assertNotNull(scene.findContaining("Filter value"))
            }
    }
}

private fun timestampFilterViewModel(): QueryViewModel {
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
                    ColumnInfo("order_date", "timestamp", nullable = true),
                    ColumnInfo("customer_id", "bigint", nullable = false, isIndexed = true),
                ),
            indexes = emptyList(),
        )
    )
    viewModel.addFilter(
        FilterSpec(
            id = "f-ts",
            tableAlias = "t0",
            column = "order_date",
            op = FilterOp.Gte,
            value = FilterValue.Single(FilterLiteral(LiteralKind.DateTime, "2025-07-03 09:12:00")),
        )
    )
    return viewModel
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.findContaining(text: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label().contains(text) }

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.requireContaining(text: String): SemanticsNode =
    findContaining(text)
        ?: error("No node containing '$text' in ${allSemanticsNodes().map { it.label() }}")

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.findExact(label: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label() == label }

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.allSemanticsNodes(): List<SemanticsNode> {
    val nodes = mutableListOf<SemanticsNode>()
    for (owner in semanticsOwners) {
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk(owner.rootSemanticsNode)
    }
    return nodes
}

private fun SemanticsNode.label(): String {
    val texts =
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString { it.text }
        } else {
            ""
        }
    val editable =
        if (config.contains(SemanticsProperties.EditableText)) {
            config[SemanticsProperties.EditableText].text
        } else {
            ""
        }
    val descriptions =
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].joinToString()
        } else {
            ""
        }
    return listOf(texts, editable, descriptions).filter { it.isNotEmpty() }.joinToString(" | ")
}
