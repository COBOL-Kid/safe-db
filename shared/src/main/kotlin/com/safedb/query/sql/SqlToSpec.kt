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
import com.safedb.model.LiteralKind
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.ValueKind
import com.safedb.model.parseBoolLiteral
import com.safedb.model.parseDateLiteral
import com.safedb.model.parseDateTimeLiteral
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
        SqlSpecBuilder(dialect, schema, defaultSchema, ast).build()
    } catch (e: SqlParseException) {
        SqlParseResult.Failure(listOf(e.issue))
    }
}

private class ResolvedTable(val info: TableInfo, val alias: String)

private class SqlSpecBuilder(
    private val dialect: Dialect,
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
            val joined = resolveTable(join.table)
            for ((left, right) in join.conditions) {
                val leftRef = resolveColumn(left)
                val rightRef = resolveColumn(right)
                // Every ON conjunct becomes an edge in the builder's join graph, and
                // buildJoinClause
                // only emits edges touching the alias being introduced. A conjunct that links two
                // other aliases — or an alias to itself — would be dropped from the compiled SQL
                // and
                // silently widen the result set, so reject it here instead.
                val linksJoinedTable =
                    (leftRef.first == joined.alias) != (rightRef.first == joined.alias)
                if (!linksJoinedTable) {
                    fail(SqlIssueCode.Unsupported, SqlMessages.JOIN_EDGE, left.span)
                }
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

    // --- Identifier resolution -------------------------------------------------------------
    //
    // One policy, applied everywhere. A quoted identifier means exactly what it spells, so it only
    // ever matches verbatim. An unquoted one is folded by the database before lookup — Postgres
    // lowercases, Oracle uppercases, MySQL and SQL Server compare case-insensitively — so the
    // folded
    // spelling is tried first and a case-insensitive sweep only serves as a fallback. Every tier
    // fails on more than one match rather than binding whichever object happens to come first.

    private fun foldUnquoted(name: String): String =
        when (dialect) {
            Dialect.Postgres -> name.lowercase()
            Dialect.Oracle -> name.uppercase()
            Dialect.MySql,
            Dialect.Mssql -> name
        }

    private fun exactSpelling(ident: SqlIdent): String =
        if (ident.quoted) ident.name else foldUnquoted(ident.name)

    private fun <T> resolveUnique(
        candidates: List<T>,
        nameOf: (T) -> String,
        ident: SqlIdent,
        onMissing: () -> Nothing,
        onAmbiguous: () -> Nothing,
    ): T {
        val exact = candidates.filter { nameOf(it) == exactSpelling(ident) }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) onAmbiguous()
        if (ident.quoted) onMissing()
        val ciMatches = candidates.filter { nameOf(it).equals(ident.name, ignoreCase = true) }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 -> onMissing()
            else -> onAmbiguous()
        }
    }

    private fun resolveTable(ref: SqlTableRefAst): ResolvedTable {
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
            resolveUnique(
                candidates = candidates,
                nameOf = { it.name },
                ident = ref.name,
                onMissing = {
                    fail(
                        SqlIssueCode.UnknownTable,
                        "Table '${ref.name.name}' not found in schema '$schemaName'.",
                        ref.name.span,
                    )
                },
                onAmbiguous = {
                    fail(
                        SqlIssueCode.UnknownTable,
                        "'${ref.name.name}' matches several tables — quote the exact name.",
                        ref.name.span,
                    )
                },
            )
        val alias = ref.alias?.name ?: info.name
        if (tables.any { it.alias == alias }) {
            fail(SqlIssueCode.DuplicateAlias, "Alias '$alias' is used more than once.", ref.span)
        }
        val resolved = ResolvedTable(info, alias)
        tables.add(resolved)
        return resolved
    }

    private fun resolveSchemaName(ident: SqlIdent): String {
        val names = schema.tables.map { it.schema }.distinct()
        return resolveUnique(
            candidates = names,
            nameOf = { it },
            ident = ident,
            onMissing = {
                fail(SqlIssueCode.UnknownSchema, "Schema '${ident.name}' not found.", ident.span)
            },
            onAmbiguous = {
                fail(
                    SqlIssueCode.UnknownSchema,
                    "'${ident.name}' matches several schemas — quote the exact name.",
                    ident.span,
                )
            },
        )
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

    // Aliases win over table names, matching SQL scoping: once a table is aliased, the table name
    // is
    // no longer a valid qualifier. Both tiers reject ambiguity rather than taking the first match.
    private fun resolveQualifier(ident: SqlIdent): ResolvedTable {
        val onMissing: () -> Nothing = {
            fail(
                SqlIssueCode.UnknownTable,
                "'${ident.name}' doesn't match a table or alias in this query.",
                ident.span,
            )
        }
        val onAmbiguous: () -> Nothing = {
            fail(
                SqlIssueCode.UnknownTable,
                "'${ident.name}' matches several tables in this query.",
                ident.span,
            )
        }

        val aliasMatches = tables.filter { it.alias == exactSpelling(ident) }
        if (aliasMatches.size == 1) return aliasMatches[0]
        if (aliasMatches.size > 1) onAmbiguous()

        val nameMatches = tables.filter { it.info.name == exactSpelling(ident) }
        if (nameMatches.size == 1) return nameMatches[0]
        if (nameMatches.size > 1) onAmbiguous()

        if (ident.quoted) onMissing()

        val ciMatches = tables.filter {
            it.alias.equals(ident.name, ignoreCase = true) ||
                it.info.name.equals(ident.name, ignoreCase = true)
        }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 -> onMissing()
            else -> onAmbiguous()
        }
    }

    private fun resolveColumn(ref: SqlColumnRefAst): Pair<String, ColumnInfo> {
        if (ref.qualifier != null) {
            val table = resolveQualifier(ref.qualifier)
            val column = findColumn(table.info, ref.name)
            return table.alias to column
        }
        val onAmbiguous: () -> Nothing = {
            fail(
                SqlIssueCode.AmbiguousColumn,
                "'${ref.name.name}' exists in several tables — qualify it as table.column.",
                ref.name.span,
            )
        }
        val exact = tables.flatMap { table ->
            table.info.columns
                .filter { it.name == exactSpelling(ref.name) }
                .map { table.alias to it }
        }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) onAmbiguous()
        if (ref.name.quoted) {
            fail(
                SqlIssueCode.UnknownColumn,
                "Column '${ref.name.name}' not found in the referenced tables.",
                ref.name.span,
            )
        }
        val ciMatches = tables.flatMap { table ->
            table.info.columns
                .filter { it.name.equals(ref.name.name, ignoreCase = true) }
                .map { table.alias to it }
        }
        return when (ciMatches.size) {
            1 -> ciMatches[0]
            0 ->
                fail(
                    SqlIssueCode.UnknownColumn,
                    "Column '${ref.name.name}' not found in the referenced tables.",
                    ref.name.span,
                )
            else -> onAmbiguous()
        }
    }

    private fun findColumn(table: TableInfo, ident: SqlIdent): ColumnInfo =
        resolveUnique(
            candidates = table.columns,
            nameOf = { it.name },
            ident = ident,
            onMissing = {
                fail(
                    SqlIssueCode.UnknownColumn,
                    "Column '${ident.name}' not found in table '${table.name}'.",
                    ident.span,
                )
            },
            onAmbiguous = {
                fail(
                    SqlIssueCode.UnknownColumn,
                    "'${ident.name}' matches several columns — quote the exact name.",
                    ident.span,
                )
            },
        )

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
        val literals = predicate.values.map { toFilterLiteral(kind, column, it) }
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

    // The literal's written form has to survive into the binding. Deriving the kind from the column
    // alone turns `name = 123` into a text comparison against "123", which is a different — and in
    // MySQL a differently-behaved — query than the one the user typed.
    private fun toFilterLiteral(
        kind: LiteralKind,
        column: ColumnInfo,
        literal: SqlLiteralAst,
    ): FilterLiteral {
        val raw = literal.raw
        val mismatch: () -> Nothing = {
            fail(
                SqlIssueCode.LiteralTypeMismatch,
                SqlMessages.literalTypeMismatch(column.name, column.dataType),
                literal.span,
            )
        }
        return when (kind) {
            LiteralKind.Text -> {
                if (literal.form != LiteralForm.Text) mismatch()
                FilterLiteral(kind, raw)
            }
            LiteralKind.Int,
            LiteralKind.Decimal,
            LiteralKind.Float ->
                when (literal.form) {
                    LiteralForm.Number -> FilterLiteral(kind, raw)
                    // `id = '123'` is a common idiom that every supported dialect coerces without
                    // changing the comparison, so allow it when the text really is such a number.
                    LiteralForm.Text ->
                        if (isNumericText(kind, raw)) FilterLiteral(kind, raw) else mismatch()
                    LiteralForm.Bool -> mismatch()
                }
            LiteralKind.Bool -> {
                if (parseBoolLiteral(raw) == null) mismatch()
                FilterLiteral(kind, raw)
            }
            LiteralKind.Date -> {
                if (literal.form != LiteralForm.Text) mismatch()
                if (parseDateLiteral(raw).isFailure) {
                    fail(
                        SqlIssueCode.LiteralTypeMismatch,
                        SqlMessages.dateFormat(column.name),
                        literal.span,
                    )
                }
                FilterLiteral(kind, raw)
            }
            LiteralKind.DateTime -> {
                if (literal.form != LiteralForm.Text) mismatch()
                when {
                    parseDateTimeLiteral(raw).isSuccess -> FilterLiteral(kind, raw)
                    // Postgres and MySQL both accept a bare date against a timestamp column, so
                    // widen
                    // it to midnight rather than failing validation after the query looks runnable.
                    parseDateLiteral(raw).isSuccess -> FilterLiteral(kind, "${raw.trim()}T00:00:00")
                    else ->
                        fail(
                            SqlIssueCode.LiteralTypeMismatch,
                            SqlMessages.dateTimeFormat(column.name),
                            literal.span,
                        )
                }
            }
        }
    }

    private fun isNumericText(kind: LiteralKind, raw: String): Boolean {
        val trimmed = raw.trim()
        return when (kind) {
            LiteralKind.Int -> trimmed.toLongOrNull() != null
            else -> trimmed.toDoubleOrNull() != null
        }
    }

    // Deterministic ids: re-parsing identical text must yield an equal QuerySpec, because pending
    // confirmations and the Explore handoff compare specs structurally.
    private fun nextGroupId(): String = "g${groupCounter++}"

    private fun nextLeafId(): String = "f${leafCounter++}"

    private fun fail(code: SqlIssueCode, message: String, span: SqlSpan?): Nothing =
        throw SqlParseException(SqlIssue(code, message, span))
}
