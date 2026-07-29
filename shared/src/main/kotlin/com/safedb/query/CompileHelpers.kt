package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.sqlOperator

internal fun quote(ident: String, dialect: Dialect): String = when (dialect) {
    Dialect.Postgres, Dialect.Oracle -> "\"${ident.replace("\"", "\"\"")}\""
    Dialect.MySql -> "`${ident.replace("`", "``")}`"
    Dialect.Mssql -> "[${ident.replace("]", "]]")}]"
}

internal fun placeholder(idx: Int, dialect: Dialect): String = when (dialect) {
    Dialect.Postgres -> "$$idx"
    Dialect.MySql -> "?"
    Dialect.Mssql -> "@P$idx"
    Dialect.Oracle -> ":$idx"
}

internal fun buildSelectClause(
    spec: QuerySpec,
    dialect: Dialect,
    validatedColumns: List<ValidatedColumn>?,
): String {
    if (validatedColumns != null) {
        return validatedColumns.joinToString(", ") { column ->
            "${quote(column.tableAlias, dialect)}.${quote(column.column, dialect)} AS ${quote(column.resultAlias, dialect)}"
        }
    }
    if (spec.columns.isEmpty()) {
        return "*"
    }
    return spec.columns.joinToString(", ") { col ->
        "${quote(col.tableAlias, dialect)}.${quote(col.column, dialect)}"
    }
}

internal fun buildFromClause(spec: QuerySpec, dialect: Dialect): String {
    if (spec.tables.isEmpty()) return ""
    val table = spec.tables[0]
    return "${quote(table.schema, dialect)}.${quote(table.name, dialect)} AS ${quote(table.alias, dialect)}"
}

internal fun buildJoinClause(spec: QuerySpec, dialect: Dialect): String {
    if (spec.tables.size <= 1) return ""

    val included = mutableSetOf(spec.tables[0].alias)
    val remaining = spec.tables.drop(1).map { it.alias }.toMutableList()
    val clauses = mutableListOf<String>()

    while (remaining.isNotEmpty()) {
        var found: Pair<Int, String>? = null
        for ((i, alias) in remaining.withIndex()) {
            for (join in spec.joins) {
                val left = join.leftAlias
                val right = join.rightAlias
                if ((left == alias && included.contains(right)) || (right == alias && included.contains(left))) {
                    found = i to alias
                    break
                }
            }
            if (found != null) break
        }

        val (idx, alias) = found ?: break
        remaining.removeAt(idx)
        included.add(alias)

        val tableRef = spec.tables.first { it.alias == alias }
        val joinTarget = buildString {
            append("INNER JOIN ")
            append(quote(tableRef.schema, dialect))
            append('.')
            append(quote(tableRef.name, dialect))
            append(" AS ")
            append(quote(tableRef.alias, dialect))
        }

        val onClause = spec.joins
            .filter { candidate ->
                (candidate.leftAlias == alias && included.contains(candidate.rightAlias)) ||
                    (candidate.rightAlias == alias && included.contains(candidate.leftAlias))
            }
            .joinToString(" AND ") { candidate ->
                "${quote(candidate.leftAlias, dialect)}.${quote(candidate.leftColumn, dialect)} = " +
                    "${quote(candidate.rightAlias, dialect)}.${quote(candidate.rightColumn, dialect)}"
            }

        clauses.add("$joinTarget ON $onClause")
    }

    return clauses.joinToString("\n")
}

internal fun buildOrderByClause(spec: QuerySpec, dialect: Dialect): String =
    spec.sorts.joinToString(", ") { sort ->
        val direction = when (sort.direction) {
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
    paramIdx: Int,
): Outcome<Pair<String, Int>> =
    joinChildren(group, overrides, dialect, params, paramIdx, wrap = false)

private fun joinChildren(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
    paramIdx: Int,
    wrap: Boolean,
): Outcome<Pair<String, Int>> {
    if (group.children.isEmpty()) {
        return Outcome.ok("" to paramIdx)
    }

    var currentIdx = paramIdx
    val parts = mutableListOf<Pair<Int, String>>()
    for ((i, child) in group.children.withIndex()) {
        when (val result = buildWhereNode(child, overrides, dialect, params, currentIdx)) {
            is Outcome.Ok -> {
                currentIdx = result.value.second
                if (result.value.first.isNotEmpty()) {
                    parts.add(i to result.value.first)
                }
            }
            is Outcome.Err -> return Outcome.err(result.message)
        }
    }

    if (parts.isEmpty()) {
        return Outcome.ok("" to currentIdx)
    }

    val joined = buildString {
        append(parts[0].second)
        for ((origI, part) in parts.drop(1)) {
            val child = group.children[origI]
            val connector = overrides[childId(child)] ?: group.connector
            append(connectorSql(connector))
            append(part)
        }
    }

    val rendered = if (wrap && parts.size > 1) "($joined)" else joined
    return Outcome.ok(rendered to currentIdx)
}

private fun childId(node: FilterNode): String = when (node) {
    is FilterNode.Leaf -> node.spec.id
    is FilterNode.Group -> node.group.id
}

private fun connectorSql(connector: GroupConnector): String = when (connector) {
    GroupConnector.And -> " AND "
    GroupConnector.Or -> " OR "
}

private fun buildWhereNode(
    node: FilterNode,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
    paramIdx: Int,
): Outcome<Pair<String, Int>> = when (node) {
    is FilterNode.Leaf -> buildLeaf(node.spec, dialect, params, paramIdx)
    is FilterNode.Group -> buildGroup(node.group, overrides, dialect, params, paramIdx)
}

private fun buildGroup(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    params: MutableList<BindValue>,
    paramIdx: Int,
): Outcome<Pair<String, Int>> =
    joinChildren(group, overrides, dialect, params, paramIdx, wrap = true)

private fun bindLiteral(literal: com.safedb.model.FilterLiteral): Outcome<BindValue> =
    BindValue.fromLiteral(literal).fold(
        onSuccess = { Outcome.ok(it) },
        onFailure = { Outcome.err(it.message ?: "Invalid literal") },
    )

private fun buildLeaf(
    filter: FilterSpec,
    dialect: Dialect,
    params: MutableList<BindValue>,
    paramIdx: Int,
): Outcome<Pair<String, Int>> {
    val columnRef = "${quote(filter.tableAlias, dialect)}.${quote(filter.column, dialect)}"
    var currentIdx = paramIdx

    if (filter.op.isFriendlyTextPattern()) {
        val literal = (filter.value as? FilterValue.Single)?.literal
            ?: return Outcome.err("${opLabel(filter.op)} expects a single text value")
        val bound = when (val result = bindLiteral(literal)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return Outcome.err(result.message)
        }
        val text = (bound as? BindValue.Text)?.value
            ?: return Outcome.err("${opLabel(filter.op)} expects a text value")
        val ph = placeholder(currentIdx, dialect)
        currentIdx += 1
        params.add(BindValue.Text(friendlyPatternText(filter.op, text)))
        val predicate = if (filter.op == FilterOp.ContainsIgnoreCase) {
            buildIlike(columnRef, ph, dialect)
        } else {
            "$columnRef ${friendlyPatternOperator(filter.op)} $ph"
        }
        return Outcome.ok("$predicate ESCAPE '!'" to currentIdx)
    }

    val sqlOp = filter.op.sqlOperator()
    if (sqlOp != null) {
        val value = filter.value
            ?: return Outcome.err("Filter on ${filter.tableAlias}.${filter.column} is missing its value")
        val lit = when (value) {
            is FilterValue.Single -> value.literal
            else -> return Outcome.err("Operator '$sqlOp' expects a single value")
        }
        val ph = placeholder(currentIdx, dialect)
        currentIdx += 1
        when (val bind = bindLiteral(lit)) {
            is Outcome.Ok -> params.add(bind.value)
            is Outcome.Err -> return Outcome.err(bind.message)
        }
        return Outcome.ok("$columnRef $sqlOp $ph" to currentIdx)
    }

    return when (filter.op) {
        FilterOp.Ilike -> {
            val value = filter.value
                ?: return Outcome.err("Filter on ${filter.tableAlias}.${filter.column} is missing its value")
            val lit = when (value) {
                is FilterValue.Single -> value.literal
                else -> return Outcome.err("ILIKE expects a single value")
            }
            val ph = placeholder(currentIdx, dialect)
            currentIdx += 1
            when (val bind = bindLiteral(lit)) {
                is Outcome.Ok -> params.add(bind.value)
                is Outcome.Err -> return Outcome.err(bind.message)
            }
            Outcome.ok(buildIlike(columnRef, ph, dialect) to currentIdx)
        }
        FilterOp.In, FilterOp.NotIn -> {
            val value = filter.value
                ?: return Outcome.err("Filter on ${filter.tableAlias}.${filter.column} is missing its value")
            val list = when (value) {
                is FilterValue.ListValue -> value.literals
                else -> return Outcome.err("IN expects a list of values")
            }
            if (list.isEmpty()) {
                val sql = if (filter.op == FilterOp.In) "1=0" else "1=1"
                return Outcome.ok(sql to currentIdx)
            }
            val phs = mutableListOf<String>()
            for (lit in list) {
                val ph = placeholder(currentIdx, dialect)
                currentIdx += 1
                when (val bind = bindLiteral(lit)) {
                    is Outcome.Ok -> params.add(bind.value)
                    is Outcome.Err -> return Outcome.err(bind.message)
                }
                phs.add(ph)
            }
            val kw = if (filter.op == FilterOp.In) "IN" else "NOT IN"
            Outcome.ok("$columnRef $kw (${phs.joinToString(", ")})" to currentIdx)
        }
        FilterOp.Between -> {
            val value = filter.value
                ?: return Outcome.err("Filter on ${filter.tableAlias}.${filter.column} is missing its value")
            val (from, to) = when (value) {
                is FilterValue.Pair -> value.first to value.second
                else -> return Outcome.err("BETWEEN expects a pair of values")
            }
            val ph1 = placeholder(currentIdx, dialect)
            currentIdx += 1
            when (val bind1 = bindLiteral(from)) {
                is Outcome.Ok -> params.add(bind1.value)
                is Outcome.Err -> return Outcome.err(bind1.message)
            }
            val ph2 = placeholder(currentIdx, dialect)
            currentIdx += 1
            when (val bind2 = bindLiteral(to)) {
                is Outcome.Ok -> params.add(bind2.value)
                is Outcome.Err -> return Outcome.err(bind2.message)
            }
            Outcome.ok("$columnRef BETWEEN $ph1 AND $ph2" to currentIdx)
        }
        FilterOp.IsNull -> Outcome.ok("$columnRef IS NULL" to currentIdx)
        FilterOp.IsNotNull -> Outcome.ok("$columnRef IS NOT NULL" to currentIdx)
        FilterOp.IsEmpty -> Outcome.ok("$columnRef = ''" to currentIdx)
        FilterOp.IsNotEmpty -> Outcome.ok("$columnRef <> ''" to currentIdx)
        else -> Outcome.err("Unsupported operator in compiler")
    }
}

private fun FilterOp.isFriendlyTextPattern(): Boolean = this in setOf(
    FilterOp.Contains,
    FilterOp.ContainsIgnoreCase,
    FilterOp.NotContains,
    FilterOp.StartsWith,
    FilterOp.EndsWith,
)

private fun friendlyPatternOperator(op: FilterOp): String = when (op) {
    FilterOp.NotContains -> "NOT LIKE"
    FilterOp.Contains, FilterOp.StartsWith, FilterOp.EndsWith -> "LIKE"
    else -> error("Not a friendly text pattern: $op")
}

private fun friendlyPatternText(op: FilterOp, text: String): String {
    val escaped = escapeLikeLiteral(text)
    return when (op) {
        FilterOp.Contains, FilterOp.ContainsIgnoreCase, FilterOp.NotContains -> "%$escaped%"
        FilterOp.StartsWith -> "$escaped%"
        FilterOp.EndsWith -> "%$escaped"
        else -> error("Not a friendly text pattern: $op")
    }
}

private fun escapeLikeLiteral(text: String): String = text
    .replace("!", "!!")
    .replace("%", "!%")
    .replace("_", "!_")

private fun buildIlike(columnRef: String, ph: String, dialect: Dialect): String = when (dialect) {
    Dialect.Postgres -> "$columnRef ILIKE $ph"
    Dialect.Mssql, Dialect.MySql -> "LOWER($columnRef) LIKE LOWER($ph)"
    Dialect.Oracle -> "UPPER($columnRef) LIKE UPPER($ph)"
}
