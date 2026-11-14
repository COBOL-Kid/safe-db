package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.model.JoinSpec
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.SortDirection
import com.safedb.model.TableRef

// Renders a QuerySpec as the literal SQL the builder describes. Unlike compileValidated, values
// are inlined instead of bound, so the result is meant for inspection and clipboard use; it still
// round-trips through parseSqlToSpec for the supported shapes.
//
// Case-insensitive predicates (ILIKE / ContainsIgnoreCase) emit `ILIKE` on Postgres and `LIKE`
// elsewhere so clipboard SQL continues to round-trip through parseSqlToSpec, which only accepts
// ILIKE on Postgres. Bound compilation uses dialect-specific LOWER/UPPER LIKE rewrites instead.
fun specToSql(spec: QuerySpec, dialect: Dialect): Outcome<String> {
    val rowLimit = if (spec.uncapped) null else spec.limit.toLong()
    val where =
        when (
            val result =
                renderFilterTree(
                    spec.filters,
                    spec.connectorOverrides,
                    dialect,
                    DisplayFilterSqlStrategy,
                    wrap = false,
                )
        ) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return result
        }
    return Outcome.ok(
        buildString {
            append("SELECT ")
            if (spec.distinct) append("DISTINCT ")
            append(rowLimitSelectPrefix(dialect, rowLimit))
            append(renderProjection(spec, dialect))

            if (spec.tables.isNotEmpty()) {
                append("\nFROM ")
                append(renderTableRef(spec.tables.first(), dialect))
                append(renderJoins(spec, dialect))
            }
            if (where.isNotEmpty()) {
                append("\nWHERE ")
                append(where)
            }
            if (spec.groups.isNotEmpty()) {
                append("\nGROUP BY ")
                append(
                    spec.groups.joinToString(", ") {
                        renderColumn(it.tableAlias, it.column, dialect)
                    },
                )
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
                    },
                )
            }
            append(rowLimitSuffix(dialect, rowLimit))
        },
    )
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
    val separator = tableAliasSeparator(dialect)
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
