package com.safedb.query.sql

import com.safedb.model.FilterOp
import com.safedb.model.SortDirection

internal data class SqlIdent(val name: String, val quoted: Boolean, val span: SqlSpan)

internal data class SqlTableRefAst(
    val schema: SqlIdent?,
    val name: SqlIdent,
    val alias: SqlIdent?,
) {
    val span: SqlSpan
        get() = SqlSpan((schema ?: name).span.start, (alias ?: name).span.end)
}

internal data class SqlColumnRefAst(val qualifier: SqlIdent?, val name: SqlIdent) {
    val span: SqlSpan
        get() = SqlSpan((qualifier ?: name).span.start, name.span.end)
}

internal sealed class SqlSelectItem {
    data class TableStar(val qualifier: SqlIdent) : SqlSelectItem()

    data class Column(val ref: SqlColumnRefAst) : SqlSelectItem()
}

internal data class SqlJoinAst(
    val table: SqlTableRefAst,
    val conditions: List<Pair<SqlColumnRefAst, SqlColumnRefAst>>,
)

internal enum class LiteralForm {
    Text,
    Number,
    Bool,
}

internal data class SqlLiteralAst(val raw: String, val form: LiteralForm, val span: SqlSpan)

internal sealed class SqlConditionAst {
    data class Or(val children: List<SqlConditionAst>) : SqlConditionAst()

    data class And(val children: List<SqlConditionAst>) : SqlConditionAst()

    data class Predicate(
        val column: SqlColumnRefAst,
        val op: FilterOp,
        val values: List<SqlLiteralAst>,
        val span: SqlSpan,
    ) : SqlConditionAst()
}

internal data class SqlSelectAst(
    val distinct: Boolean,
    // null items means a bare `*` select list
    val items: List<SqlSelectItem>?,
    val from: SqlTableRefAst,
    val joins: List<SqlJoinAst>,
    val where: SqlConditionAst?,
    val groupBy: List<SqlColumnRefAst>,
    val orderBy: List<Pair<SqlColumnRefAst, SortDirection>>,
    val limit: Int?,
)
