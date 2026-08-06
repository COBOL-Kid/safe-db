package com.safedb.schema

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName

internal enum class SchemaMapCardinality {
    OneToOne,
    ManyToOne,
    Unknown,
}

internal enum class SchemaMapOptionality {
    Required,
    Optional,
    Unknown,
}

internal enum class SchemaMapColumnMarkerKind {
    PrimaryKey,
    Unique,
    ForeignKey,
    Index,
}

internal data class SchemaMapColumnMarker(
    val kind: SchemaMapColumnMarkerKind,
    val tooltip: String,
)

internal data class SchemaMapColumn(
    val column: ColumnInfo,
    val markers: List<SchemaMapColumnMarker>,
) {
    val isKeyRelated: Boolean
        get() = markers.isNotEmpty()
}

internal data class SchemaMapNode(
    val id: String,
    val label: String,
    val table: TableInfo? = null,
    val externalColumns: List<String> = emptyList(),
    val columns: List<SchemaMapColumn> = emptyList(),
) {
    val isExternal: Boolean
        get() = table == null
}

internal data class SchemaMapRelationship(
    val id: String,
    val name: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val sourceColumns: List<String>,
    val targetColumns: List<String>,
    val cardinality: SchemaMapCardinality,
    val optionality: SchemaMapOptionality,
    val description: String,
)

internal data class SchemaMapGraph(
    val nodes: List<SchemaMapNode>,
    val relationships: List<SchemaMapRelationship>,
)

internal data class SchemaMapSearchResult(
    val nodeIds: Set<String>,
    val relationshipIds: Set<String>,
    val columnsByNode: Map<String, List<String>>,
) {
    val isEmpty: Boolean
        get() = nodeIds.isEmpty() && relationshipIds.isEmpty()
}

internal data class SchemaMapPoint(val x: Float, val y: Float)

internal data class SchemaMapSize(val width: Float, val height: Float)

internal fun buildSchemaMapGraph(schema: Schema, selectedSchema: String): SchemaMapGraph {
    val selectedTables =
        schema.tables
            .filter { it.schema == selectedSchema }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val selectedById = selectedTables.associateBy(TableInfo::qualifiedName)
    val externalColumns = linkedMapOf<String, LinkedHashSet<String>>()
    val relationships = mutableListOf<SchemaMapRelationship>()

    for (table in selectedTables) {
        for (foreignKey in table.foreignKeys.sortedBy { it.name.lowercase() }) {
            if (foreignKey.columns.isEmpty()) continue
            if (foreignKey.columns.size != foreignKey.referencedColumns.size) continue
            val targetId = qualifiedId(foreignKey.referencedSchema, foreignKey.referencedTable)
            if (targetId !in selectedById) {
                externalColumns
                    .getOrPut(targetId, ::linkedSetOf)
                    .addAll(foreignKey.referencedColumns)
            }
            val cardinality = relationshipCardinality(table, foreignKey)
            val optionality = relationshipOptionality(table, foreignKey)
            relationships +=
                SchemaMapRelationship(
                    id =
                        "${table.qualifiedName()}|${foreignKey.name}|$targetId|" +
                            foreignKey.columns.joinToString(","),
                    name = foreignKey.name,
                    sourceNodeId = table.qualifiedName(),
                    targetNodeId = targetId,
                    sourceColumns = foreignKey.columns,
                    targetColumns = foreignKey.referencedColumns,
                    cardinality = cardinality,
                    optionality = optionality,
                    description =
                        relationshipDescription(table, foreignKey, cardinality, optionality),
                )
        }
    }

    val nodes = buildList {
        selectedTables.forEach { table ->
            add(
                SchemaMapNode(
                    id = table.qualifiedName(),
                    label = table.name,
                    table = table,
                    columns = table.columns.map { column -> projectColumn(table, column) },
                )
            )
        }
        externalColumns.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (id, columns) ->
                add(
                    SchemaMapNode(
                        id = id,
                        label = id,
                        externalColumns = columns.toList(),
                    )
                )
            }
    }
    return SchemaMapGraph(nodes, relationships.distinctBy(SchemaMapRelationship::id))
}

internal fun searchSchemaMap(graph: SchemaMapGraph, query: String): SchemaMapSearchResult {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) {
        return SchemaMapSearchResult(
            graph.nodes.mapTo(linkedSetOf()) { it.id },
            emptySet(),
            emptyMap(),
        )
    }

    val nodeIds = linkedSetOf<String>()
    val columns = linkedMapOf<String, List<String>>()
    for (node in graph.nodes) {
        val table = node.table
        val matchingColumns =
            if (table == null) {
                node.externalColumns.filter { it.lowercase().contains(needle) }
            } else {
                table.columns
                    .filter { column ->
                        column.name.lowercase().contains(needle) ||
                            column.dataType.lowercase().contains(needle)
                    }
                    .map(ColumnInfo::name)
            }
        val metadataMatches =
            table?.indexes?.any { index ->
                index.name.lowercase().contains(needle) ||
                    indexKeyColumns(index).any { it.lowercase().contains(needle) }
            } == true ||
                table?.foreignKeys?.any { foreignKey ->
                    foreignKey.name.lowercase().contains(needle) ||
                        foreignKey.referencedTable.lowercase().contains(needle)
                } == true
        if (
            node.id.lowercase().contains(needle) ||
                node.label.lowercase().contains(needle) ||
                matchingColumns.isNotEmpty() ||
                metadataMatches
        ) {
            nodeIds += node.id
            if (matchingColumns.isNotEmpty()) columns[node.id] = matchingColumns
        }
    }

    val relationshipIds =
        graph.relationships
            .filter { relationship ->
                relationship.name.lowercase().contains(needle) ||
                    relationship.description.lowercase().contains(needle) ||
                    relationship.sourceColumns.any { it.lowercase().contains(needle) } ||
                    relationship.targetColumns.any { it.lowercase().contains(needle) }
            }
            .mapTo(linkedSetOf()) { relationship ->
                nodeIds += relationship.sourceNodeId
                nodeIds += relationship.targetNodeId
                relationship.id
            }
    return SchemaMapSearchResult(nodeIds, relationshipIds, columns)
}

/**
 * Deterministic component-aware layout. Referenced tables are placed before dependent tables;
 * cycles are collapsed into a single layer before nodes are ordered within that layer.
 */
internal fun layoutSchemaMap(
    graph: SchemaMapGraph,
    nodeSizes: Map<String, SchemaMapSize> = emptyMap(),
): Map<String, SchemaMapPoint> {
    if (graph.nodes.isEmpty()) return emptyMap()
    val nodesById = graph.nodes.associateBy(SchemaMapNode::id)
    val undirected = graph.nodes.associate { it.id to linkedSetOf<String>() }.toMutableMap()
    graph.relationships.forEach { relationship ->
        if (relationship.sourceNodeId in nodesById && relationship.targetNodeId in nodesById) {
            undirected.getValue(relationship.sourceNodeId) += relationship.targetNodeId
            undirected.getValue(relationship.targetNodeId) += relationship.sourceNodeId
        }
    }
    val components = connectedComponents(nodesById.keys, undirected)
    val result = linkedMapOf<String, SchemaMapPoint>()
    var packX = 0f
    var packY = 0f
    var rowHeight = 0f
    val packWidth = 1_650f

    for (component in components) {
        val local = layoutComponent(component, graph.relationships, nodeSizes, nodesById)
        val bounds = layoutBounds(component, local, nodeSizes, nodesById)
        if (packX > 0f && packX + bounds.width > packWidth) {
            packX = 0f
            packY += rowHeight + COMPONENT_GAP
            rowHeight = 0f
        }
        component.sorted().forEach { id ->
            val point = local.getValue(id)
            result[id] = SchemaMapPoint(point.x + packX, point.y + packY)
        }
        packX += bounds.width + COMPONENT_GAP
        rowHeight = maxOf(rowHeight, bounds.height)
    }
    return result
}

private fun relationshipCardinality(
    table: TableInfo,
    foreignKey: ForeignKeyInfo,
): SchemaMapCardinality {
    if (!table.indexMetadata.isComplete) return SchemaMapCardinality.Unknown

    val foreignColumns = foreignKey.columns.toSet()
    fun IndexInfo.exactForeignKeyColumns(): Boolean =
        exactIndexKeyColumns(this)?.let { keys ->
            keys.size == foreignKey.columns.size && keys.toSet() == foreignColumns
        } == true

    val exactUnique =
        table.indexes.any { index ->
            index.exactForeignKeyColumns() &&
                (index.isPrimary || (index.isUnique && index.isPartial == false))
        }
    val ambiguousExactUnique =
        table.indexes.any { index ->
            !index.isPrimary &&
                index.isUnique &&
                index.isPartial == null &&
                index.exactForeignKeyColumns()
        }
    return when {
        exactUnique -> SchemaMapCardinality.OneToOne
        ambiguousExactUnique -> SchemaMapCardinality.Unknown
        else -> SchemaMapCardinality.ManyToOne
    }
}

private fun relationshipOptionality(
    table: TableInfo,
    foreignKey: ForeignKeyInfo,
): SchemaMapOptionality {
    val columns = foreignKey.columns.map { name -> table.columns.find { it.name == name } }
    return when {
        columns.any { it == null } -> SchemaMapOptionality.Unknown
        columns.any { requireNotNull(it).nullable } -> SchemaMapOptionality.Optional
        else -> SchemaMapOptionality.Required
    }
}

private fun relationshipDescription(
    table: TableInfo,
    foreignKey: ForeignKeyInfo,
    cardinality: SchemaMapCardinality,
    optionality: SchemaMapOptionality,
): String {
    val kind =
        when (cardinality) {
            SchemaMapCardinality.OneToOne -> "one to one"
            SchemaMapCardinality.ManyToOne -> "many to one"
            SchemaMapCardinality.Unknown -> "cardinality unknown"
        }
    val requirement =
        when (optionality) {
            SchemaMapOptionality.Required -> "the referenced row is required"
            SchemaMapOptionality.Optional -> "the referenced row is optional"
            SchemaMapOptionality.Unknown -> "optionality is unknown"
        }
    return buildString {
        append(foreignKey.name)
        append(": ")
        append(table.name)
        append('.')
        append(foreignKey.columns.joinToString(" + "))
        append(" → ")
        append(foreignKey.referencedSchema)
        append('.')
        append(foreignKey.referencedTable)
        append('.')
        append(foreignKey.referencedColumns.joinToString(" + "))
        append(" · ")
        append(kind)
        append("; ")
        append(requirement)
        append('.')
    }
}

private fun projectColumn(table: TableInfo, column: ColumnInfo): SchemaMapColumn {
    fun marker(kind: SchemaMapColumnMarkerKind, lines: List<String>) =
        lines.takeIf(List<String>::isNotEmpty)?.let {
            SchemaMapColumnMarker(kind, it.joinToString("\n"))
        }

    val primary =
        table.indexes
            .filter { it.isPrimary && column.name in indexKeyColumns(it) }
            .map(::indexTooltip)
    val unique =
        table.indexes
            .filter { !it.isPrimary && it.isUnique && column.name in indexKeyColumns(it) }
            .map(::indexTooltip)
    val foreign =
        table.foreignKeys
            .filter { column.name in it.columns }
            .map { key ->
                val sourcePosition = key.columns.indexOf(column.name)
                val target = key.referencedColumns.getOrNull(sourcePosition) ?: "?"
                "Foreign key ${key.name}: ${column.name} → ${key.referencedSchema}.${key.referencedTable}.$target"
            }
    val indexes =
        table.indexes
            .filter { index ->
                (!index.isPrimary && !index.isUnique && column.name in indexKeyColumns(index)) ||
                    column.name in index.includedColumns
            }
            .map(::indexTooltip)
    return SchemaMapColumn(
        column,
        listOfNotNull(
            marker(SchemaMapColumnMarkerKind.PrimaryKey, primary),
            marker(SchemaMapColumnMarkerKind.Unique, unique),
            marker(SchemaMapColumnMarkerKind.ForeignKey, foreign),
            marker(SchemaMapColumnMarkerKind.Index, indexes),
        ),
    )
}

internal fun indexTooltip(index: IndexInfo): String = buildString {
    append(
        when {
            index.isPrimary -> "Primary key index"
            index.isUnique && index.isPartial == true -> "Partial unique index"
            index.isUnique -> "Unique index"
            else -> "Index"
        }
    )
    append(" ${index.name}")
    index.kind.takeIf(String::isNotBlank)?.let { append(" · $it") }
    val keys = indexKeyDescription(index)
    if (keys.isNotBlank()) append(": $keys")
    if (index.includedColumns.isNotEmpty()) {
        append(" · includes ${index.includedColumns.joinToString(", ")}")
    }
    when (index.isPartial) {
        true -> append(" · partial predicate")
        null -> append(" · predicate details unavailable")
        false -> Unit
    }
}

private fun indexKeyColumns(index: IndexInfo): List<String> =
    if (index.keys.isNotEmpty()) index.keys.mapNotNull { it.column } else index.columns

private fun exactIndexKeyColumns(index: IndexInfo): List<String>? =
    if (index.keys.isNotEmpty()) {
        index.keys
            .takeUnless { keys -> keys.any { it.expression || it.column == null } }
            ?.map { requireNotNull(it.column) }
    } else {
        index.columns
    }

private fun indexKeyDescription(index: IndexInfo): String =
    if (index.keys.isNotEmpty()) {
        index.keys.joinToString(", ") { key ->
            val label = key.column ?: "expression"
            when (key.direction) {
                SortDirection.Asc -> "$label ASC"
                SortDirection.Desc -> "$label DESC"
                null -> label
            }
        }
    } else {
        index.columns.joinToString(", ")
    }

private fun qualifiedId(schema: String, table: String) = "$schema.$table"

private fun connectedComponents(
    nodeIds: Set<String>,
    adjacency: Map<String, Set<String>>,
): List<Set<String>> {
    val remaining = nodeIds.toMutableSet()
    val result = mutableListOf<Set<String>>()
    while (remaining.isNotEmpty()) {
        val start = remaining.minOrNull() ?: break
        val queue = ArrayDeque<String>()
        val component = linkedSetOf<String>()
        queue += start
        remaining -= start
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            component += id
            adjacency[id].orEmpty().sorted().forEach { neighbor ->
                if (remaining.remove(neighbor)) queue += neighbor
            }
        }
        result += component
    }
    return result.sortedByDescending { component -> component.size }
}

private fun layoutComponent(
    component: Set<String>,
    relationships: List<SchemaMapRelationship>,
    nodeSizes: Map<String, SchemaMapSize>,
    nodesById: Map<String, SchemaMapNode>,
): Map<String, SchemaMapPoint> {
    val parentToChildren = component.associateWith { linkedSetOf<String>() }.toMutableMap()
    relationships.forEach { relationship ->
        if (relationship.sourceNodeId in component && relationship.targetNodeId in component) {
            parentToChildren.getValue(relationship.targetNodeId) += relationship.sourceNodeId
        }
    }
    val stronglyConnected = stronglyConnectedComponents(component, parentToChildren)
    val groupByNode = buildMap {
        stronglyConnected.forEachIndexed { i, group -> group.forEach { put(it, i) } }
    }
    val groupEdges = stronglyConnected.indices.associateWith { linkedSetOf<Int>() }.toMutableMap()
    val indegree = IntArray(stronglyConnected.size)
    parentToChildren.forEach { (parent, children) ->
        val from = groupByNode.getValue(parent)
        children.forEach { child ->
            val to = groupByNode.getValue(child)
            if (from != to && groupEdges.getValue(from).add(to)) indegree[to] += 1
        }
    }
    val layer = IntArray(stronglyConnected.size)
    val ready = java.util.PriorityQueue<Int>(compareBy { stronglyConnected[it].minOrNull() })
    indegree.indices.filter { indegree[it] == 0 }.forEach(ready::add)
    while (ready.isNotEmpty()) {
        val group = ready.remove()
        groupEdges[group].orEmpty().forEach { next ->
            layer[next] = maxOf(layer[next], layer[group] + 1)
            indegree[next] -= 1
            if (indegree[next] == 0) ready += next
        }
    }
    val nodesByLayer =
        component.sorted().groupBy { id -> layer[groupByNode.getValue(id)] }.toSortedMap()
    val xByLayer = mutableMapOf<Int, Float>()
    var x = 0f
    nodesByLayer.forEach { (layerIndex, ids) ->
        xByLayer[layerIndex] = x
        val widest = ids.maxOf { id -> sizeFor(id, nodeSizes, nodesById).width }
        x += widest + HORIZONTAL_GAP
    }
    val result = linkedMapOf<String, SchemaMapPoint>()
    nodesByLayer.forEach { (layerIndex, ids) ->
        var y = 0f
        ids.forEach { id ->
            result[id] = SchemaMapPoint(xByLayer.getValue(layerIndex), y)
            y += sizeFor(id, nodeSizes, nodesById).height + VERTICAL_GAP
        }
    }
    return result
}

private fun stronglyConnectedComponents(
    nodes: Set<String>,
    edges: Map<String, Set<String>>,
): List<Set<String>> {
    var nextIndex = 0
    val indexes = mutableMapOf<String, Int>()
    val lowLinks = mutableMapOf<String, Int>()
    val stack = ArrayDeque<String>()
    val onStack = mutableSetOf<String>()
    val groups = mutableListOf<Set<String>>()

    fun visit(node: String) {
        indexes[node] = nextIndex
        lowLinks[node] = nextIndex
        nextIndex += 1
        stack.addLast(node)
        onStack += node
        edges[node].orEmpty().sorted().forEach { neighbor ->
            if (neighbor !in indexes) {
                visit(neighbor)
                lowLinks[node] = minOf(lowLinks.getValue(node), lowLinks.getValue(neighbor))
            } else if (neighbor in onStack) {
                lowLinks[node] = minOf(lowLinks.getValue(node), indexes.getValue(neighbor))
            }
        }
        if (lowLinks[node] == indexes[node]) {
            val group = linkedSetOf<String>()
            do {
                val member = stack.removeLast()
                onStack -= member
                group += member
            } while (member != node)
            groups += group
        }
    }
    nodes.sorted().forEach { if (it !in indexes) visit(it) }
    return groups
}

private fun layoutBounds(
    component: Set<String>,
    positions: Map<String, SchemaMapPoint>,
    nodeSizes: Map<String, SchemaMapSize>,
    nodesById: Map<String, SchemaMapNode>,
): SchemaMapSize =
    SchemaMapSize(
        width =
            component.maxOf { id ->
                positions.getValue(id).x + sizeFor(id, nodeSizes, nodesById).width
            },
        height =
            component.maxOf { id ->
                positions.getValue(id).y + sizeFor(id, nodeSizes, nodesById).height
            },
    )

private fun sizeFor(
    id: String,
    sizes: Map<String, SchemaMapSize>,
    nodes: Map<String, SchemaMapNode>,
): SchemaMapSize =
    sizes[id]
        ?: if (nodes.getValue(id).isExternal) SchemaMapSize(220f, 92f)
        else SchemaMapSize(280f, 220f)

private const val HORIZONTAL_GAP = 120f
private const val VERTICAL_GAP = 48f
private const val COMPONENT_GAP = 96f
