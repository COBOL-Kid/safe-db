package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.JoinSpec
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.TableRef
import com.safedb.model.sqlOperator

internal fun buildSelectClause(columns: List<ValidatedColumn>, dialect: Dialect): String =
    columns.joinToString(", ") { column ->
        "${quote(column.tableAlias, dialect)}.${quote(column.column, dialect)} AS ${quote(column.resultAlias, dialect)}"
    }

internal fun buildFromClause(spec: QuerySpec, dialect: Dialect): String {
    if (spec.tables.isEmpty()) return ""
    return quotedTableAlias(spec.tables[0], dialect)
}

internal fun buildJoinClause(spec: QuerySpec, dialect: Dialect): String =
    walkJoinOrder(spec).joinToString("\n") { step ->
        val joinTarget = "INNER JOIN ${quotedTableAlias(step.table, dialect)}"
        val onClause =
            step.conditions.joinToString(" AND ") { candidate ->
                "${quote(candidate.leftAlias, dialect)}.${quote(candidate.leftColumn, dialect)} = " +
                    "${quote(candidate.rightAlias, dialect)}.${quote(candidate.rightColumn, dialect)}"
            }
        "$joinTarget ON $onClause"
    }

// One joinable table in dependency order, with the ON conditions that connect it to the tables
// already in scope. Shared by the bound compiler and the clipboard renderer so both emit the same
// join order; each caller keeps its own identifier quoting.
internal data class JoinStep(val table: TableRef, val conditions: List<JoinSpec>)

internal fun walkJoinOrder(spec: QuerySpec): List<JoinStep> {
    if (spec.tables.size <= 1) return emptyList()

    val included = mutableSetOf(spec.tables.first().alias)
    val remaining = spec.tables.drop(1).map { it.alias }.toMutableList()
    val steps = mutableListOf<JoinStep>()

    while (remaining.isNotEmpty()) {
        val alias =
            remaining.firstOrNull { candidate ->
                spec.joins.any { join ->
                    (join.leftAlias == candidate && join.rightAlias in included) ||
                        (join.rightAlias == candidate && join.leftAlias in included)
                }
            } ?: break
        remaining.remove(alias)
        included += alias

        val table = spec.tables.first { it.alias == alias }
        val conditions =
            spec.joins.filter { join ->
                (join.leftAlias == alias && join.rightAlias in included) ||
                    (join.rightAlias == alias && join.leftAlias in included)
            }
        steps += JoinStep(table, conditions)
    }

    return steps
}

// Oracle rejects table-alias AS (ORA-03048); column AS remains valid.
private fun quotedTableAlias(table: TableRef, dialect: Dialect): String {
    val relation = "${quote(table.schema, dialect)}.${quote(table.name, dialect)}"
    val alias = quote(table.alias, dialect)
    val separator = tableAliasSeparator(dialect)
    return "$relation$separator$alias"
}

internal fun buildOrderByClause(spec: QuerySpec, dialect: Dialect): String =
    spec.sorts.joinToString(", ") { sort ->
        val direction =
            when (sort.direction) {
                com.safedb.model.SortDirection.Asc -> "ASC"
                com.safedb.model.SortDirection.Desc -> "DESC"
            }
        "${quote(sort.tableAlias, dialect)}.${quote(sort.column, dialect)} $direction"
    }

internal fun buildGroupByClause(spec: QuerySpec, dialect: Dialect): String =
    spec.groups.joinToString(", ") { group ->
        "${quote(group.tableAlias, dialect)}.${quote(group.column, dialect)}"
    }

internal fun buildWhereRoot(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
): Outcome<String> = joinChildren(group, overrides, dialect, params, wrap = false)

private fun joinChildren(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
    wrap: Boolean,
): Outcome<String> {
    if (group.children.isEmpty()) {
        return Outcome.ok("")
    }

    val parts = mutableListOf<Pair<Int, String>>()
    for ((i, child) in group.children.withIndex()) {
        when (val result = buildWhereNode(child, overrides, dialect, params)) {
            is Outcome.Ok -> {
                if (result.value.isNotEmpty()) {
                    parts.add(i to result.value)
                }
            }
            is Outcome.Err -> return Outcome.err(result.message)
        }
    }

    if (parts.isEmpty()) {
        return Outcome.ok("")
    }

    val joined = buildString {
        append(parts[0].second)
        for ((origI, part) in parts.drop(1)) {
            val child = group.children[origI]
            append(connectorSql(childConnector(overrides, group, child)))
            append(part)
        }
    }

    return Outcome.ok(if (wrap && parts.size > 1) "($joined)" else joined)
}

private fun buildWhereNode(
    node: FilterNode,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
): Outcome<String> =
    when (node) {
        is FilterNode.Leaf -> buildLeaf(node.spec, dialect, params)
        is FilterNode.Group -> joinChildren(node.group, overrides, dialect, params, wrap = true)
    }

private fun bindLiteral(literal: com.safedb.model.FilterLiteral): Outcome<BindValue> =
    BindValue.fromLiteral(literal)
        .fold(
            onSuccess = { Outcome.ok(it) },
            onFailure = { Outcome.err(it.message ?: "Invalid literal") },
        )

private fun buildLeaf(
    filter: FilterSpec,
    dialect: Dialect,
    params: MutableList<BindValue>,
): Outcome<String> {
    val columnRef = "${quote(filter.tableAlias, dialect)}.${quote(filter.column, dialect)}"
    // Every placeholder must be rendered from the bind list as it stands before its own value is
    // appended, so placeholder numbering stays one-to-one with the bound parameters.
    fun nextPlaceholder(): String = placeholder(params.size + 1, dialect)

    if (filter.op.isFriendlyTextPattern()) {
        val literal =
            (filter.value as? FilterValue.Single)?.literal
                ?: return Outcome.err("${opLabel(filter.op)} expects a single text value")
        val bound =
            when (val result = bindLiteral(literal)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return Outcome.err(result.message)
            }
        val text =
            (bound as? BindValue.Text)?.value
                ?: return Outcome.err("${opLabel(filter.op)} expects a text value")
        val ph = nextPlaceholder()
        params.add(BindValue.Text(friendlyPatternText(filter.op, text)))
        val predicate =
            if (filter.op == FilterOp.ContainsIgnoreCase) {
                buildIlike(columnRef, ph, dialect)
            } else {
                "$columnRef ${friendlyPatternOperator(filter.op)} $ph"
            }
        return Outcome.ok("$predicate ESCAPE '!'")
    }

    val sqlOp = filter.op.sqlOperator()
    if (sqlOp != null) {
        val value =
            filter.value
                ?: return Outcome.err(
                    "Filter on ${filter.tableAlias}.${filter.column} is missing its value"
                )
        val lit =
            when (value) {
                is FilterValue.Single -> value.literal
                else -> return Outcome.err("Operator '$sqlOp' expects a single value")
            }
        val ph = nextPlaceholder()
        when (val bind = bindLiteral(lit)) {
            is Outcome.Ok -> params.add(bind.value)
            is Outcome.Err -> return Outcome.err(bind.message)
        }
        return Outcome.ok("$columnRef $sqlOp $ph")
    }

    return when (filter.op) {
        FilterOp.Ilike -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on ${filter.tableAlias}.${filter.column} is missing its value"
                    )
            val lit =
                when (value) {
                    is FilterValue.Single -> value.literal
                    else -> return Outcome.err("ILIKE expects a single value")
                }
            val ph = nextPlaceholder()
            when (val bind = bindLiteral(lit)) {
                is Outcome.Ok -> params.add(bind.value)
                is Outcome.Err -> return Outcome.err(bind.message)
            }
            Outcome.ok(buildIlike(columnRef, ph, dialect))
        }
        FilterOp.In,
        FilterOp.NotIn -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on ${filter.tableAlias}.${filter.column} is missing its value"
                    )
            val list =
                when (value) {
                    is FilterValue.ListValue -> value.literals
                    else -> return Outcome.err("IN expects a list of values")
                }
            if (list.isEmpty()) {
                return Outcome.ok(if (filter.op == FilterOp.In) "1=0" else "1=1")
            }
            val phs = mutableListOf<String>()
            for (lit in list) {
                val ph = nextPlaceholder()
                when (val bind = bindLiteral(lit)) {
                    is Outcome.Ok -> params.add(bind.value)
                    is Outcome.Err -> return Outcome.err(bind.message)
                }
                phs.add(ph)
            }
            val kw = if (filter.op == FilterOp.In) "IN" else "NOT IN"
            Outcome.ok("$columnRef $kw (${phs.joinToString(", ")})")
        }
        FilterOp.Between -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on ${filter.tableAlias}.${filter.column} is missing its value"
                    )
            val (from, to) =
                when (value) {
                    is FilterValue.Pair -> value.first to value.second
                    else -> return Outcome.err("BETWEEN expects a pair of values")
                }
            val ph1 = nextPlaceholder()
            when (val bind1 = bindLiteral(from)) {
                is Outcome.Ok -> params.add(bind1.value)
                is Outcome.Err -> return Outcome.err(bind1.message)
            }
            val ph2 = nextPlaceholder()
            when (val bind2 = bindLiteral(to)) {
                is Outcome.Ok -> params.add(bind2.value)
                is Outcome.Err -> return Outcome.err(bind2.message)
            }
            Outcome.ok("$columnRef BETWEEN $ph1 AND $ph2")
        }
        FilterOp.IsNull -> Outcome.ok("$columnRef IS NULL")
        FilterOp.IsNotNull -> Outcome.ok("$columnRef IS NOT NULL")
        FilterOp.IsEmpty -> Outcome.ok("$columnRef = ''")
        FilterOp.IsNotEmpty -> Outcome.ok("$columnRef <> ''")
        else -> Outcome.err("Unsupported operator in compiler")
    }
}
