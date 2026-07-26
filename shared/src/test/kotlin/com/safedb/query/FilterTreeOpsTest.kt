package com.safedb.query

import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.LiteralKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilterTreeOpsTest {
    @Test
    fun idsAreDeterministicWhenFactoryIsInjected() {
        val ids = ArrayDeque(listOf("root", "leaf"))
        val identified = ensureFilterNodeIds(
            FilterGroup(children = listOf(FilterNode.Leaf(filter(alias = "t0")))),
            ids::removeFirst,
        )

        assertEquals("root", identified.id)
        assertEquals("leaf", (identified.children.single() as FilterNode.Leaf).spec.id)
    }

    @Test
    fun nestedRemovalAndAliasPruningDropEmptyGroups() {
        val nested = FilterGroup(
            id = "root",
            children = listOf(
                FilterNode.Leaf(filter("keep", "t0")),
                FilterNode.Group(
                    FilterGroup(
                        id = "group",
                        children = listOf(FilterNode.Leaf(filter("drop", "t1"))),
                    ),
                ),
            ),
        )

        assertEquals(1, countFilterLeaves(removeFilterNode(nested, listOf(1, 0))))
        val pruned = pruneFiltersForAlias(nested, "t1")
        assertEquals(1, pruned.children.size)
        assertNull(filterGroupAtPath(pruned, listOf(1)))
    }

    @Test
    fun rebuildingOverridesRemovesMissingAndDefaultConnectors() {
        val tree = FilterGroup(
            id = "root",
            connector = GroupConnector.And,
            children = listOf(
                FilterNode.Leaf(filter("first", "t0")),
                FilterNode.Leaf(filter("second", "t0")),
            ),
        )

        assertEquals(
            mapOf("second" to GroupConnector.Or),
            rebuildConnectorOverrides(
                tree,
                mapOf("missing" to GroupConnector.Or, "second" to GroupConnector.Or),
                modifiedGroupPath = emptyList(),
            ),
        )
        assertEquals(
            emptyMap(),
            rebuildConnectorOverrides(
                tree,
                mapOf("second" to GroupConnector.And),
                modifiedGroupPath = emptyList(),
            ),
        )
    }

    private fun filter(id: String = "", alias: String) = FilterSpec(
        id = id,
        tableAlias = alias,
        column = "id",
        op = FilterOp.Eq,
        value = FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
    )
}
