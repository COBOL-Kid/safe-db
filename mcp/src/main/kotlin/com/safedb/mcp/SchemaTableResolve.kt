package com.safedb.mcp

import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult

internal fun resolveTableReference(
    reference: String,
    defaultSchema: String?,
    tables: List<TableInfo>,
): Arg<TableInfo> {
    val dot = reference.indexOf('.')
    if (dot >= 0) {
        val schema = reference.substring(0, dot).trim()
        val name = reference.substring(dot + 1).trim()
        if (schema.isEmpty() || name.isEmpty()) {
            return Arg.Err(
                toolError(ERROR_INVALID_ARGUMENTS, "table names must be schema.table or table")
            )
        }
        return resolveTable(schema, name, tables)
    }
    if (defaultSchema != null) {
        return resolveTable(defaultSchema, reference, tables)
    }
    return resolveUnqualifiedTable(reference, tables)
}

internal fun resolveTable(
    schemaName: String,
    tableName: String,
    tables: List<TableInfo>,
): Arg<TableInfo> {
    val matches =
        matchExactThenIgnoreCase(
            tables,
            exactPredicate = { it.schema == schemaName && it.name == tableName },
            ignoreCasePredicate = {
                it.schema.equals(schemaName, ignoreCase = true) &&
                    it.name.equals(tableName, ignoreCase = true)
            },
        )
    return pickTable(
        matches,
        unqualifiedReference = null,
        allTables = tables,
        requested = "$schemaName.$tableName",
    )
}

internal fun resolveUnqualifiedTable(
    reference: String,
    tables: List<TableInfo>,
): Arg<TableInfo> {
    val matches =
        matchExactThenIgnoreCase(
            tables,
            exactPredicate = { it.name == reference },
            ignoreCasePredicate = { it.name.equals(reference, ignoreCase = true) },
        )
    return pickTable(matches, reference, tables, requested = reference)
}

private fun matchExactThenIgnoreCase(
    tables: List<TableInfo>,
    exactPredicate: (TableInfo) -> Boolean,
    ignoreCasePredicate: (TableInfo) -> Boolean,
): List<TableInfo> {
    val exact = tables.filter(exactPredicate)
    return exact.ifEmpty { tables.filter(ignoreCasePredicate) }
}

private fun pickTable(
    candidates: List<TableInfo>,
    unqualifiedReference: String?,
    allTables: List<TableInfo>,
    requested: String,
): Arg<TableInfo> =
    when {
        candidates.isEmpty() -> Arg.Err(tableNotFound(requested, allTables))
        candidates.size == 1 -> Arg.Ok(candidates.single())
        unqualifiedReference != null &&
            candidates.map { it.qualifiedName().lowercase() }.distinct().size > 1 ->
            Arg.Err(defaultSchemaRequired(unqualifiedReference))
        else -> Arg.Err(ambiguousTableError(candidates))
    }

internal fun defaultSchemaRequired(reference: String): CallToolResult =
    toolError(
        ERROR_INVALID_ARGUMENTS,
        "default_schema is required to disambiguate '$reference'",
    )

internal fun ambiguousTableError(tables: List<TableInfo>): CallToolResult =
    toolError(
        ERROR_INVALID_ARGUMENTS,
        "Table reference is ambiguous: " +
            tables
                .map { it.qualifiedName() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .joinToString(", "),
    )

internal fun tableNotFound(requested: String, tables: List<TableInfo>): CallToolResult {
    val suggestions = similarTableNames(requested, tables)
    val message =
        if (suggestions.isEmpty()) {
            "Table not found"
        } else {
            "Table not found. Suggested tables: ${suggestions.joinToString(", ")}"
        }
    return toolError(ERROR_NOT_FOUND, message)
}

internal fun similarTableNames(
    requested: String,
    tables: List<TableInfo>,
    limit: Int = 5,
): List<String> {
    val query = requested.trim()
    if (query.isEmpty()) return emptyList()
    val bare = query.substringAfterLast('.').ifBlank { query }
    return tables
        .asSequence()
        .mapNotNull { table ->
            // Rank by the table name only. Comparing the full schema.table string would
            // treat a shared schema prefix as similarity and suggest unrelated tables.
            if (!isSimilarTableName(bare, table.name)) return@mapNotNull null
            RankedTable(table.qualifiedName(), tableNameDistance(bare, table.name))
        }
        .sortedWith(
            compareBy<RankedTable> { it.distance }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        .map { it.name }
        .distinct()
        .take(limit)
        .toList()
}

private data class RankedTable(val name: String, val distance: Int)

private fun isSimilarTableName(query: String, candidate: String): Boolean {
    val q = query.lowercase()
    val c = candidate.lowercase()
    return (q.length >= 2 && (c.startsWith(q) || (q.startsWith(c) && c.length >= 2))) ||
        tableNameDistance(q, c) <= maxOf(2, q.length / 3)
}

private fun tableNameDistance(left: String, right: String): Int {
    val a = left.lowercase()
    val b = right.lowercase()
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + substitution)
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return previous[b.length]
}
