package com.safedb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class FilterRowLayoutTest {
    @Test
    fun timestampFilterValueIsFullyVisibleAtDefaultAndNarrowWidths() {
        assertTimestampValueReadable(sceneWidth = 720)
        assertTimestampValueReadable(sceneWidth = 360)
    }

    @Test
    fun customersTableAndInListStayReadableAtOverlayWidths() {
        listOf(407, 463, 720).forEach { sceneWidth ->
            withFilterRow(customersTable, customersInListFilter, sceneWidth) { scene ->
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
                scene.assertNoHorizontalOverflow(sceneWidth)
            }
        }
    }

    @Test
    fun inListFilterWrapsAtNarrowWidth() {
        withFilterRow(ordersTable, customerIdInListFilter, 360) { scene ->
            val values =
                (1..4).map { index ->
                    scene.requireContaining("Filter value $index for customer_id")
                }
            val tops = values.map { it.boundsInRoot.top }.distinct()
            assertTrue(
                tops.size > 1,
                "list values did not wrap: tops=$tops, " + "all=${values.map { it.boundsInRoot }}",
            )
            assertTrue(
                values.all { it.boundsInRoot.right <= 360f + 0.5f },
                "list values overflowed: ${values.map { it.boundsInRoot }}",
            )
            values.forEachIndexed { index, value ->
                val remove = scene.requireContaining("Remove value ${index + 1} for customer_id")
                // The 28dp button is centered against the 30dp input, so compare centers
                // rather than raw tops to prove they share one row.
                assertEquals(
                    value.boundsInRoot.center.y,
                    remove.boundsInRoot.center.y,
                    0.5f,
                    "remove button ${index + 1} split from its input: " +
                        "remove=${remove.boundsInRoot} input=${value.boundsInRoot}",
                )
            }
        }
    }

    @Test
    fun betweenOperatorLabelFitsBesideTheDropdownArrow() {
        listOf(360, 720).forEach { sceneWidth ->
            withFilterRow(ordersTable, betweenFilter, sceneWidth) { scene ->
                val between = scene.requireContaining("BETWEEN")
                assertTrue(
                    between.boundsInRoot.width >= BETWEEN_OP_LABEL_MIN_WIDTH_PX,
                    "BETWEEN was truncated to ${between.boundsInRoot.width}px at ${sceneWidth}px",
                )
            }
        }
    }

    @Test
    fun betweenTimestampValuesStayReadableAndGrouped() {
        listOf(360, 407, 720).forEach { sceneWidth ->
            withFilterRow(ordersTable, betweenFilter, sceneWidth) { scene ->
                val from = scene.requireContaining("Filter value from order_date")
                val to = scene.requireContaining("Filter value to order_date")
                val and = scene.requireExact("and")
                assertNotNull(scene.findContaining("2025-07-01 00:00:00"))
                assertNotNull(scene.findContaining("2025-07-31 23:59:59"))
                assertTrue(
                    from.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
                    "from field was ${from.boundsInRoot.width}px at ${sceneWidth}px",
                )
                assertTrue(
                    to.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
                    "to field was ${to.boundsInRoot.width}px at ${sceneWidth}px",
                )
                assertBetweenRelationship(from, and, to)
                if (sceneWidth >= 407) {
                    assertEquals(
                        from.boundsInRoot.top,
                        to.boundsInRoot.top,
                        0.5f,
                        "pair inputs split at ${sceneWidth}px: from=${from.boundsInRoot} " +
                            "to=${to.boundsInRoot}",
                    )
                }
                scene.assertNoHorizontalOverflow(sceneWidth)
            }
        }
    }

    private fun assertTimestampValueReadable(sceneWidth: Int) {
        withFilterRow(ordersTable, timestampFilter, sceneWidth, height = 120) { scene ->
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
            // "2025-07-03 09:12:00" at 12sp monospace needs ~168px after the inner padding.
            assertTrue(
                value.boundsInRoot.width >= TIMESTAMP_FIELD_MIN_WIDTH_PX,
                "timestamp field was ${value.boundsInRoot.width}px at ${sceneWidth}px",
            )
            scene.assertNoHorizontalOverflow(sceneWidth)
        }
    }
}

private val ordersTable =
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

private val timestampFilter =
    FilterSpec(
        id = "f-ts",
        tableAlias = "t0",
        column = "order_date",
        op = FilterOp.Gte,
        value = FilterValue.Single(FilterLiteral(LiteralKind.DateTime, "2025-07-03 09:12:00")),
    )

private val customersInListFilter =
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
                ),
            ),
    )

private val customerIdInListFilter =
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
                ),
            ),
    )

private val betweenFilter =
    FilterSpec(
        id = "f-between",
        tableAlias = "t0",
        column = "order_date",
        op = FilterOp.Between,
        value =
            FilterValue.Pair(
                FilterLiteral(LiteralKind.DateTime, "2025-07-01 00:00:00"),
                FilterLiteral(LiteralKind.DateTime, "2025-07-31 23:59:59"),
            ),
    )

@OptIn(ExperimentalComposeUiApi::class)
private fun withFilterRow(
    table: TableInfo,
    filter: FilterSpec,
    sceneWidth: Int,
    height: Int = 220,
    body: (ImageComposeScene) -> Unit,
) {
    val queryViewModel = filterRowViewModel(table, filter)
    val leaf = (queryViewModel.filters.children.single() as FilterNode.Leaf).spec
    withComposeScene(
        width = sceneWidth,
        height = height,
        content = {
            SafeDbTheme(isDark = false) {
                Box(Modifier.fillMaxWidth()) {
                    FilterRow(
                        queryViewModel = queryViewModel,
                        filter = leaf,
                        path = listOf(0),
                    )
                }
            }
        },
        body = body,
    )
}

private fun filterRowViewModel(table: TableInfo, filter: FilterSpec): QueryViewModel {
    val viewModel =
        QueryViewModel(
            object : FakeSafeDbServiceSupport() {},
            CoroutineScope(Dispatchers.Unconfined),
        )
    viewModel.addTable(table)
    viewModel.addFilter(filter)
    return viewModel
}
