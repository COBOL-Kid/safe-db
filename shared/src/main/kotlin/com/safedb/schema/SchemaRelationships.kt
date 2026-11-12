package com.safedb.schema

import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.keyColumnNames
import com.safedb.model.qualifiedName

enum class RelationshipCardinality {
    OneToOne,
    ManyToOne,
    Unknown,
}

enum class RelationshipOptionality {
    Required,
    Optional,
    Unknown,
}

fun relationshipCardinality(
    table: TableInfo,
    foreignKey: ForeignKeyInfo,
): RelationshipCardinality {
    if (!table.indexMetadata.isComplete) return RelationshipCardinality.Unknown

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
        exactUnique -> RelationshipCardinality.OneToOne
        ambiguousExactUnique -> RelationshipCardinality.Unknown
        else -> RelationshipCardinality.ManyToOne
    }
}

fun relationshipOptionality(
    table: TableInfo,
    foreignKey: ForeignKeyInfo,
): RelationshipOptionality {
    val columns = foreignKey.columns.map { name -> table.columns.find { it.name == name } }
    return when {
        columns.any { it == null } -> RelationshipOptionality.Unknown
        columns.any { requireNotNull(it).nullable } -> RelationshipOptionality.Optional
        else -> RelationshipOptionality.Required
    }
}

data class IncomingForeignKey(
    val name: String,
    val sourceSchema: String,
    val sourceTable: String,
    val sourceColumns: List<String>,
    val referencedColumns: List<String>,
    val cardinality: RelationshipCardinality,
    val optionality: RelationshipOptionality,
)

fun incomingForeignKeysFor(
    tables: List<TableInfo>,
    schema: String,
    table: String,
): List<IncomingForeignKey> =
    tables
        .flatMap { source ->
            source.foreignKeys
                .filter { it.referencedSchema == schema && it.referencedTable == table }
                .map { foreignKey ->
                    IncomingForeignKey(
                        name = foreignKey.name,
                        sourceSchema = source.schema,
                        sourceTable = source.name,
                        sourceColumns = foreignKey.columns,
                        referencedColumns = foreignKey.referencedColumns,
                        cardinality = relationshipCardinality(source, foreignKey),
                        optionality = relationshipOptionality(source, foreignKey),
                    )
                }
        }
        .sortedWith(
            compareBy<IncomingForeignKey> { it.sourceSchema.lowercase() }
                .thenBy { it.sourceTable.lowercase() }
                .thenBy { it.name.lowercase() }
        )

data class CatalogTableMatch(
    val schema: String,
    val name: String,
    val qualifiedName: String,
    val sizeClass: TableSizeClass,
    val matchedColumns: List<String>,
    val matchedIndexes: List<String>,
    val matchedForeignKeys: List<String>,
)

fun searchCatalogTables(
    tables: List<TableInfo>,
    query: String,
    schemaFilter: String? = null,
): List<CatalogTableMatch> {
    val needle = query.trim().lowercase()
    val filtered =
        if (schemaFilter.isNullOrBlank()) {
            tables
        } else {
            tables.filter { it.schema == schemaFilter }
        }
    if (needle.isEmpty()) {
        return filtered.map { table ->
            CatalogTableMatch(
                schema = table.schema,
                name = table.name,
                qualifiedName = table.qualifiedName(),
                sizeClass = table.tableSize.sizeClass,
                matchedColumns = emptyList(),
                matchedIndexes = emptyList(),
                matchedForeignKeys = emptyList(),
            )
        }
    }

    return filtered.mapNotNull { table ->
        fun matches(value: String): Boolean = value.lowercase().contains(needle)

        val matchedColumns =
            table.columns.filter { matches(it.name) || matches(it.dataType) }.map { it.name }
        val matchedIndexes =
            table.indexes
                .filter { index -> matches(index.name) || index.keyColumnNames().any(::matches) }
                .map { it.name }
        val matchedForeignKeys =
            table.foreignKeys
                .filter { foreignKey ->
                    matches(foreignKey.name) ||
                        foreignKey.columns.any(::matches) ||
                        foreignKey.referencedColumns.any(::matches) ||
                        matches(foreignKey.referencedTable)
                }
                .map { it.name }
        val tableMatches =
            matches(table.schema) ||
                matches(table.name) ||
                matches(table.qualifiedName()) ||
                matchedColumns.isNotEmpty() ||
                matchedIndexes.isNotEmpty() ||
                matchedForeignKeys.isNotEmpty()
        if (!tableMatches) return@mapNotNull null
        CatalogTableMatch(
            schema = table.schema,
            name = table.name,
            qualifiedName = table.qualifiedName(),
            sizeClass = table.tableSize.sizeClass,
            matchedColumns = matchedColumns,
            matchedIndexes = matchedIndexes,
            matchedForeignKeys = matchedForeignKeys,
        )
    }
}

enum class JoinCandidateKind {
    ForeignKey,
    IndexedColumns,
}

data class JoinColumnPair(val leftColumn: String, val rightColumn: String)

data class JoinEdge(
    val fromSchema: String,
    val fromTable: String,
    val toSchema: String,
    val toTable: String,
    val kind: JoinCandidateKind,
    val columns: List<JoinColumnPair>,
    val foreignKeyName: String? = null,
    val cardinality: RelationshipCardinality? = null,
    val optionality: RelationshipOptionality? = null,
)

data class JoinPath(val steps: List<JoinEdge>)

fun findDirectJoinEdges(
    tables: List<TableInfo>,
    fromSchema: String,
    fromTable: String,
    includeIndexedColumns: Boolean = true,
): List<JoinEdge> {
    val from =
        tables.firstOrNull { it.schema == fromSchema && it.name == fromTable } ?: return emptyList()
    val byQualifiedName = tables.associateBy { it.schema to it.name }
    val edges = mutableListOf<JoinEdge>()
    val foreignKeyPairs = mutableSetOf<ForeignKeyPair>()

    for (foreignKey in from.foreignKeys) {
        if (!isCompleteForeignKey(foreignKey)) continue
        val target =
            byQualifiedName[foreignKey.referencedSchema to foreignKey.referencedTable] ?: continue
        val edge =
            foreignKeyJoinEdge(
                from = from,
                to = target,
                foreignKey = foreignKey,
                leftColumns = foreignKey.columns,
                rightColumns = foreignKey.referencedColumns,
                owner = from,
            ) ?: continue
        foreignKeyPairs += foreignKeyPairsFor(target, edge)
        edges += edge
    }

    for (source in tables) {
        if (source.schema == from.schema && source.name == from.name) continue
        for (foreignKey in source.foreignKeys) {
            if (!isCompleteForeignKey(foreignKey)) continue
            if (
                foreignKey.referencedSchema != from.schema ||
                    foreignKey.referencedTable != from.name
            ) {
                continue
            }
            val edge =
                foreignKeyJoinEdge(
                    from = from,
                    to = source,
                    foreignKey = foreignKey,
                    leftColumns = foreignKey.referencedColumns,
                    rightColumns = foreignKey.columns,
                    owner = source,
                ) ?: continue
            foreignKeyPairs += foreignKeyPairsFor(source, edge)
            edges += edge
        }
    }

    if (includeIndexedColumns) {
        edges += indexedColumnEdges(from, tables, foreignKeyPairs)
    }

    return edges.distinct()
}

private fun foreignKeyJoinEdge(
    from: TableInfo,
    to: TableInfo,
    foreignKey: ForeignKeyInfo,
    leftColumns: List<String>,
    rightColumns: List<String>,
    owner: TableInfo,
): JoinEdge? {
    val pairs = columnPairs(from, leftColumns, to, rightColumns) ?: return null
    return JoinEdge(
        fromSchema = from.schema,
        fromTable = from.name,
        toSchema = to.schema,
        toTable = to.name,
        kind = JoinCandidateKind.ForeignKey,
        columns = pairs,
        foreignKeyName = foreignKey.name,
        cardinality = relationshipCardinality(owner, foreignKey),
        optionality = relationshipOptionality(owner, foreignKey),
    )
}

private fun foreignKeyPairsFor(target: TableInfo, edge: JoinEdge): List<ForeignKeyPair> =
    edge.columns.map { ForeignKeyPair(target.schema, target.name, it) }

private fun indexedColumnEdges(
    from: TableInfo,
    tables: List<TableInfo>,
    foreignKeyPairs: Set<ForeignKeyPair>,
): List<JoinEdge> {
    val indexedByTable = linkedMapOf<Pair<String, String>, MutableList<JoinColumnPair>>()
    for (target in tables) {
        if (target.schema == from.schema && target.name == from.name) continue
        for (left in from.columns) {
            if (!left.joinEligible) continue
            for (right in target.columns) {
                if (!right.joinEligible) continue
                if (left.category != right.category) continue
                if (
                    !columnsCompatibleByConvention(
                        from.name,
                        left.name,
                        target.name,
                        right.name,
                    )
                ) {
                    continue
                }
                val pair = JoinColumnPair(left.name, right.name)
                if (ForeignKeyPair(target.schema, target.name, pair) in foreignKeyPairs) {
                    continue
                }
                indexedByTable.getOrPut(target.schema to target.name, ::mutableListOf).add(pair)
            }
        }
    }
    return indexedByTable.entries
        .sortedWith(
            compareBy<Map.Entry<Pair<String, String>, MutableList<JoinColumnPair>>> {
                    it.key.first.lowercase()
                }
                .thenBy { it.key.second.lowercase() }
        )
        .map { (key, pairs) ->
            val (schema, name) = key
            JoinEdge(
                fromSchema = from.schema,
                fromTable = from.name,
                toSchema = schema,
                toTable = name,
                kind = JoinCandidateKind.IndexedColumns,
                columns = pairs.distinct(),
            )
        }
}

private data class ForeignKeyPair(
    val targetSchema: String,
    val targetTable: String,
    val pair: JoinColumnPair,
)

fun findJoinPaths(
    tables: List<TableInfo>,
    fromSchema: String,
    fromTable: String,
    toSchema: String,
    toTable: String,
    maxHops: Int = 2,
    includeIndexedColumns: Boolean = true,
): List<JoinPath> {
    val hops = maxHops.coerceIn(1, 3)
    if (fromSchema == toSchema && fromTable == toTable) return emptyList()

    val edgeCache = mutableMapOf<Pair<String, String>, List<JoinEdge>>()
    fun edges(schema: String, table: String) =
        edgeCache.getOrPut(schema to table) {
            orderedEdges(tables, schema, table, includeIndexedColumns)
        }

    val paths = mutableListOf<JoinPath>()
    val queue = ArrayDeque<Pair<JoinPath, Set<Pair<String, String>>>>()
    val start = fromSchema to fromTable
    for (edge in edges(fromSchema, fromTable)) {
        val next = edge.toSchema to edge.toTable
        if (next == start) continue
        queue += JoinPath(listOf(edge)) to setOf(start, next)
    }

    while (queue.isNotEmpty()) {
        val (path, visited) = queue.removeFirst()
        val last = path.steps.last()
        if (path.steps.size > hops) continue
        if (last.toSchema == toSchema && last.toTable == toTable) {
            paths += path
            continue
        }
        if (path.steps.size == hops) continue
        for (edge in edges(last.toSchema, last.toTable)) {
            val next = edge.toSchema to edge.toTable
            if (next in visited) continue
            queue += JoinPath(path.steps + edge) to (visited + next)
        }
    }

    return paths
        .distinct()
        .sortedWith(
            compareBy<JoinPath> { it.steps.size }
                .thenBy { path ->
                    path.steps.joinToString("|") { "${it.toSchema}.${it.toTable}" }.lowercase()
                }
        )
        .take(10)
}

private fun orderedEdges(
    tables: List<TableInfo>,
    schema: String,
    table: String,
    includeIndexedColumns: Boolean,
): List<JoinEdge> =
    findDirectJoinEdges(tables, schema, table, includeIndexedColumns)
        .sortedWith(
            compareBy<JoinEdge> { edge ->
                    when (edge.kind) {
                        JoinCandidateKind.ForeignKey -> 0
                        JoinCandidateKind.IndexedColumns -> 1
                    }
                }
                .thenBy { it.toSchema.lowercase() }
                .thenBy { it.toTable.lowercase() }
        )

private fun isCompleteForeignKey(foreignKey: ForeignKeyInfo): Boolean =
    foreignKey.columns.isNotEmpty() && foreignKey.columns.size == foreignKey.referencedColumns.size

private fun columnPairs(
    left: TableInfo,
    leftColumns: List<String>,
    right: TableInfo,
    rightColumns: List<String>,
): List<JoinColumnPair>? {
    val pairs = mutableListOf<JoinColumnPair>()
    for ((leftName, rightName) in leftColumns.zip(rightColumns)) {
        val leftColumn = left.columns.find { it.name == leftName } ?: return null
        val rightColumn = right.columns.find { it.name == rightName } ?: return null
        if (leftColumn.category != rightColumn.category) return null
        pairs += JoinColumnPair(leftName, rightName)
    }
    return pairs
}

private fun columnsCompatibleByConvention(
    leftTable: String,
    leftColumn: String,
    rightTable: String,
    rightColumn: String,
): Boolean {
    val left = leftColumn.lowercase()
    val right = rightColumn.lowercase()
    // A generic "id" column only identifies a row within its own table; matching id = id across
    // tables would invent an edge that carries no meaning.
    if (left == "id" && right == "id") return false
    if (left == right) return true
    if (left == "id" && right == "${leftTable.lowercase()}_id") return true
    if (right == "id" && left == "${rightTable.lowercase()}_id") return true
    return false
}

private fun exactIndexKeyColumns(index: IndexInfo): List<String>? =
    if (index.keys.isNotEmpty()) {
        index.keys
            .takeUnless { keys -> keys.any { it.expression || it.column == null } }
            ?.map { requireNotNull(it.column) }
    } else {
        index.columns
    }
