package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.TableInfo

enum class SqlCompletionKind {
    Keyword,
    Table,
    Column,
    Alias,
    SchemaName,
}

data class SqlCompletionItem(
    val label: String,
    val insertText: String,
    val kind: SqlCompletionKind,
    val detail: String? = null,
)

data class SqlCompletionRequest(
    val text: String,
    val caret: Int,
    val dialect: Dialect,
    val schema: Schema?,
    val defaultSchema: String?,
)

data class SqlCompletionResult(
    val items: List<SqlCompletionItem>,
    // Character range of the word being completed; inserted text replaces this range.
    val replaceStart: Int,
    val replaceEnd: Int,
)

private data class StatementTable(val info: TableInfo?, val name: String, val alias: String)

fun sqlCompletions(request: SqlCompletionRequest): SqlCompletionResult {
    val caret = request.caret.coerceIn(0, request.text.length)
    val tokens = tokenizeSql(request.text, request.dialect)
    val significant = tokens.filter {
        it.type != SqlTokenType.Whitespace && it.type != SqlTokenType.Comment
    }

    // A quoted identifier, string, comment, or malformed token containing the caret is not
    // completable. A line comment or unterminated literal running to the end of the text still
    // contains a caret sitting at its very end, so those extend one position further.
    tokens
        .firstOrNull { token ->
            val opaque =
                token.type == SqlTokenType.QuotedIdentifier ||
                    token.type == SqlTokenType.StringLiteral ||
                    token.type == SqlTokenType.Comment ||
                    token.type == SqlTokenType.Error
            if (!opaque || token.span.start >= caret) return@firstOrNull false
            val openToEof =
                token.span.end == request.text.length &&
                    (token.type == SqlTokenType.Error || !token.text.endsWith("*/"))
            caret < token.span.end || (openToEof && caret == token.span.end)
        }
        ?.let {
            return SqlCompletionResult(emptyList(), caret, caret)
        }

    val wordToken = significant.lastOrNull {
        (it.type == SqlTokenType.Identifier || it.type == SqlTokenType.Keyword) &&
            it.span.start < caret &&
            caret <= it.span.end
    }
    val prefix = wordToken?.let { request.text.substring(it.span.start, caret) } ?: ""
    val replaceStart = wordToken?.span?.start ?: caret

    val before = significant.filter { it.span.end <= (wordToken?.span?.start ?: caret) }
    val prev = before.lastOrNull()
    val prevWord =
        prev
            ?.takeIf { it.type == SqlTokenType.Keyword || it.type == SqlTokenType.Identifier }
            ?.text
            ?.uppercase()

    val statementTables = scanStatementTables(significant, request)
    val keywords = sqlCompletionKeywords(request.dialect)

    val items: List<SqlCompletionItem> =
        when {
            prev?.type == SqlTokenType.Dot -> {
                val qualifier =
                    before.getOrNull(before.size - 2)?.takeIf {
                        it.type == SqlTokenType.Identifier ||
                            it.type == SqlTokenType.QuotedIdentifier
                    }
                val table = qualifier?.let { q ->
                    statementTables.find {
                        it.alias.equals(q.value, ignoreCase = true) ||
                            it.name.equals(q.value, ignoreCase = true)
                    }
                }
                val columns =
                    table?.info?.columns.orEmpty().map {
                        SqlCompletionItem(it.name, it.name, SqlCompletionKind.Column, it.dataType)
                    }
                // Aliases and table names win, matching SQL scoping. Only when the qualifier names
                // no table in the statement is it a schema — `FROM public.` should list its tables.
                columns.ifEmpty { tableCompletions(request, schemaName = qualifier?.value) }
            }
            prevWord == "FROM" || prevWord == "JOIN" -> tableCompletions(request)
            prevWord in COLUMN_CONTEXT_WORDS ||
                prev?.type == SqlTokenType.Comma ||
                prev?.type == SqlTokenType.LeftParen ->
                columnCompletions(statementTables) + keywordCompletions(keywords)
            else -> keywordCompletions(keywords)
        }

    val filtered =
        if (prefix.isEmpty()) items
        else
            items.filter { item ->
                // Multi-table labels are qualified (`u.email`), so also match what the user is
                // actually typing — the column name after the dot.
                item.label.startsWith(prefix, ignoreCase = true) ||
                    item.label.substringAfterLast('.').startsWith(prefix, ignoreCase = true)
            }
    // Completing mid-word replaces the whole word, not just the part before the caret.
    val replaceEnd = wordToken?.span?.end?.coerceAtLeast(caret) ?: caret
    return SqlCompletionResult(filtered, replaceStart, replaceEnd)
}

private val COLUMN_CONTEXT_WORDS = setOf("SELECT", "DISTINCT", "WHERE", "ON", "BY", "AND", "OR")

private fun keywordCompletions(keywords: Set<String>): List<SqlCompletionItem> =
    keywords.sorted().map { SqlCompletionItem(it, it, SqlCompletionKind.Keyword) }

// With no schemaName, lists the selected schema's tables plus every schema as a `name.` prefix.
// With one, lists only that schema's tables — the member list for a completed `schema.` qualifier.
private fun tableCompletions(
    request: SqlCompletionRequest,
    schemaName: String? = null,
): List<SqlCompletionItem> {
    val schema = request.schema ?: return emptyList()
    if (schemaName != null) {
        val known = schema.tables.map { it.schema }.any { it.equals(schemaName, ignoreCase = true) }
        if (!known) return emptyList()
        return schema.tables
            .filter { it.schema.equals(schemaName, ignoreCase = true) }
            .sortedBy { it.name }
            .map { SqlCompletionItem(it.name, it.name, SqlCompletionKind.Table, it.schema) }
    }
    val tables =
        request.defaultSchema
            ?.let { selected -> schema.tables.filter { it.schema == selected } }
            .orEmpty()
            .sortedBy { it.name }
            .map { SqlCompletionItem(it.name, it.name, SqlCompletionKind.Table, it.schema) }
    val schemaNames =
        schema.tables
            .map { it.schema }
            .distinct()
            .sorted()
            .map { SqlCompletionItem("$it.", "$it.", SqlCompletionKind.SchemaName, "schema") }
    return tables + schemaNames
}

private fun columnCompletions(tables: List<StatementTable>): List<SqlCompletionItem> {
    val qualify = tables.size > 1
    val aliases =
        if (qualify) {
            tables.map {
                SqlCompletionItem("${it.alias}.", "${it.alias}.", SqlCompletionKind.Alias, it.name)
            }
        } else {
            emptyList()
        }
    val columns = tables.flatMap { table ->
        table.info?.columns.orEmpty().map { column ->
            val text = if (qualify) "${table.alias}.${column.name}" else column.name
            SqlCompletionItem(text, text, SqlCompletionKind.Column, column.dataType)
        }
    }
    return aliases + columns.sortedBy { it.label }
}

// Lightweight scan of FROM/JOIN clauses; resolution failures simply yield no columns.
private fun scanStatementTables(
    tokens: List<SqlToken>,
    request: SqlCompletionRequest,
): List<StatementTable> {
    val result = mutableListOf<StatementTable>()
    var i = 0
    while (i < tokens.size) {
        val word = tokens[i].takeIf { it.type == SqlTokenType.Keyword }?.text?.uppercase()
        if (word != "FROM" && word != "JOIN") {
            i++
            continue
        }
        i++
        val first = tokens.getOrNull(i)?.takeIf(::isIdentToken) ?: continue
        i++
        var schemaName: String? = null
        var tableName = first.value
        if (tokens.getOrNull(i)?.type == SqlTokenType.Dot) {
            val name = tokens.getOrNull(i + 1)?.takeIf(::isIdentToken)
            if (name != null) {
                schemaName = first.value
                tableName = name.value
                i += 2
            } else {
                // `FROM public.` with no table yet — recording `public` as a table would shadow the
                // schema and leave the completion popup empty.
                i++
                continue
            }
        }
        var alias = tableName
        val next = tokens.getOrNull(i)
        if (next?.type == SqlTokenType.Keyword && next.text.uppercase() == "AS") {
            tokens.getOrNull(i + 1)?.takeIf(::isIdentToken)?.let {
                alias = it.value
                i += 2
            }
        } else if (
            next != null && isIdentToken(next) && next.text.uppercase() !in NON_TABLE_ALIAS_WORDS
        ) {
            alias = next.value
            i++
        }
        val effectiveSchema = schemaName ?: request.defaultSchema
        val info =
            request.schema?.tables?.find {
                (effectiveSchema == null || it.schema.equals(effectiveSchema, ignoreCase = true)) &&
                    it.name.equals(tableName, ignoreCase = true)
            }
        result.add(StatementTable(info, tableName, alias))
    }
    return result
}

private val NON_TABLE_ALIAS_WORDS =
    setOf(
        "LEFT",
        "RIGHT",
        "FULL",
        "CROSS",
        "OUTER",
        "UNION",
        "INTERSECT",
        "EXCEPT",
        "HAVING",
        "OFFSET",
        "TOP",
    )

private fun isIdentToken(token: SqlToken): Boolean =
    token.type == SqlTokenType.Identifier || token.type == SqlTokenType.QuotedIdentifier
