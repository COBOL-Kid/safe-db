package com.safedb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class FilterRowLayoutTest {
    @Test
    fun timestampFilterValueIsFullyVisibleAtDefaultAndNarrowWidths() {
        assertTimestampValueReadable(sceneWidth = 720)
        assertTimestampValueReadable(sceneWidth = 360)
    }

    @Test
    fun customersTableAndInListStayReadableAtOverlayWidths() {
        listOf(407, 463, 720).forEach { sceneWidth ->
            val viewModel = customersInListFilterViewModel()
            val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
            ImageComposeScene(width = sceneWidth, height = 220, density = Density(1f)) {
                    SafeDbTheme(isDark = false) {
                        Box(Modifier.fillMaxWidth()) {
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
                    val customers = scene.requireContaining("customers")
                    val alice = scene.requireContaining("alice")
                    val bob = scene.requireContaining("bob")
                    val carol = scene.requireContaining("carol")
                    assertTrue(
                        customers.boundsInRoot.left >= -0.5f,
                        "table chip clipped on the left at ${sceneWidth}px: ${customers.boundsInRoot}",
                    )
                    assertTrue(
                        customers.boundsInRoot.width >= 56f,
                        "table name was only ${customers.boundsInRoot.width}px at ${sceneWidth}px",
                    )
                    for (value in listOf(alice, bob, carol)) {
                        assertTrue(
                            value.boundsInRoot.left >= -0.5f,
                            "list value clipped on the left at ${sceneWidth}px: ${value.boundsInRoot}",
                        )
                        assertTrue(
                            value.boundsInRoot.right <= sceneWidth + 0.5f,
                            "list value overflowed ${sceneWidth}px: ${value.boundsInRoot}",
                        )
                    }
                    if (sceneWidth <= 463) {
                        val tops = listOf(alice, bob, carol).map { it.boundsInRoot.top }.distinct()
                        assertTrue(
                            tops.size > 1 || alice.boundsInRoot.top > customers.boundsInRoot.top,
                            "IN-list did not wrap at ${sceneWidth}px: " +
                                "customers=${customers.boundsInRoot}, " +
                                "values=${listOf(alice, bob, carol).map { it.boundsInRoot }}",
                        )
                    }
                    val overflows =
                        scene.allSemanticsNodes().filter {
                            it.boundsInRoot.right > sceneWidth + 0.5f ||
                                it.boundsInRoot.left < -0.5f
                        }
                    assertTrue(
                        overflows.isEmpty(),
                        "nodes overflowed ${sceneWidth}px: ${
                            overflows.map { "${it.label()} ${it.boundsInRoot}" }
                        }",
                    )
                }
        }
    }

    @Test
    fun inListFilterWrapsAtNarrowWidth() {
        val viewModel = inListFilterViewModel()
        val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
        ImageComposeScene(width = 360, height = 220, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    Box(Modifier.fillMaxWidth()) {
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
                val values =
                    (1..4).map { index ->
                        scene.requireContaining("Filter value $index for customer_id")
                    }
                val tops = values.map { it.boundsInRoot.top }.distinct()
                assertTrue(
                    tops.size > 1,
                    "list values did not wrap: tops=$tops, " +
                        "all=${values.map { it.boundsInRoot }}",
                )
                assertTrue(
                    values.all { it.boundsInRoot.right <= 360f + 0.5f },
                    "list values overflowed: ${values.map { it.boundsInRoot }}",
                )
            }
    }

    private fun assertTimestampValueReadable(sceneWidth: Int) {
        val viewModel = timestampFilterViewModel()
        val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
        ImageComposeScene(width = sceneWidth, height = 120, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    Box(Modifier.fillMaxWidth()) {
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
                val value = scene.requireContaining("Filter value for order_date")
                val table = scene.requireContaining("Table:")
                if (sceneWidth == 720) {
                    assertTrue(
                        abs(value.boundsInRoot.top - table.boundsInRoot.top) <= 0.5f,
                        "value top=${value.boundsInRoot.top}, table top=${table.boundsInRoot.top}",
                    )
                } else {
                    assertTrue(
                        value.boundsInRoot.top > table.boundsInRoot.top,
                        "value top=${value.boundsInRoot.top} did not wrap below " +
                            "table top=${table.boundsInRoot.top}",
                    )
                }
                assertNotNull(scene.findContaining("2025-07-03 09:12:00"))
                // Allow for one side of the 8.dp horizontal padding.
                val innerMin = FILTER_SINGLE_VALUE_WIDTH_DP - 8
                assertTrue(
                    value.boundsInRoot.width >= innerMin,
                    "timestamp field was ${value.boundsInRoot.width}px at ${sceneWidth}px",
                )
                val overflows =
                    scene.allSemanticsNodes().filter { it.boundsInRoot.right > sceneWidth + 0.5f }
                assertTrue(
                    overflows.isEmpty(),
                    "nodes overflowed ${sceneWidth}px: ${
                        overflows.map { "${it.label()} right=${it.boundsInRoot.right}" }
                    }",
                )
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

private fun customersInListFilterViewModel(): QueryViewModel {
    val viewModel =
        QueryViewModel(
            object : FakeSafeDbServiceSupport() {},
            CoroutineScope(Dispatchers.Unconfined),
        )
    viewModel.addTable(
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
    )
    viewModel.addFilter(
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
    return viewModel
}

private fun inListFilterViewModel(): QueryViewModel {
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
            id = "f-in",
            tableAlias = "t0",
            column = "customer_id",
            op = FilterOp.In,
            value =
                FilterValue.ListValue(
                    listOf(
                        FilterLiteral(LiteralKind.Int, "1"),
                        FilterLiteral(LiteralKind.Int, "2"),
                        FilterLiteral(LiteralKind.Int, "3"),
                        FilterLiteral(LiteralKind.Int, "4"),
                    )
                ),
        )
    )
    return viewModel
}
