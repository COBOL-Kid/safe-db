package com.safedb.query

import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterSpec
import com.safedb.model.GroupConnector
import java.util.UUID

fun filterGroupAtPath(group: FilterGroup, path: List<Int>): FilterGroup? {
    if (path.isEmpty()) return group
    val child = group.children.getOrNull(path.first()) ?: return null
    return when (child) {
        is FilterNode.Group -> filterGroupAtPath(child.group, path.drop(1))
        is FilterNode.Leaf -> null
    }
}

fun addFilterLeaf(
    group: FilterGroup,
    path: List<Int>,
    spec: FilterSpec,
    idFactory: () -> String = { UUID.randomUUID().toString() },
): FilterGroup {
    val identified = spec.copy(id = spec.id.ifEmpty(idFactory))
    if (path.isEmpty()) return group.copy(children = group.children + FilterNode.Leaf(identified))
    val index = path.first()
    val child = group.children.getOrNull(index) as? FilterNode.Group ?: return group
    val children = group.children.toMutableList()
    children[index] =
        FilterNode.Group(addFilterLeaf(child.group, path.drop(1), identified, idFactory))
    return group.copy(children = children)
}

fun addFilterGroup(
    group: FilterGroup,
    path: List<Int>,
    connector: GroupConnector,
    idFactory: () -> String = { UUID.randomUUID().toString() },
): FilterGroup {
    if (path.isEmpty()) {
        return group.copy(
            children =
                group.children +
                    FilterNode.Group(FilterGroup(id = idFactory(), connector = connector))
        )
    }
    val index = path.first()
    val child = group.children.getOrNull(index) as? FilterNode.Group ?: return group
    val children = group.children.toMutableList()
    children[index] =
        FilterNode.Group(addFilterGroup(child.group, path.drop(1), connector, idFactory))
    return group.copy(children = children)
}

fun updateFilterNode(group: FilterGroup, path: List<Int>, newNode: FilterNode): FilterGroup {
    if (path.isEmpty()) return group
    val index = path.first()
    if (index !in group.children.indices) return group
    val children = group.children.toMutableList()
    if (path.size == 1) {
        children[index] = newNode
    } else {
        val child = children[index] as? FilterNode.Group ?: return group
        children[index] = FilterNode.Group(updateFilterNode(child.group, path.drop(1), newNode))
    }
    return group.copy(children = children)
}

fun removeFilterNode(group: FilterGroup, path: List<Int>): FilterGroup {
    if (path.isEmpty()) return group
    val index = path.first()
    if (index !in group.children.indices) return group
    if (path.size == 1) {
        return group.copy(
            children = group.children.filterIndexed { childIndex, _ -> childIndex != index }
        )
    }
    val child = group.children[index] as? FilterNode.Group ?: return group
    val children = group.children.toMutableList()
    children[index] = FilterNode.Group(removeFilterNode(child.group, path.drop(1)))
    return group.copy(children = children)
}

fun pruneFiltersForAlias(group: FilterGroup, alias: String): FilterGroup =
    group.copy(
        children =
            group.children.mapNotNull { child ->
                when (child) {
                    is FilterNode.Leaf -> child.takeUnless { it.spec.tableAlias == alias }
                    is FilterNode.Group -> {
                        val pruned = pruneFiltersForAlias(child.group, alias)
                        FilterNode.Group(pruned).takeIf { pruned.children.isNotEmpty() }
                    }
                }
            }
    )

internal fun filterNodeId(node: FilterNode): String =
    when (node) {
        is FilterNode.Leaf -> node.spec.id
        is FilterNode.Group -> node.group.id
    }

fun filterNodeIdAtPath(group: FilterGroup, path: List<Int>): String? {
    if (path.isEmpty()) return group.id.ifEmpty { null }
    val child = group.children.getOrNull(path.first()) ?: return null
    return when (child) {
        is FilterNode.Leaf -> child.spec.id.ifEmpty { null }.takeIf { path.size == 1 }
        is FilterNode.Group -> filterNodeIdAtPath(child.group, path.drop(1))
    }
}

fun filterLeafIdAtPath(group: FilterGroup, path: List<Int>): String? =
    if (path.isEmpty()) null else filterNodeIdAtPath(group, path)

fun ensureFilterNodeIds(
    group: FilterGroup,
    idFactory: () -> String = { UUID.randomUUID().toString() },
): FilterGroup =
    group.copy(
        id = group.id.ifEmpty(idFactory),
        children =
            group.children.map { child ->
                when (child) {
                    is FilterNode.Leaf ->
                        FilterNode.Leaf(
                            child.spec.takeIf { it.id.isNotEmpty() }
                                ?: child.spec.copy(id = idFactory())
                        )
                    is FilterNode.Group ->
                        FilterNode.Group(ensureFilterNodeIds(child.group, idFactory))
                }
            },
    )

fun rebuildConnectorOverrides(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    modifiedGroupPath: List<Int>? = null,
): Map<String, GroupConnector> {
    val eligibleIds = mutableSetOf<String>()
    val parents = mutableMapOf<String, FilterGroup>()

    fun visit(parent: FilterGroup) {
        parent.children.forEachIndexed { index, child ->
            val id = filterNodeId(child)
            if (index > 0 && id.isNotEmpty()) {
                eligibleIds += id
                parents[id] = parent
            }
            if (child is FilterNode.Group) visit(child.group)
        }
    }
    visit(group)

    val modifiedGroup = modifiedGroupPath?.let { filterGroupAtPath(group, it) }
    return overrides.filter { (id, connector) ->
        id in eligibleIds &&
            (modifiedGroup == null ||
                parents[id]?.let { it.id != modifiedGroup.id || it.connector != connector } !=
                    false)
    }
}
