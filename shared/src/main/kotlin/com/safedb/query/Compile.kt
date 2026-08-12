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

    val fetchLimit = spec.limit + 1
    val sql = buildString {
        append("SELECT ")
        if (spec.distinct) {
            append("DISTINCT ")
        }
        if (dialect == Dialect.Mssql) {
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
        when (dialect) {
            Dialect.Mssql -> Unit
            Dialect.Oracle -> {
                append("\nFETCH FIRST $fetchLimit ROWS ONLY")
            }
            else -> {
                append("\nLIMIT $fetchLimit")
            }
        }
    }

    return Outcome.ok(CompiledQuery(sql = sql, params = params.toList()))
}
