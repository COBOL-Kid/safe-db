package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.FilterOp
import com.safedb.model.SortDirection
import com.safedb.model.displayName
import java.util.Locale

internal sealed class SqlStatementResult {
    data class Ok(val ast: SqlSelectAst) : SqlStatementResult()

    data class Fail(val issue: SqlIssue) : SqlStatementResult()
}

// Deep parenthesis towers would otherwise overflow the recursive-descent stack and kill the UI
// thread mid-composition; genuine queries never need anywhere near this many levels.
internal const val MAX_CONDITION_PAREN_DEPTH = 32

internal fun parseSqlStatement(
    sql: String,
    dialect: Dialect,
    mySqlBackslashEscapes: Boolean? = null,
): SqlStatementResult {
    val all = tokenizeSql(sql, dialect, mySqlBackslashEscapes)
    all.firstOrNull { it.type == SqlTokenType.Error }
        ?.let {
            return SqlStatementResult.Fail(
                SqlIssue(SqlIssueCode.Syntax, it.error ?: "Invalid input", it.span)
            )
        }
    val significant = all.filter {
        it.type != SqlTokenType.Whitespace && it.type != SqlTokenType.Comment
    }
    return try {
        SqlStatementResult.Ok(SqlParser(significant, dialect, sql.length).parseStatement())
    } catch (e: SqlParseException) {
        SqlStatementResult.Fail(e.issue)
    }
}

internal class SqlParseException(val issue: SqlIssue) : Exception(issue.message)

// Words that end a table-alias position but are not dialect keywords (they tokenize as
// identifiers).
private val NON_ALIAS_WORDS =
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

private val SET_OPERATIONS = setOf("UNION", "INTERSECT", "EXCEPT")
private val OUTER_JOIN_WORDS = setOf("LEFT", "RIGHT", "FULL", "CROSS", "OUTER")
private val COMPARISON_OPERATORS = setOf("=", "<>", "!=", ">", ">=", "<", "<=")
private const val LIKE_ESCAPE_CHAR = "!"

private class SqlParser(
    private val tokens: List<SqlToken>,
    private val dialect: Dialect,
    private val inputLength: Int,
) {
    private var pos = 0
    private var parenDepth = 0

    fun parseStatement(): SqlSelectAst {
        val first = peek() ?: fail(SqlIssueCode.Syntax, "Enter a SELECT query.", null)
        val firstWord = sqlWord(first.text)
        when {
            wordAt(0) == "WITH" -> fail(SqlIssueCode.Unsupported, SqlMessages.CTE, first.span)
            firstWord in BLOCKED_STATEMENT_STARTERS &&
                first.type != SqlTokenType.QuotedIdentifier ->
                fail(SqlIssueCode.NotSelect, SqlMessages.notSelect(firstWord), first.span)
            wordAt(0) != "SELECT" ->
                fail(SqlIssueCode.Syntax, "Expected SELECT at the start of the query.", first.span)
        }
        val ast = parseSelect()
        if (peek()?.type == SqlTokenType.Semicolon) pos++
        peek()?.let { extra ->
            val extraWord = sqlWord(extra.text)
            val code =
                if (extraWord in SET_OPERATIONS) SqlIssueCode.Unsupported
                else SqlIssueCode.MultipleStatements
            val message =
                if (extraWord in SET_OPERATIONS) SqlMessages.SET_OPERATION
                else SqlMessages.MULTIPLE_STATEMENTS
            fail(code, message, extra.span)
        }
        return ast
    }

    private fun parseSelect(): SqlSelectAst {
        expectWord("SELECT", "Expected SELECT.")
        val distinct = matchWord("DISTINCT")
        var topLimit: Int? = null
        // TOP is a keyword only on MSSQL (see sqlKeywords); elsewhere it tokenizes as an identifier
        // and `SELECT top FROM t` is an ordinary column. `TOP 7` and `TOP (7)` cannot be a column
        // reference in any dialect, so those still reach the clause and its "use LIMIT" message.
        val looksLikeTopClause =
            wordAt(0) == "TOP" &&
                (peek()?.type == SqlTokenType.Keyword ||
                    peek(1)?.type == SqlTokenType.NumberLiteral ||
                    peek(1)?.type == SqlTokenType.LeftParen)
        if (looksLikeTopClause) {
            val topToken = advance()
            if (dialect != Dialect.Mssql) {
                fail(
                    SqlIssueCode.Unsupported,
                    SqlMessages.topElsewhere(dialect.displayName()),
                    topToken.span,
                )
            }
            val parenthesized = peek()?.type == SqlTokenType.LeftParen
            if (parenthesized) advance()
            topLimit = parseLimitNumber()
            if (parenthesized) expectType(SqlTokenType.RightParen, "Expected ')' after TOP (n).")
        }

        val items = parseSelectList()
        expectWord("FROM", "Expected FROM after the select list.")
        val from = parseTableRef()
        val joins = parseJoins()
        val where = if (matchWord("WHERE")) parseOrExpr() else null
        var groupBy = emptyList<SqlColumnRefAst>()
        if (matchWord("GROUP")) {
            expectWord("BY", "Expected BY after GROUP.")
            groupBy = parseColumnRefList()
        }
        if (wordAt(0) == "HAVING") {
            fail(SqlIssueCode.Unsupported, SqlMessages.HAVING, peek()!!.span)
        }
        var orderBy = emptyList<Pair<SqlColumnRefAst, SortDirection>>()
        if (matchWord("ORDER")) {
            expectWord("BY", "Expected BY after ORDER.")
            orderBy = parseOrderList()
        }
        val limit = parseLimitClause(topLimit)
        if (wordAt(0) == "OFFSET") {
            fail(SqlIssueCode.Unsupported, SqlMessages.OFFSET, peek()!!.span)
        }
        if (wordAt(0) in SET_OPERATIONS) {
            fail(SqlIssueCode.Unsupported, SqlMessages.SET_OPERATION, peek()!!.span)
        }
        peek()?.let { trailing ->
            if (trailing.type != SqlTokenType.Semicolon) {
                fail(SqlIssueCode.Syntax, "Unexpected '${trailing.text}'.", trailing.span)
            }
        }
        return SqlSelectAst(distinct, items, from, joins, where, groupBy, orderBy, limit)
    }

    private fun parseSelectList(): List<SqlSelectItem>? {
        if (peek()?.type == SqlTokenType.Star) {
            val star = advance()
            if (peek()?.type == SqlTokenType.Comma) {
                fail(SqlIssueCode.Unsupported, SqlMessages.STAR_MIX, star.span)
            }
            return null
        }
        val items = mutableListOf<SqlSelectItem>()
        while (true) {
            if (peek()?.type == SqlTokenType.Star) {
                fail(SqlIssueCode.Unsupported, SqlMessages.STAR_MIX, peek()!!.span)
            }
            val first = parseIdent("Expected a column name.")
            if (peek()?.type == SqlTokenType.LeftParen) {
                fail(SqlIssueCode.Unsupported, SqlMessages.FUNCTIONS, spanFrom(first.span))
            }
            val item =
                if (peek()?.type == SqlTokenType.Dot) {
                    advance()
                    if (peek()?.type == SqlTokenType.Star) {
                        advance()
                        SqlSelectItem.TableStar(first)
                    } else {
                        val name = parseIdent("Expected a column name after '.'.")
                        if (peek()?.type == SqlTokenType.LeftParen) {
                            fail(
                                SqlIssueCode.Unsupported,
                                SqlMessages.FUNCTIONS,
                                spanFrom(name.span),
                            )
                        }
                        SqlSelectItem.Column(SqlColumnRefAst(first, name))
                    }
                } else {
                    SqlSelectItem.Column(SqlColumnRefAst(null, first))
                }
            rejectSelectItemTail()
            items.add(item)
            if (peek()?.type == SqlTokenType.Comma) {
                advance()
                continue
            }
            return items
        }
    }

    private fun rejectSelectItemTail() {
        val next = peek() ?: return
        when {
            wordAt(0) == "AS" -> fail(SqlIssueCode.Unsupported, SqlMessages.COLUMN_ALIAS, next.span)
            next.type == SqlTokenType.Operator ->
                fail(SqlIssueCode.Unsupported, SqlMessages.EXPRESSION, next.span)
            isIdent(next) && wordAt(0) !in NON_ALIAS_WORDS -> {
                suggestKeywordTypo(next.text, "FROM")?.let { message ->
                    fail(SqlIssueCode.Syntax, message, next.span)
                }
                fail(SqlIssueCode.Unsupported, SqlMessages.COLUMN_ALIAS, next.span)
            }
        }
    }

    private fun parseTableRef(): SqlTableRefAst {
        if (peek()?.type == SqlTokenType.LeftParen) {
            fail(SqlIssueCode.Unsupported, SqlMessages.SUBQUERY, peek()!!.span)
        }
        val first = parseIdent("Expected a table name.")
        var schema: SqlIdent? = null
        var name = first
        if (peek()?.type == SqlTokenType.Dot) {
            advance()
            schema = first
            name = parseIdent("Expected a table name after '.'.")
        }
        var alias: SqlIdent? = null
        if (matchWord("AS")) {
            alias = parseIdent("Expected an alias after AS.")
        } else {
            val next = peek()
            if (next != null && isIdent(next) && sqlWord(next.text) !in NON_ALIAS_WORDS) {
                alias = parseIdent("Expected an alias.")
            }
        }
        return SqlTableRefAst(schema, name, alias)
    }

    private fun parseJoins(): List<SqlJoinAst> {
        val joins = mutableListOf<SqlJoinAst>()
        while (true) {
            when (val word = wordAt(0)) {
                in OUTER_JOIN_WORDS ->
                    fail(SqlIssueCode.Unsupported, SqlMessages.OUTER_JOIN, peek()!!.span)
                "INNER",
                "JOIN" -> {
                    if (word == "INNER") advance()
                    expectWord("JOIN", "Expected JOIN after INNER.")
                    val table = parseTableRef()
                    expectWord("ON", "Expected ON after the joined table.")
                    val conditions = mutableListOf<Pair<SqlColumnRefAst, SqlColumnRefAst>>()
                    do {
                        conditions.add(parseJoinCondition())
                    } while (matchWord("AND"))
                    joins.add(SqlJoinAst(table, conditions))
                }
                else -> return joins
            }
        }
    }

    private fun parseJoinCondition(): Pair<SqlColumnRefAst, SqlColumnRefAst> {
        val left = parseColumnRef()
        val op = peek()
        if (op == null || op.type != SqlTokenType.Operator || op.text != "=") {
            fail(SqlIssueCode.Unsupported, SqlMessages.JOIN_CONDITION, op?.span ?: endSpan())
        }
        advance()
        val rightStart =
            peek() ?: fail(SqlIssueCode.Syntax, "Expected a column after '='.", endSpan())
        if (!isIdent(rightStart)) {
            fail(SqlIssueCode.Unsupported, SqlMessages.JOIN_CONDITION, rightStart.span)
        }
        val right = parseColumnRef()
        return left to right
    }

    private fun parseOrExpr(): SqlConditionAst {
        val children = mutableListOf(parseAndExpr())
        while (matchWord("OR")) {
            children.add(parseAndExpr())
        }
        return if (children.size == 1) children[0] else SqlConditionAst.Or(children)
    }

    private fun parseAndExpr(): SqlConditionAst {
        val children = mutableListOf(parseConditionPrimary())
        while (matchWord("AND")) {
            children.add(parseConditionPrimary())
        }
        return if (children.size == 1) children[0] else SqlConditionAst.And(children)
    }

    private fun parseConditionPrimary(): SqlConditionAst {
        val next = peek() ?: fail(SqlIssueCode.Syntax, "Expected a condition.", endSpan())
        if (wordAt(0) == "NOT") {
            fail(SqlIssueCode.Unsupported, SqlMessages.NOT_CONDITION, next.span)
        }
        if (next.type == SqlTokenType.LeftParen) {
            if (parenDepth >= MAX_CONDITION_PAREN_DEPTH) {
                fail(SqlIssueCode.Unsupported, SqlMessages.PAREN_DEPTH, next.span)
            }
            parenDepth++
            advance()
            if (wordAt(0) == "SELECT") {
                fail(SqlIssueCode.Unsupported, SqlMessages.SUBQUERY, peek()!!.span)
            }
            val inner = parseOrExpr()
            expectType(SqlTokenType.RightParen, "Expected ')'.")
            parenDepth--
            return inner
        }
        return parsePredicate()
    }

    private fun parsePredicate(): SqlConditionAst.Predicate {
        val column = parseColumnRef()
        val next =
            peek()
                ?: fail(SqlIssueCode.Syntax, "Expected a comparison after the column.", endSpan())
        val word = sqlWord(next.text)
        return when {
            next.type == SqlTokenType.Operator && next.text in COMPARISON_OPERATORS -> {
                advance()
                val op =
                    when (next.text) {
                        "=" -> FilterOp.Eq
                        "<>",
                        "!=" -> FilterOp.Ne
                        ">" -> FilterOp.Gt
                        ">=" -> FilterOp.Gte
                        "<" -> FilterOp.Lt
                        else -> FilterOp.Lte
                    }
                val value = parseLiteral()
                predicate(column, op, listOf(value))
            }
            next.type == SqlTokenType.Operator ->
                fail(SqlIssueCode.Unsupported, SqlMessages.EXPRESSION, next.span)
            word == "IS" -> {
                advance()
                val negated = matchWord("NOT")
                val nullToken = peek()
                if (wordAt(0) != "NULL") {
                    fail(
                        SqlIssueCode.Syntax,
                        "Expected NULL after IS${if (negated) " NOT" else ""}.",
                        nullToken?.span ?: endSpan(),
                    )
                }
                advance()
                predicate(column, if (negated) FilterOp.IsNotNull else FilterOp.IsNull, emptyList())
            }
            word == "LIKE" -> {
                advance()
                val value = parseLiteral()
                consumeOptionalLikeEscape()
                predicate(column, FilterOp.Like, listOf(value))
            }
            word == "ILIKE" -> {
                val ilikeToken = advance()
                if (dialect != Dialect.Postgres) {
                    fail(
                        SqlIssueCode.Unsupported,
                        SqlMessages.ilikeElsewhere(dialect.displayName()),
                        ilikeToken.span,
                    )
                }
                val value = parseLiteral()
                consumeOptionalLikeEscape()
                predicate(column, FilterOp.Ilike, listOf(value))
            }
            word == "NOT" -> {
                advance()
                when (wordAt(0)) {
                    "LIKE" -> {
                        advance()
                        val value = parseLiteral()
                        consumeOptionalLikeEscape()
                        predicate(column, FilterOp.NotLike, listOf(value))
                    }
                    "IN" -> {
                        advance()
                        predicate(column, FilterOp.NotIn, parseInList())
                    }
                    // BETWEEN and ILIKE parse on their own, so a bare "Expected LIKE or IN"
                    // reads like a typo. Name the construct that has no negated FilterOp.
                    "BETWEEN",
                    "ILIKE" ->
                        fail(
                            SqlIssueCode.Unsupported,
                            SqlMessages.notOperator(wordAt(0)!!),
                            peek()?.span ?: endSpan(),
                        )
                    else ->
                        fail(
                            SqlIssueCode.Syntax,
                            "Expected LIKE or IN after NOT.",
                            peek()?.span ?: endSpan(),
                        )
                }
            }
            word == "IN" -> {
                advance()
                predicate(column, FilterOp.In, parseInList())
            }
            word == "BETWEEN" -> {
                advance()
                val low = parseLiteral()
                expectWord("AND", "Expected AND in BETWEEN.")
                val high = parseLiteral()
                predicate(column, FilterOp.Between, listOf(low, high))
            }
            else ->
                fail(
                    SqlIssueCode.Syntax,
                    "Expected a comparison operator after '${identDisplay(column)}'.",
                    next.span,
                )
        }
    }

    private fun predicate(
        column: SqlColumnRefAst,
        op: FilterOp,
        values: List<SqlLiteralAst>,
    ): SqlConditionAst.Predicate {
        val end = values.lastOrNull()?.span?.end ?: previousEnd()
        return SqlConditionAst.Predicate(column, op, values, SqlSpan(column.span.start, end))
    }

    private fun parseInList(): List<SqlLiteralAst> {
        expectType(SqlTokenType.LeftParen, "Expected '(' after IN.")
        if (wordAt(0) == "SELECT") {
            fail(SqlIssueCode.Unsupported, SqlMessages.SUBQUERY, peek()!!.span)
        }
        if (peek()?.type == SqlTokenType.RightParen) {
            fail(SqlIssueCode.Syntax, "IN requires at least one value.", peek()!!.span)
        }
        val values = mutableListOf(parseLiteral())
        while (peek()?.type == SqlTokenType.Comma) {
            advance()
            values.add(parseLiteral())
        }
        expectType(SqlTokenType.RightParen, "Expected ')' to close the IN list.")
        return values
    }

    private fun parseLiteral(): SqlLiteralAst {
        val next = peek() ?: fail(SqlIssueCode.Syntax, "Expected a value.", endSpan())
        val literal =
            when {
                next.type == SqlTokenType.StringLiteral -> {
                    advance()
                    SqlLiteralAst(next.value, LiteralForm.Text, next.span)
                }
                next.type == SqlTokenType.NumberLiteral -> {
                    advance()
                    SqlLiteralAst(next.text, LiteralForm.Number, next.span)
                }
                next.type == SqlTokenType.Operator && next.text == "-" -> {
                    advance()
                    val number = peek()
                    if (number == null || number.type != SqlTokenType.NumberLiteral) {
                        fail(
                            SqlIssueCode.Syntax,
                            "Expected a number after '-'.",
                            number?.span ?: endSpan(),
                        )
                    }
                    advance()
                    SqlLiteralAst(
                        "-${number.text}",
                        LiteralForm.Number,
                        SqlSpan(next.span.start, number.span.end),
                    )
                }
                wordAt(0) == "TRUE" || wordAt(0) == "FALSE" -> {
                    advance()
                    SqlLiteralAst(next.text.lowercase(Locale.ROOT), LiteralForm.Bool, next.span)
                }
                wordAt(0) == "NULL" ->
                    fail(SqlIssueCode.Unsupported, SqlMessages.COMPARE_NULL, next.span)
                next.type == SqlTokenType.LeftParen && wordAt(1) == "SELECT" ->
                    fail(SqlIssueCode.Unsupported, SqlMessages.SUBQUERY, next.span)
                isIdent(next) ->
                    fail(SqlIssueCode.Unsupported, SqlMessages.COLUMN_COMPARE, next.span)
                else ->
                    fail(SqlIssueCode.Syntax, "Expected a value, found '${next.text}'.", next.span)
            }
        val after = peek()
        if (
            after != null &&
                after.type == SqlTokenType.Operator &&
                after.text !in COMPARISON_OPERATORS
        ) {
            fail(SqlIssueCode.Unsupported, SqlMessages.EXPRESSION, after.span)
        }
        return literal
    }

    // Clipboard SQL escapes LIKE metacharacters with `!`; accept the explicit ESCAPE clause so that
    // rendered patterns paste back. It only validates the escape character and does not change the
    // operator.
    private fun consumeOptionalLikeEscape() {
        if (wordAt(0) != "ESCAPE") return
        advance()
        val token = peek()
        if (token == null || token.type != SqlTokenType.StringLiteral) {
            fail(
                SqlIssueCode.Syntax,
                "Expected a string literal after ESCAPE.",
                token?.span ?: endSpan(),
            )
        }
        advance()
        if (token.value != LIKE_ESCAPE_CHAR) {
            fail(
                SqlIssueCode.Syntax,
                "Only ESCAPE '$LIKE_ESCAPE_CHAR' is supported.",
                token.span,
            )
        }
    }

    private fun parseColumnRefList(): List<SqlColumnRefAst> {
        val refs = mutableListOf(parseColumnRef())
        while (peek()?.type == SqlTokenType.Comma) {
            advance()
            refs.add(parseColumnRef())
        }
        return refs
    }

    private fun parseOrderList(): List<Pair<SqlColumnRefAst, SortDirection>> {
        val items = mutableListOf<Pair<SqlColumnRefAst, SortDirection>>()
        while (true) {
            val ref = parseColumnRef()
            val direction =
                when {
                    matchWord("ASC") -> SortDirection.Asc
                    matchWord("DESC") -> SortDirection.Desc
                    else -> SortDirection.Asc
                }
            items.add(ref to direction)
            if (peek()?.type == SqlTokenType.Comma) {
                advance()
                continue
            }
            return items
        }
    }

    private fun parseLimitClause(topLimit: Int?): Int? {
        return when {
            matchWord("LIMIT") -> {
                if (topLimit != null) {
                    fail(SqlIssueCode.Syntax, "Use either TOP or LIMIT, not both.", previousSpan())
                }
                parseLimitNumber()
            }
            wordAt(0) == "FETCH" -> {
                advance()
                expectWord("FIRST", "Expected FIRST after FETCH.")
                val limit = parseLimitNumber()
                expectWord("ROWS", "Expected ROWS in FETCH FIRST.")
                expectWord("ONLY", "Expected ONLY in FETCH FIRST.")
                if (topLimit != null) {
                    fail(
                        SqlIssueCode.Syntax,
                        "Use either TOP or FETCH FIRST, not both.",
                        previousSpan(),
                    )
                }
                limit
            }
            else -> topLimit
        }
    }

    private fun parseLimitNumber(): Int {
        val token = peek()
        if (token == null || token.type != SqlTokenType.NumberLiteral) {
            fail(
                SqlIssueCode.InvalidLimit,
                SqlMessages.LIMIT_WHOLE_NUMBER,
                token?.span ?: endSpan(),
            )
        }
        advance()
        if (token.text.contains('.')) {
            fail(SqlIssueCode.InvalidLimit, SqlMessages.LIMIT_WHOLE_NUMBER, token.span)
        }
        val digits = token.text.trimStart('0').ifEmpty { "0" }
        val value = digits.toLongOrNull()
        // A digit string that does not fit in a Long is still above the 32-bit maximum.
        if (value == null) {
            if (digits.all(Char::isDigit)) return Int.MAX_VALUE
            fail(SqlIssueCode.InvalidLimit, SqlMessages.LIMIT_WHOLE_NUMBER, token.span)
        }
        // QuerySpec reserves 0 as the builder's "no limit chosen" sentinel, which validateQuery
        // rewrites to DEFAULT_LIMIT. Typed SQL means it literally, so reject rather than run 500.
        if (value < 1) {
            fail(SqlIssueCode.InvalidLimit, SqlMessages.LIMIT_POSITIVE, token.span)
        }
        if (value > Int.MAX_VALUE.toLong()) return Int.MAX_VALUE
        return value.toInt()
    }

    private fun parseColumnRef(): SqlColumnRefAst {
        val first = parseIdent("Expected a column name.")
        if (peek()?.type == SqlTokenType.LeftParen) {
            fail(SqlIssueCode.Unsupported, SqlMessages.FUNCTIONS, spanFrom(first.span))
        }
        if (peek()?.type == SqlTokenType.Dot) {
            advance()
            val name = parseIdent("Expected a column name after '.'.")
            if (peek()?.type == SqlTokenType.LeftParen) {
                fail(SqlIssueCode.Unsupported, SqlMessages.FUNCTIONS, spanFrom(name.span))
            }
            return SqlColumnRefAst(first, name)
        }
        return SqlColumnRefAst(null, first)
    }

    private fun parseIdent(expectation: String): SqlIdent {
        val token = peek() ?: fail(SqlIssueCode.Syntax, expectation, endSpan())
        if (!isIdent(token)) {
            fail(SqlIssueCode.Syntax, "$expectation Found '${token.text}'.", token.span)
        }
        advance()
        return SqlIdent(
            name = token.value,
            quoted = token.type == SqlTokenType.QuotedIdentifier,
            span = token.span,
        )
    }

    private fun isIdent(token: SqlToken): Boolean =
        token.type == SqlTokenType.Identifier || token.type == SqlTokenType.QuotedIdentifier

    private fun identDisplay(ref: SqlColumnRefAst): String =
        listOfNotNull(ref.qualifier?.name, ref.name.name).joinToString(".")

    private fun peek(offset: Int = 0): SqlToken? = tokens.getOrNull(pos + offset)

    private fun advance(): SqlToken = tokens[pos++]

    // Uppercased word at lookahead, for unquoted identifiers and keywords only.
    private fun wordAt(offset: Int): String? {
        val token = peek(offset) ?: return null
        if (token.type != SqlTokenType.Keyword && token.type != SqlTokenType.Identifier) return null
        return sqlWord(token.text)
    }

    private fun matchWord(word: String): Boolean {
        if (wordAt(0) == word) {
            pos++
            return true
        }
        return false
    }

    private fun expectWord(word: String, message: String) {
        if (matchWord(word)) return
        val token = peek()
        val typo = token?.text?.let { suggestKeywordTypo(it, word) }
        fail(SqlIssueCode.Syntax, typo ?: message, token?.span ?: endSpan())
    }

    private fun expectType(type: SqlTokenType, message: String) {
        val token = peek()
        if (token == null || token.type != type) {
            fail(SqlIssueCode.Syntax, message, token?.span ?: endSpan())
        }
        pos++
    }

    private fun endSpan(): SqlSpan = SqlSpan(inputLength, inputLength)

    private fun previousSpan(): SqlSpan = tokens.getOrNull(pos - 1)?.span ?: endSpan()

    private fun previousEnd(): Int = tokens.getOrNull(pos - 1)?.span?.end ?: inputLength

    private fun spanFrom(start: SqlSpan): SqlSpan =
        SqlSpan(start.start, peek()?.span?.end ?: start.end)

    private fun fail(code: SqlIssueCode, message: String, span: SqlSpan?): Nothing =
        throw SqlParseException(SqlIssue(code, message, span))
}

internal fun suggestKeywordTypo(found: String, expected: String): String? {
    val actual = sqlWord(found)
    val target = sqlWord(expected)
    if (actual == target) return null
    val distance = editDistance(actual, target)
    val nearMiss = distance == 1 || (distance == 2 && isAdjacentTransposition(actual, target))
    return if (nearMiss) SqlMessages.unexpectedKeywordTypo(found, expected) else null
}

internal fun isAdjacentTransposition(left: String, right: String): Boolean {
    if (left.length != right.length || left.length < 2) return false
    var index = 0
    while (index < left.length && left[index] == right[index]) index++
    return index < left.length - 1 &&
        left[index] == right[index + 1] &&
        left[index + 1] == right[index] &&
        left.substring(index + 2) == right.substring(index + 2)
}

internal fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    val previous = IntArray(right.length + 1) { it }
    val current = IntArray(right.length + 1)
    for (i in 1..left.length) {
        current[0] = i
        for (j in 1..right.length) {
            val substitution = if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + substitution)
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return previous[right.length]
}
