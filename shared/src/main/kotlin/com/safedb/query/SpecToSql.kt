package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QuerySpec
import com.safedb.model.SortDirection
import com.safedb.model.TableRef
import com.safedb.model.parseBoolLiteral
import com.safedb.model.sqlOperator

// Renders a QuerySpec as the literal SQL the builder describes. Unlike compileValidated, values
// are inlined instead of bound, so the result is meant for inspection and clipboard use; it still
// round-trips through parseSqlToSpec for the supported shapes.
fun specToSql(spec: QuerySpec, dialect: Dialect): String = buildString {
    append("SELECT ")
    if (spec.distinct) append("DISTINCT ")
    if (dialect == Dialect.Mssql && !spec.uncapped) append("TOP ${spec.limit} ")
    append(renderProjection(spec, dialect))

    if (spec.tables.isNotEmpty()) {
        append("\nFROM ")
        append(renderTableRef(spec.tables.first(), dialect))
        append(renderJoins(spec, dialect))
    }
    val where = renderFilterGroup(spec.filters, spec.connectorOverrides, dialect, wrap = false)
    if (where.isNotEmpty()) {
        append("\nWHERE ")
        append(where)
    }
    if (spec.groups.isNotEmpty()) {
        append("\nGROUP BY ")
        append(spec.groups.joinToString(", ") { renderColumn(it.tableAlias, it.column, dialect) })
    }
    if (spec.sorts.isNotEmpty()) {
        append("\nORDER BY ")
        append(
            spec.sorts.joinToString(", ") { sort ->
                val direction =
                    when (sort.direction) {
                        SortDirection.Asc -> "ASC"
                        SortDirection.Desc -> "DESC"
                    }
                "${renderColumn(sort.tableAlias, sort.column, dialect)} $direction"
            }
        )
    }
    if (!spec.uncapped) {
        when (dialect) {
            // SQL Server places TOP before the select list.
            Dialect.Mssql -> Unit
            Dialect.Oracle,
            Dialect.Db2 -> append("\nFETCH FIRST ${spec.limit} ROWS ONLY")
            Dialect.Postgres,
            Dialect.MySql -> append("\nLIMIT ${spec.limit}")
        }
    }
}

private fun renderProjection(spec: QuerySpec, dialect: Dialect): String {
    if (spec.columns.isEmpty()) return "*"
    return spec.columns.joinToString(", ") { renderColumn(it.tableAlias, it.column, dialect) }
}

private fun renderColumn(tableAlias: String, column: String, dialect: Dialect): String =
    "${quoteIfRequired(tableAlias, dialect)}.${quoteIfRequired(column, dialect)}"

private fun renderTableRef(table: TableRef, dialect: Dialect): String {
    val relation =
        "${quoteIfRequired(table.schema, dialect)}.${quoteIfRequired(table.name, dialect)}"
    val alias = quoteIfRequired(table.alias, dialect)
    val separator =
        when (dialect) {
            // Oracle rejects AS between a table and its alias.
            Dialect.Oracle -> " "
            Dialect.Postgres,
            Dialect.MySql,
            Dialect.Mssql,
            Dialect.Db2 -> " AS "
        }
    return "$relation$separator$alias"
}

private fun renderJoins(spec: QuerySpec, dialect: Dialect): String {
    val steps = walkJoinOrder(spec)
    if (steps.isEmpty()) return ""
    return steps.joinToString(separator = "\n", prefix = "\n") { step ->
        val conditions =
            step.conditions.joinToString(" AND ") { join -> renderJoinCondition(join, dialect) }
        "INNER JOIN ${renderTableRef(step.table, dialect)} ON $conditions"
    }
}

private fun renderJoinCondition(join: JoinSpec, dialect: Dialect): String =
    "${renderColumn(join.leftAlias, join.leftColumn, dialect)} = " +
        renderColumn(join.rightAlias, join.rightColumn, dialect)

private fun renderFilterGroup(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    wrap: Boolean,
): String {
    if (group.children.isEmpty()) return ""
    val parts = mutableListOf<Pair<Int, String>>()
    group.children.forEachIndexed { index, child ->
        val rendered = renderFilterNode(child, overrides, dialect)
        if (rendered.isNotEmpty()) parts += index to rendered
    }
    if (parts.isEmpty()) return ""
    val joined = buildString {
        append(parts.first().second)
        for ((index, part) in parts.drop(1)) {
            val connector = overrides[filterNodeId(group.children[index])] ?: group.connector
            append(
                when (connector) {
                    GroupConnector.And -> " AND "
                    GroupConnector.Or -> " OR "
                }
            )
            append(part)
        }
    }
    return if (wrap && parts.size > 1) "($joined)" else joined
}

private fun renderFilterNode(
    node: FilterNode,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
): String =
    when (node) {
        is FilterNode.Leaf -> renderFilter(node.spec, dialect)
        is FilterNode.Group -> renderFilterGroup(node.group, overrides, dialect, wrap = true)
    }

private fun renderFilter(filter: FilterSpec, dialect: Dialect): String {
    val column = renderColumn(filter.tableAlias, filter.column, dialect)
    return when {
        filter.op.isFriendlyPattern() -> {
            val literal = (filter.value as? FilterValue.Single)?.literal ?: return ""
            val pattern = friendlyPatternText(filter.op, literal.text)
            val operator =
                when (filter.op) {
                    FilterOp.NotContains -> "NOT LIKE"
                    FilterOp.ContainsIgnoreCase ->
                        if (dialect == Dialect.Postgres) "ILIKE" else "LIKE"
                    else -> "LIKE"
                }
            "$column $operator ${quoteText(pattern)} ESCAPE '!'"
        }
        filter.op.sqlOperator() != null -> {
            val literal = (filter.value as? FilterValue.Single)?.literal ?: return ""
            "$column ${filter.op.sqlOperator()} ${renderLiteral(literal)}"
        }
        filter.op == FilterOp.Ilike -> {
            val literal = (filter.value as? FilterValue.Single)?.literal ?: return ""
            val operator = if (dialect == Dialect.Postgres) "ILIKE" else "LIKE"
            "$column $operator ${renderLiteral(literal)}"
        }
        filter.op == FilterOp.In || filter.op == FilterOp.NotIn -> {
            val literals =
                (filter.value as? FilterValue.ListValue)?.literals?.map(::renderLiteral).orEmpty()
            when {
                literals.isEmpty() -> if (filter.op == FilterOp.In) "1=0" else "1=1"
                else ->
                    "$column ${if (filter.op == FilterOp.In) "IN" else "NOT IN"} " +
                        "(${literals.joinToString(", ")})"
            }
        }
        filter.op == FilterOp.Between -> {
            val pair = filter.value as? FilterValue.Pair ?: return ""
            "$column BETWEEN ${renderLiteral(pair.first)} AND ${renderLiteral(pair.second)}"
        }
        filter.op == FilterOp.IsNull -> "$column IS NULL"
        filter.op == FilterOp.IsNotNull -> "$column IS NOT NULL"
        filter.op == FilterOp.IsEmpty -> "$column = ''"
        filter.op == FilterOp.IsNotEmpty -> "$column <> ''"
        else -> ""
    }
}

private fun FilterOp.isFriendlyPattern(): Boolean =
    this in
        setOf(
            FilterOp.Contains,
            FilterOp.ContainsIgnoreCase,
            FilterOp.NotContains,
            FilterOp.StartsWith,
            FilterOp.EndsWith,
        )

private fun renderLiteral(literal: FilterLiteral): String =
    when (literal.kind) {
        LiteralKind.Text,
        LiteralKind.Date,
        LiteralKind.DateTime -> quoteText(literal.text)
        LiteralKind.Int,
        LiteralKind.Decimal,
        LiteralKind.Float -> literal.text
        LiteralKind.Bool -> if (parseBoolLiteral(literal.text) == true) "TRUE" else "FALSE"
    }

private fun quoteText(text: String): String = "'${text.replace("'", "''")}'"
