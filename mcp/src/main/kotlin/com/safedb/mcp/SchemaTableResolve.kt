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
    tables
        .find { it.schema == schemaName && it.name == tableName }
        ?.let {
            return Arg.Ok(it)
        }
    val matches = tables.filter {
        it.schema.equals(schemaName, ignoreCase = true) &&
            it.name.equals(tableName, ignoreCase = true)
    }
    return when (matches.size) {
        1 -> Arg.Ok(matches.single())
        0 -> Arg.Err(tableNotFound(tables))
        else -> Arg.Err(ambiguousTableError(matches))
    }
}

internal fun resolveUnqualifiedTable(
    reference: String,
    tables: List<TableInfo>,
): Arg<TableInfo> {
    val exact = tables.filter { it.name == reference }
    when {
        exact.size == 1 -> return Arg.Ok(exact.single())
        exact.size > 1 -> {
            if (exact.map { it.qualifiedName() }.distinct().size == 1) {
                return Arg.Ok(exact.first())
            }
            return Arg.Err(defaultSchemaRequired(reference))
        }
    }
    val matches = tables.filter { it.name.equals(reference, ignoreCase = true) }
    return when (matches.size) {
        1 -> Arg.Ok(matches.single())
        0 -> Arg.Err(tableNotFound(tables))
        else ->
            Arg.Err(
                if (sameTableIgnoringCase(matches)) ambiguousTableError(matches)
                else defaultSchemaRequired(reference)
            )
    }
}

internal fun sameTableIgnoringCase(tables: List<TableInfo>): Boolean =
    tables.map { it.qualifiedName().lowercase() }.distinct().size == 1

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
