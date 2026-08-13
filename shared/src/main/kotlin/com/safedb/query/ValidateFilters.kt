package com.safedb.query

import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.ValueKind
import com.safedb.model.parseBoolLiteral
import com.safedb.model.parseDateLiteral
import com.safedb.model.parseDateTimeLiteral
import com.safedb.model.valueKind
import java.math.BigDecimal

internal fun collectNodeIds(group: FilterGroup, nodeIds: MutableSet<String>): Outcome<Unit> {
    when (val result = insertNodeId(group.id, nodeIds)) {
        is Outcome.Ok -> Unit
        is Outcome.Err -> return result
    }
    for (child in group.children) {
        when (child) {
            is FilterNode.Leaf ->
                when (val result = insertNodeId(child.spec.id, nodeIds)) {
                    is Outcome.Ok -> Unit
                    is Outcome.Err -> return result
                }
            is FilterNode.Group ->
                when (val result = collectNodeIds(child.group, nodeIds)) {
                    is Outcome.Ok -> Unit
                    is Outcome.Err -> return result
                }
        }
    }
    return Outcome.ok(Unit)
}

internal fun validateNode(
    node: FilterNode,
    schema: Schema,
    spec: QuerySpec,
    tableAliases: Set<String>,
    depth: Int,
    warnings: MutableList<String>,
): Outcome<Unit> {
    if (depth > MAX_FILTER_DEPTH) {
        return Outcome.err("Filter nesting exceeds maximum depth of $MAX_FILTER_DEPTH")
    }
    return when (node) {
        is FilterNode.Leaf -> validateLeaf(node.spec, schema, spec, tableAliases, warnings)
        is FilterNode.Group ->
            validateGroup(node.group, schema, spec, tableAliases, depth, warnings)
    }
}

internal fun validateLeaf(
    filter: FilterSpec,
    schema: Schema,
    spec: QuerySpec,
    tableAliases: Set<String>,
    warnings: MutableList<String>,
): Outcome<Unit> {
    if (!tableAliases.contains(filter.tableAlias)) {
        return Outcome.err("Filter references unknown table alias '${filter.tableAlias}'")
    }

    val table =
        findTableByAlias(schema, spec, filter.tableAlias)
            ?: return Outcome.err("Cannot resolve table for alias '${filter.tableAlias}'")

    val col =
        table.columns.find { it.name == filter.column }
            ?: return Outcome.err(
                "Filter column '${filter.tableAlias}.${filter.column}' does not exist"
            )

    val allowed = validOpsForColumn(col.dataType)
    if (!allowed.contains(filter.op)) {
        return Outcome.err(
            "Operator '${opLabel(filter.op)}' is not applicable to column '${filter.tableAlias}.${filter.column}' (type: ${col.dataType})"
        )
    }

    if (!col.isIndexed) {
        val warning =
            "This query may scan more data than expected because it searches the non-indexed field '${table.name}.${col.name}'. Safe DB will still use the row limit and timeout."
        if (!warnings.contains(warning)) {
            warnings.add(warning)
        }
    }

    return when (filter.op.valueKind()) {
        ValueKind.None -> {
            if (filter.value != null) {
                Outcome.err(
                    "Operator '${opLabel(filter.op)}' on '${filter.tableAlias}.${filter.column}' should not have a value"
                )
            } else {
                Outcome.ok(Unit)
            }
        }
        ValueKind.Single -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' requires a value"
                    )
            val lit =
                expectSingle(value)
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' expects a single value"
                    )
            validateLiteral(lit, col.dataType, filter.tableAlias, filter.column)
        }
        ValueKind.List -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' requires a value list"
                    )
            val list =
                expectList(value)
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' expects a list of values"
                    )
            if (list.size > MAX_IN_LIST_SIZE) {
                return Outcome.err(
                    "Filter on '${filter.tableAlias}.${filter.column}' has too many values (max $MAX_IN_LIST_SIZE)"
                )
            }
            for (lit in list) {
                when (
                    val result =
                        validateLiteral(lit, col.dataType, filter.tableAlias, filter.column)
                ) {
                    is Outcome.Ok -> Unit
                    is Outcome.Err -> return result
                }
            }
            Outcome.ok(Unit)
        }
        ValueKind.Pair -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' requires a range (from/to)"
                    )
            val pair =
                expectPair(value)
                    ?: return Outcome.err(
                        "Filter on '${filter.tableAlias}.${filter.column}' expects a range (from/to)"
                    )
            when (
                val fromResult =
                    validateLiteral(pair.first, col.dataType, filter.tableAlias, filter.column)
            ) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> return fromResult
            }
            validateLiteral(pair.second, col.dataType, filter.tableAlias, filter.column)
        }
    }
}

internal fun validateGroup(
    group: FilterGroup,
    schema: Schema,
    spec: QuerySpec,
    tableAliases: Set<String>,
    depth: Int,
    warnings: MutableList<String>,
): Outcome<Unit> {
    if (depth > 0 && group.children.isEmpty()) {
        warnings.add("Filter group has no conditions")
    }
    if (depth > 0 && group.children.size == 1 && group.children[0] is FilterNode.Leaf) {
        warnings.add("Filter group has only one condition — a group is unnecessary")
    }
    for (child in group.children) {
        when (val result = validateNode(child, schema, spec, tableAliases, depth + 1, warnings)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> return result
        }
    }
    return Outcome.ok(Unit)
}

internal fun validateLiteral(
    lit: FilterLiteral,
    dataType: String,
    alias: String,
    column: String,
): Outcome<Unit> {
    val expected = literalKindForColumn(dataType)
    if (lit.kind != expected) {
        return Outcome.err(
            "Value for '$alias.$column' has type ${lit.kind}; expected $expected for column type $dataType"
        )
    }
    return when (lit.kind) {
        LiteralKind.Text -> {
            if (lit.text.length > MAX_TEXT_LITERAL_LEN) {
                Outcome.err(
                    "Text value for '$alias.$column' exceeds maximum length of $MAX_TEXT_LITERAL_LEN characters"
                )
            } else {
                Outcome.ok(Unit)
            }
        }
        LiteralKind.Int -> {
            lit.text.toLongOrNull()?.let { Outcome.ok(Unit) }
                ?: Outcome.err("'${lit.text}' is not a valid integer for '$alias.$column'")
        }
        LiteralKind.Decimal -> {
            try {
                BigDecimal(lit.text)
                Outcome.ok(Unit)
            } catch (_: Exception) {
                Outcome.err("'${lit.text}' is not a valid decimal for '$alias.$column'")
            }
        }
        LiteralKind.Float -> {
            lit.text.toDoubleOrNull()?.let { Outcome.ok(Unit) }
                ?: Outcome.err("'${lit.text}' is not a valid number for '$alias.$column'")
        }
        LiteralKind.Bool -> {
            parseBoolLiteral(lit.text)?.let { Outcome.ok(Unit) }
                ?: Outcome.err("'${lit.text}' is not a valid boolean for '$alias.$column'")
        }
        LiteralKind.Date -> {
            parseDateLiteral(lit.text)
                .fold(
                    onSuccess = { Outcome.ok(Unit) },
                    onFailure = { Outcome.err("${it.message} for '$alias.$column'") },
                )
        }
        LiteralKind.DateTime -> {
            parseDateTimeLiteral(lit.text)
                .fold(
                    onSuccess = { Outcome.ok(Unit) },
                    onFailure = { Outcome.err("${it.message} for '$alias.$column'") },
                )
        }
    }
}

private fun insertNodeId(id: String, nodeIds: MutableSet<String>): Outcome<Unit> {
    if (id.trim().isEmpty()) {
        return Outcome.err("Every filter node must have a non-empty stable id")
    }
    if (!nodeIds.add(id)) {
        return Outcome.err("Duplicate filter node id '$id'")
    }
    return Outcome.ok(Unit)
}

private fun expectSingle(value: FilterValue): FilterLiteral? =
    when (value) {
        is FilterValue.Single -> value.literal
        else -> null
    }

private fun expectList(value: FilterValue): List<FilterLiteral>? =
    when (value) {
        is FilterValue.ListValue -> value.literals
        else -> null
    }

private fun expectPair(value: FilterValue): Pair<FilterLiteral, FilterLiteral>? =
    when (value) {
        is FilterValue.Pair -> value.first to value.second
        else -> null
    }

fun opLabel(op: FilterOp): String =
    when (op) {
        FilterOp.Eq -> "="
        FilterOp.Ne -> "<>"
        FilterOp.Gt -> ">"
        FilterOp.Gte -> ">="
        FilterOp.Lt -> "<"
        FilterOp.Lte -> "<="
        FilterOp.Contains -> "contains"
        FilterOp.ContainsIgnoreCase -> "contains (case-insensitive)"
        FilterOp.NotContains -> "does not contain"
        FilterOp.StartsWith -> "starts with"
        FilterOp.EndsWith -> "ends with"
        FilterOp.Like -> "LIKE"
        FilterOp.NotLike -> "NOT LIKE"
        FilterOp.Ilike -> "ILIKE"
        FilterOp.In -> "IN"
        FilterOp.NotIn -> "NOT IN"
        FilterOp.Between -> "BETWEEN"
        FilterOp.IsNull -> "IS NULL"
        FilterOp.IsNotNull -> "IS NOT NULL"
        FilterOp.IsEmpty -> "IS EMPTY"
        FilterOp.IsNotEmpty -> "IS NOT EMPTY"
    }

internal fun checkJoinConnectivity(spec: QuerySpec): Boolean {
    if (spec.tables.size <= 1) return true

    val connected = mutableSetOf(spec.tables[0].alias)
    var changed = true
    while (changed) {
        changed = false
        for ((left, _, right, _) in spec.joins) {
            if (connected.contains(left) && !connected.contains(right)) {
                connected.add(right)
                changed = true
            } else if (connected.contains(right) && !connected.contains(left)) {
                connected.add(left)
                changed = true
            }
        }
    }
    return connected.size == spec.tables.size
}

internal fun isBlocked(schema: String, custom: List<String>): Boolean =
    BLOCKED_SCHEMAS.any { it.equals(schema, ignoreCase = true) } ||
        custom.any { it.equals(schema, ignoreCase = true) }
