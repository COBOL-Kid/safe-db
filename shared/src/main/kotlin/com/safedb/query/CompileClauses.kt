package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.GroupConnector
import com.safedb.model.JoinSpec
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.TableRef

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
): Outcome<String> =
    renderFilterTree(group, overrides, dialect, BoundFilterSqlStrategy(params), wrap = false)
