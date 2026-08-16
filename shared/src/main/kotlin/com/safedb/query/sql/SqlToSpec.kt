package com.safedb.query.sql

import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.ValueKind
import com.safedb.model.valueKind
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.literalKindForColumn

fun parseSqlToSpec(
    sql: String,
    dialect: Dialect,
    schema: Schema,
    defaultSchema: String?,
): SqlParseResult {
    val ast =
        when (val result = parseSqlStatement(sql, dialect)) {
            is SqlStatementResult.Fail -> return SqlParseResult.Failure(listOf(result.issue))
            is SqlStatementResult.Ok -> result.ast
        }
    return try {
        SqlSpecBuilder(schema, defaultSchema, ast).build()
    } catch (e: SqlParseException) {
        SqlParseResult.Failure(listOf(e.issue))
    }
}

private class ResolvedTable(val info: TableInfo, val alias: String)

private class SqlSpecBuilder(
    private val schema: Schema,
    private val defaultSchema: String?,
    private val ast: SqlSelectAst,
) {
    private val tables = mutableListOf<ResolvedTable>()
    private var leafCounter = 0
    private var groupCounter = 0

    fun build(): SqlParseResult {
        val notes = mutableListOf<String>()

        resolveTable(ast.from)
        val joinSpecs = mutableListOf<JoinSpec>()
        for (join in ast.joins) {
            resolveTable(join.table)
            for ((left, right) in join.conditions) {
                val leftRef = resolveColumn(left)
                val rightRef = resolveColumn(right)
                joinSpecs.add(
                    JoinSpec(
                        leftAlias = leftRef.first,
                        leftColumn = leftRef.second.name,
                        rightAlias = rightRef.first,
                        rightColumn = rightRef.second.name,
                    )
                )
            }
        }

        val columns = resolveSelectItems()
        val filters = ast.where?.let(::toFilterGroup) ?: FilterGroup(id = nextGroupId())
        val groups =
            ast.groupBy.map { ref ->
                val (alias, column) = resolveColumn(ref)
                GroupSpec(tableAlias = alias, column = column.name)
            }
        val sorts =
            ast.orderBy.map { (ref, direction) ->
                val (alias, column) = resolveColumn(ref)
                SortSpec(tableAlias = alias, column = column.name, direction = direction)
            }
        val limit =
            ast.limit
                ?: DEFAULT_LIMIT.also { notes.add("No LIMIT specified — defaulting to $it rows.") }

        val spec =
            QuerySpec(
                tables =
                    tables.map {
                        TableRef(schema = it.info.schema, name = it.info.name, alias = it.alias)
                    },
                columns = columns,
                joins = joinSpecs,
                filters = filters,
                limit = limit,
                distinct = ast.distinct,
                sorts = sorts,
                groups = groups,
            )
        return SqlParseResult.Success(spec, notes)
    }

    private fun resolveTable(ref: SqlTableRefAst) {
        val schemaName =
            if (ref.schema != null) {
                resolveSchemaName(ref.schema)
            } else {
                defaultSchema
                    ?: fail(SqlIssueCode.SchemaRequired, SqlMessages.SCHEMA_REQUIRED, ref.name.span)
            }
        val candidates = schema.tables.filter { it.schema == schemaName }
        if (candidates.isEmpty()) {
            fail(SqlIssueCode.UnknownSchema, "Schema '$schemaName' has no tables.", ref.span)
        }
        val info =
            candidates.find { it.name == ref.name.name }
                ?: candidates
                    .filter { it.name.equals(ref.name.name, ignoreCase = true) }
                    .let { matches ->
                        when (matches.size) {
                            1 -> matches[0]
                            0 ->
                                fail(
                                    SqlIssueCode.UnknownTable,
                                    "Table '${ref.name.name}' not found in schema '$schemaName'.",
                                    ref.name.span,
                                )
                            else ->
                                fail(
                                    SqlIssueCode.UnknownTable,
                                    "'${ref.name.name}' matches several tables — quote the exact name.",
                                    ref.name.span,
                                )
                        }
                    }
        val alias = ref.alias?.name ?: info.name
        if (tables.any { it.alias == alias }) {
            fail(SqlIssueCode.DuplicateAlias, "Alias '$alias' is used more than once.", ref.span)
        }
        tables.add(ResolvedTable(info, alias))
    }

    private fun resolveSchemaName(ident: SqlIdent): String {
        val names = schema.tables.map { it.schema }.distinct()
        names
            .find { it == ident.name }
            ?.let {
                return it
            }
        val ciMatches = names.filter { it.equals(ident.name, ignoreCase = true) }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 -> fail(SqlIssueCode.UnknownSchema, "Schema '${ident.name}' not found.", ident.span)
            else ->
                fail(
                    SqlIssueCode.UnknownSchema,
                    "'${ident.name}' matches several schemas — quote the exact name.",
                    ident.span,
                )
        }
    }

    private fun resolveSelectItems(): List<ColumnSel> {
        val items = ast.items ?: return emptyList()
        return items.flatMap { item ->
            when (item) {
                is SqlSelectItem.TableStar -> {
                    val table = resolveQualifier(item.qualifier)
                    table.info.columns.map { column ->
                        ColumnSel(tableAlias = table.alias, column = column.name)
                    }
                }
                is SqlSelectItem.Column -> {
                    val (alias, column) = resolveColumn(item.ref)
                    listOf(ColumnSel(tableAlias = alias, column = column.name))
                }
            }
        }
    }

    private fun resolveQualifier(ident: SqlIdent): ResolvedTable {
        tables
            .find { it.alias == ident.name }
            ?.let {
                return it
            }
        tables
            .find { it.info.name == ident.name }
            ?.let {
                return it
            }
        val ciMatches = tables.filter {
            it.alias.equals(ident.name, ignoreCase = true) ||
                it.info.name.equals(ident.name, ignoreCase = true)
        }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 ->
                fail(
                    SqlIssueCode.UnknownTable,
                    "'${ident.name}' doesn't match a table or alias in this query.",
                    ident.span,
                )
            else ->
                fail(
                    SqlIssueCode.UnknownTable,
                    "'${ident.name}' matches several tables in this query.",
                    ident.span,
                )
        }
    }

    private fun resolveColumn(ref: SqlColumnRefAst): Pair<String, ColumnInfo> {
        if (ref.qualifier != null) {
            val table = resolveQualifier(ref.qualifier)
            val column = findColumn(table.info, ref.name)
            return table.alias to column
        }
        val exact = tables.mapNotNull { table ->
            table.info.columns.find { it.name == ref.name.name }?.let { table.alias to it }
        }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) {
            fail(
                SqlIssueCode.AmbiguousColumn,
                "'${ref.name.name}' exists in several tables — qualify it as table.column.",
                ref.name.span,
            )
        }
        val ci = tables.mapNotNull { table ->
            table.info.columns
                .filter { it.name.equals(ref.name.name, ignoreCase = true) }
                .let { matches -> if (matches.size == 1) table.alias to matches[0] else null }
        }
        return when (ci.size) {
            1 -> ci[0]
            0 ->
                fail(
                    SqlIssueCode.UnknownColumn,
                    "Column '${ref.name.name}' not found in the referenced tables.",
                    ref.name.span,
                )
            else ->
                fail(
                    SqlIssueCode.AmbiguousColumn,
                    "'${ref.name.name}' exists in several tables — qualify it as table.column.",
                    ref.name.span,
                )
        }
    }

    private fun findColumn(table: TableInfo, ident: SqlIdent): ColumnInfo {
        table.columns
            .find { it.name == ident.name }
            ?.let {
                return it
            }
        val ciMatches = table.columns.filter { it.name.equals(ident.name, ignoreCase = true) }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 ->
                fail(
                    SqlIssueCode.UnknownColumn,
                    "Column '${ident.name}' not found in table '${table.name}'.",
                    ident.span,
                )
            else ->
                fail(
                    SqlIssueCode.UnknownColumn,
                    "'${ident.name}' matches several columns — quote the exact name.",
                    ident.span,
                )
        }
    }

    private fun toFilterGroup(condition: SqlConditionAst): FilterGroup =
        when (condition) {
            is SqlConditionAst.Or ->
                FilterGroup(
                    id = nextGroupId(),
                    connector = GroupConnector.Or,
                    children = condition.children.map(::toFilterNode),
                )
            is SqlConditionAst.And ->
                FilterGroup(
                    id = nextGroupId(),
                    connector = GroupConnector.And,
                    children = condition.children.map(::toFilterNode),
                )
            is SqlConditionAst.Predicate ->
                FilterGroup(id = nextGroupId(), children = listOf(toFilterNode(condition)))
        }

    private fun toFilterNode(condition: SqlConditionAst): FilterNode =
        when (condition) {
            is SqlConditionAst.Or,
            is SqlConditionAst.And -> FilterNode.Group(toFilterGroup(condition))
            is SqlConditionAst.Predicate -> FilterNode.Leaf(toFilterSpec(condition))
        }

    private fun toFilterSpec(predicate: SqlConditionAst.Predicate): FilterSpec {
        val (alias, column) = resolveColumn(predicate.column)
        val kind = literalKindForColumn(column.dataType)
        val literals = predicate.values.map { FilterLiteral(kind, it.raw) }
        val value =
            when (predicate.op.valueKind()) {
                ValueKind.None -> null
                ValueKind.Single -> FilterValue.Single(literals[0])
                ValueKind.List -> FilterValue.ListValue(literals)
                ValueKind.Pair -> FilterValue.Pair(literals[0], literals[1])
            }
        return FilterSpec(
            id = nextLeafId(),
            tableAlias = alias,
            column = column.name,
            op = predicate.op,
            value = value,
        )
    }

    // Deterministic ids: re-parsing identical text must yield an equal QuerySpec, because pending
    // confirmations and the Explore handoff compare specs structurally.
    private fun nextGroupId(): String = "g${groupCounter++}"

    private fun nextLeafId(): String = "f${leafCounter++}"

    private fun fail(code: SqlIssueCode, message: String, span: SqlSpan?): Nothing =
        throw SqlParseException(SqlIssue(code, message, span))
}
