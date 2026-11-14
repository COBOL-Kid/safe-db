package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.parseBoolLiteral
import com.safedb.model.sqlOperator

private val LIKE_ESCAPE_OPS = setOf(FilterOp.Like, FilterOp.NotLike, FilterOp.Ilike)

internal interface FilterSqlStrategy {
    fun columnRef(tableAlias: String, column: String, dialect: Dialect): String

    fun emitLiteral(literal: FilterLiteral, dialect: Dialect): Outcome<String>

    fun emitFriendlyPattern(op: FilterOp, literal: FilterLiteral, dialect: Dialect): Outcome<String>

    fun caseInsensitivePredicate(columnRef: String, operand: String, dialect: Dialect): String
}

internal class BoundFilterSqlStrategy(private val params: MutableList<BindValue>) :
    FilterSqlStrategy {
    override fun columnRef(tableAlias: String, column: String, dialect: Dialect): String =
        "${quote(tableAlias, dialect)}.${quote(column, dialect)}"

    override fun emitLiteral(literal: FilterLiteral, dialect: Dialect): Outcome<String> {
        val bound =
            when (val result = bindLiteral(literal)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        params.add(bound)
        return Outcome.ok(placeholder(params.size, dialect))
    }

    override fun emitFriendlyPattern(
        op: FilterOp,
        literal: FilterLiteral,
        dialect: Dialect,
    ): Outcome<String> {
        val bound =
            when (val result = bindLiteral(literal)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        val text =
            (bound as? BindValue.Text)?.value
                ?: return Outcome.err("${opLabel(op)} expects a text value")
        params.add(BindValue.Text(friendlyPatternText(op, text)))
        return Outcome.ok(placeholder(params.size, dialect))
    }

    override fun caseInsensitivePredicate(
        columnRef: String,
        operand: String,
        dialect: Dialect,
    ): String = buildIlike(columnRef, operand, dialect)
}

internal object DisplayFilterSqlStrategy : FilterSqlStrategy {
    override fun columnRef(tableAlias: String, column: String, dialect: Dialect): String =
        "${quoteIfRequired(tableAlias, dialect)}.${quoteIfRequired(column, dialect)}"

    override fun emitLiteral(literal: FilterLiteral, dialect: Dialect): Outcome<String> {
        when (val result = bindLiteral(literal)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> return result
        }
        return Outcome.ok(formatInlineLiteral(literal))
    }

    override fun emitFriendlyPattern(
        op: FilterOp,
        literal: FilterLiteral,
        dialect: Dialect,
    ): Outcome<String> {
        val bound =
            when (val result = bindLiteral(literal)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        val text =
            (bound as? BindValue.Text)?.value
                ?: return Outcome.err("${opLabel(op)} expects a text value")
        return Outcome.ok(quoteText(friendlyPatternText(op, text)))
    }

    override fun caseInsensitivePredicate(
        columnRef: String,
        operand: String,
        dialect: Dialect,
    ): String {
        val operator = if (dialect == Dialect.Postgres) "ILIKE" else "LIKE"
        return "$columnRef $operator $operand"
    }
}

internal fun renderFilterTree(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    strategy: FilterSqlStrategy,
    wrap: Boolean = false,
): Outcome<String> {
    if (group.children.isEmpty()) {
        return Outcome.ok("")
    }

    val parts = mutableListOf<Pair<Int, String>>()
    for ((i, child) in group.children.withIndex()) {
        when (val result = renderFilterNode(child, overrides, dialect, strategy)) {
            is Outcome.Ok -> {
                if (result.value.isNotEmpty()) {
                    parts.add(i to result.value)
                }
            }
            is Outcome.Err -> return result
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

private fun renderFilterNode(
    node: FilterNode,
    overrides: Map<String, GroupConnector>,
    dialect: Dialect,
    strategy: FilterSqlStrategy,
): Outcome<String> =
    when (node) {
        is FilterNode.Leaf -> renderFilterLeaf(node.spec, dialect, strategy)
        is FilterNode.Group ->
            renderFilterTree(node.group, overrides, dialect, strategy, wrap = true)
    }

private fun renderFilterLeaf(
    filter: FilterSpec,
    dialect: Dialect,
    strategy: FilterSqlStrategy,
): Outcome<String> {
    val columnRef = strategy.columnRef(filter.tableAlias, filter.column, dialect)

    if (filter.op.isFriendlyTextPattern()) {
        val literal =
            (filter.value as? FilterValue.Single)?.literal
                ?: return Outcome.err("${opLabel(filter.op)} expects a single text value")
        val operand =
            when (val result = strategy.emitFriendlyPattern(filter.op, literal, dialect)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        val predicate =
            if (filter.op == FilterOp.ContainsIgnoreCase) {
                strategy.caseInsensitivePredicate(columnRef, operand, dialect)
            } else {
                "$columnRef ${friendlyPatternOperator(filter.op)} $operand"
            }
        return Outcome.ok("$predicate $LIKE_ESCAPE_CLAUSE")
    }

    val sqlOp = filter.op.sqlOperator()
    if (sqlOp != null) {
        val literal =
            when (val result = singleLiteral(filter, "Operator '$sqlOp' expects a single value")) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        val operand =
            when (val result = strategy.emitLiteral(literal, dialect)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return result
            }
        return Outcome.ok(withLikeEscape("$columnRef $sqlOp $operand", filter))
    }

    return when (filter.op) {
        FilterOp.Ilike -> {
            val literal =
                when (val result = singleLiteral(filter, "ILIKE expects a single value")) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> return result
                }
            val operand =
                when (val result = strategy.emitLiteral(literal, dialect)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> return result
                }
            Outcome.ok(
                withLikeEscape(
                    strategy.caseInsensitivePredicate(columnRef, operand, dialect),
                    filter,
                ),
            )
        }
        FilterOp.In,
        FilterOp.NotIn -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on ${filter.tableAlias}.${filter.column} is missing its value",
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
                when (val result = strategy.emitLiteral(lit, dialect)) {
                    is Outcome.Ok -> phs.add(result.value)
                    is Outcome.Err -> return result
                }
            }
            val kw = if (filter.op == FilterOp.In) "IN" else "NOT IN"
            Outcome.ok("$columnRef $kw (${phs.joinToString(", ")})")
        }
        FilterOp.Between -> {
            val value =
                filter.value
                    ?: return Outcome.err(
                        "Filter on ${filter.tableAlias}.${filter.column} is missing its value",
                    )
            val (from, to) =
                when (value) {
                    is FilterValue.Pair -> value.first to value.second
                    else -> return Outcome.err("BETWEEN expects a pair of values")
                }
            val ph1 =
                when (val result = strategy.emitLiteral(from, dialect)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> return result
                }
            val ph2 =
                when (val result = strategy.emitLiteral(to, dialect)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> return result
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

private fun singleLiteral(filter: FilterSpec, wrongShape: String): Outcome<FilterLiteral> {
    val value =
        filter.value
            ?: return Outcome.err(
                "Filter on ${filter.tableAlias}.${filter.column} is missing its value",
            )
    return when (value) {
        is FilterValue.Single -> Outcome.ok(value.literal)
        else -> Outcome.err(wrongShape)
    }
}

private fun withLikeEscape(sql: String, filter: FilterSpec): String =
    if (filter.likeEscape && filter.op in LIKE_ESCAPE_OPS) "$sql $LIKE_ESCAPE_CLAUSE" else sql

private fun bindLiteral(literal: FilterLiteral): Outcome<BindValue> =
    BindValue.fromLiteral(literal)
        .fold(
            onSuccess = { Outcome.ok(it) },
            onFailure = { Outcome.err(it.message ?: "Invalid literal") },
        )

private fun formatInlineLiteral(literal: FilterLiteral): String =
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
