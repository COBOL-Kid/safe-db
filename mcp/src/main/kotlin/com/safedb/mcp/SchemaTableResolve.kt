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
    val matches = tables.filter {
        it.schema.equals(schemaName, ignoreCase = true) &&
            it.name.equals(tableName, ignoreCase = true)
    }
    return pickTable(matches, unqualifiedReference = null, allTables = tables)
}

internal fun resolveUnqualifiedTable(
    reference: String,
    tables: List<TableInfo>,
): Arg<TableInfo> {
    val exact = tables.filter { it.name == reference }
    if (exact.isNotEmpty()) {
        return pickTable(exact, reference, tables)
    }
    val matches = tables.filter { it.name.equals(reference, ignoreCase = true) }
    return pickTable(matches, reference, tables)
}

private fun pickTable(
    candidates: List<TableInfo>,
    unqualifiedReference: String?,
    allTables: List<TableInfo>,
): Arg<TableInfo> =
    when {
        candidates.isEmpty() -> Arg.Err(tableNotFound(allTables))
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

internal fun tableNotFound(tables: List<TableInfo>): CallToolResult {
    val suggestions =
        tables.map { it.qualifiedName() }.sortedWith(String.CASE_INSENSITIVE_ORDER).take(5)
    val message =
        if (suggestions.isEmpty()) {
            "Table not found"
        } else {
            "Table not found. Suggested tables: ${suggestions.joinToString(", ")}"
        }
    return toolError(ERROR_NOT_FOUND, message)
}
