package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.Outcome

fun compileValidated(validated: ValidatedQuery, dialect: Dialect): Outcome<CompiledQuery> {
    val spec = validated.spec()
    val params = mutableListOf<BindValue>()

    val selectClause = buildSelectClause(validated.columns(), dialect)
    val fromClause = buildFromClause(spec, dialect)
    val joinClause = buildJoinClause(spec, dialect)
    val whereClause =
        when (
            val result =
                buildWhereRoot(
                    group = spec.filters,
                    overrides = spec.connectorOverrides,
                    dialect = dialect,
                    params = params,
                )
        ) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return Outcome.err(result.message)
        }
    val orderByClause = buildOrderByClause(spec, dialect)
    val groupByClause = buildGroupByClause(spec, dialect)

    // Below the maximum cap, fetch one extra row so a full cap is distinguishable from a short
    // result. The maximum itself is not incremented, and an uncapped query has no row-limit clause.
    val fetchLimit =
        when {
            spec.uncapped -> null
            spec.limit < Int.MAX_VALUE -> spec.limit.toLong() + 1
            else -> spec.limit.toLong()
        }
    val sql = buildString {
        append("SELECT ")
        if (spec.distinct) {
            append("DISTINCT ")
        }
        if (dialect == Dialect.Mssql && fetchLimit != null) {
            append("TOP $fetchLimit ")
        }
        append(selectClause)
        append('\n')
        append("FROM ")
        append(fromClause)
        if (joinClause.isNotEmpty()) {
            append('\n')
            append(joinClause)
        }
        if (whereClause.isNotEmpty()) {
            append('\n')
            append("WHERE ")
            append(whereClause)
        }
        if (groupByClause.isNotEmpty()) {
            append("\nGROUP BY ")
            append(groupByClause)
        }
        if (orderByClause.isNotEmpty()) {
            append("\nORDER BY ")
            append(orderByClause)
        }
        if (fetchLimit != null) {
            when (dialect) {
                Dialect.Mssql -> Unit
                Dialect.Oracle,
                Dialect.Db2 -> {
                    append("\nFETCH FIRST $fetchLimit ROWS ONLY")
                }
                else -> {
                    append("\nLIMIT $fetchLimit")
                }
            }
        }
    }

    return Outcome.ok(CompiledQuery(sql = sql, params = params.toList()))
}
