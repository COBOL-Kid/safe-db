package com.safedb.query.sql

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect

data class SqlSpan(val start: Int, val end: Int)

enum class SqlTokenType {
    Keyword,
    Identifier,
    QuotedIdentifier,
    StringLiteral,
    NumberLiteral,
    Operator,
    Comma,
    Dot,
    LeftParen,
    RightParen,
    Semicolon,
    Star,
    Comment,
    Whitespace,
    Error,
}

data class SqlToken(
    val type: SqlTokenType,
    val text: String,
    val span: SqlSpan,
    // QuotedIdentifier: quotes stripped and escape doubling undone. StringLiteral: unescaped value.
    val value: String = text,
    val error: String? = null,
)

private fun quoteHint(dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres,
        Dialect.Oracle -> "double quotes (\"name\")"
        Dialect.MySql -> "backticks (`name`)"
        Dialect.Mssql -> "brackets ([name])"
    }

// value is null when the quote never closes.
private class QuotedScan(val end: Int, val value: String?)

fun tokenizeSql(
    sql: String,
    dialect: Dialect,
    // MySQL string semantics depend on the session's NO_BACKSLASH_ESCAPES mode. True/false when
    // the connection pins sql_mode (see mySqlBackslashEscapes); null when the mode is unknown, in
    // which case strings whose meaning differs between the two modes are rejected, not guessed.
    mySqlBackslashEscapes: Boolean? = null,
): List<SqlToken> {
    val tokens = mutableListOf<SqlToken>()
    val keywords = sqlKeywords(dialect)
    var i = 0

    fun add(
        type: SqlTokenType,
        start: Int,
        end: Int,
        value: String? = null,
        error: String? = null,
    ) {
        tokens.add(
            SqlToken(
                type,
                sql.substring(start, end),
                SqlSpan(start, end),
                value ?: sql.substring(start, end),
                error,
            )
        )
    }

    // MySQL treats \ as an escape in strings unless NO_BACKSLASH_ESCAPES is set, so 'it\'s' is
    // one string and 'a\\b' is a single backslash. ANSI dialects take \ literally.
    fun scanQuoted(close: Char, start: Int, backslashEscapes: Boolean): QuotedScan {
        val body = StringBuilder()
        var j = start + 1
        while (j < sql.length) {
            val ch = sql[j]
            if (backslashEscapes && ch == '\\' && j + 1 < sql.length) {
                when (val escaped = sql[j + 1]) {
                    // Real newline after \ continues the string; \n (letter n) stays an escaped
                    // newline in the value.
                    '\n' -> {
                        j += 2
                        continue
                    }
                    '\r' -> {
                        j += 2
                        if (j < sql.length && sql[j] == '\n') j++
                        continue
                    }
                    'n' -> body.append('\n')
                    't' -> body.append('\t')
                    'r' -> body.append('\r')
                    'b' -> body.append('\b')
                    '0' -> body.append('\u0000')
                    'Z' -> body.append('\u001A')
                    // MySQL keeps the backslash for \% and \_, so LIKE can treat them as
                    // escaped wildcards rather than the wildcard itself.
                    '%',
                    '_' -> {
                        body.append('\\')
                        body.append(escaped)
                    }
                    else -> body.append(escaped)
                }
                j += 2
                continue
            }
            if (ch == close) {
                if (j + 1 < sql.length && sql[j + 1] == close) {
                    body.append(close)
                    j += 2
                    continue
                }
                return QuotedScan(j + 1, body.toString())
            }
            body.append(ch)
            j++
        }
        return QuotedScan(sql.length, null)
    }

    fun readQuoted(close: Char, start: Int, kind: SqlTokenType, what: String): Int {
        val scan = scanQuoted(close, start, backslashEscapes = false)
        if (scan.value == null) {
            add(SqlTokenType.Error, start, scan.end, error = "Unterminated $what")
        } else {
            add(kind, start, scan.end, value = scan.value)
        }
        return scan.end
    }

    fun readMySqlString(start: Int): Int {
        val escaped = scanQuoted('\'', start, backslashEscapes = true)
        val plain = scanQuoted('\'', start, backslashEscapes = false)
        val chosen =
            when (mySqlBackslashEscapes) {
                true -> escaped
                false -> plain
                // Unknown session mode: only accept the string when both readings agree (no
                // backslash, or only the \% and \_ forms that decode identically); anything else
                // would silently run a different comparison than the server sees.
                null ->
                    if (escaped.end == plain.end && escaped.value == plain.value) {
                        escaped
                    } else {
                        val end = maxOf(escaped.end, plain.end)
                        add(
                            SqlTokenType.Error,
                            start,
                            end,
                            error = SqlMessages.MYSQL_BACKSLASH_AMBIGUOUS,
                        )
                        return end
                    }
            }
        if (chosen.value == null) {
            add(SqlTokenType.Error, start, chosen.end, error = "Unterminated string")
        } else {
            add(SqlTokenType.StringLiteral, start, chosen.end, value = chosen.value)
        }
        return chosen.end
    }

    while (i < sql.length) {
        val c = sql[i]
        when {
            c.isWhitespace() -> {
                var j = i
                while (j < sql.length && sql[j].isWhitespace()) j++
                add(SqlTokenType.Whitespace, i, j)
                i = j
            }
            // MySQL only starts a comment when -- is followed by whitespace or a control character;
            // `--2` there is two unary minuses, not a comment that would swallow the rest of the
            // line.
            c == '-' &&
                i + 1 < sql.length &&
                sql[i + 1] == '-' &&
                (dialect != Dialect.MySql ||
                    i + 2 >= sql.length ||
                    sql[i + 2].isWhitespace() ||
                    sql[i + 2].isISOControl()) -> {
                var j = i
                // Stop at CR or LF; do not consume the LF of CRLF (whitespace handles it).
                while (j < sql.length && sql[j] != '\n' && sql[j] != '\r') j++
                add(SqlTokenType.Comment, i, j)
                i = j
            }
            c == '#' && dialect == Dialect.MySql -> {
                var j = i
                while (j < sql.length && sql[j] != '\n' && sql[j] != '\r') j++
                add(SqlTokenType.Comment, i, j)
                i = j
            }
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                // PostgreSQL and SQL Server nest block comments; MySQL and Oracle close at the
                // first */, so `/* /* */` already ends the comment there.
                val nested = dialect == Dialect.Postgres || dialect == Dialect.Mssql
                val end = blockCommentEnd(sql, i, nested)
                val marker = sql.getOrNull(i + 2)
                if (end < 0) {
                    add(SqlTokenType.Error, i, sql.length, error = "Unterminated comment")
                    i = sql.length
                } else if (marker == '!' || marker == '+') {
                    // /*! … */ is executed by MySQL and /*+ … */ carries optimizer hints.
                    // Discarding either as an ordinary comment would silently reinterpret the
                    // statement, so reject it rather than quietly dropping what the user wrote.
                    add(
                        SqlTokenType.Error,
                        i,
                        end,
                        error =
                            if (marker == '!') SqlMessages.MYSQL_EXEC_COMMENT
                            else SqlMessages.OPTIMIZER_HINT,
                    )
                    i = end
                } else {
                    add(SqlTokenType.Comment, i, end)
                    i = end
                }
            }
            // N'…' is a national string literal; without this it tokenizes as identifier N plus a
            // string and fails downstream as an unsupported column comparison.
            (c == 'N' || c == 'n') &&
                sql.getOrNull(i + 1) == '\'' &&
                (dialect == Dialect.Mssql || dialect == Dialect.Postgres) -> {
                val end = scanQuoted('\'', i + 1, backslashEscapes = false).end
                add(SqlTokenType.Error, i, end, error = SqlMessages.NATIONAL_STRING)
                i = end
            }
            c == '\'' ->
                i =
                    if (dialect == Dialect.MySql) readMySqlString(i)
                    else readQuoted('\'', i, SqlTokenType.StringLiteral, "string")
            c == '"' ->
                if (dialect == Dialect.Postgres || dialect == Dialect.Oracle) {
                    i = readQuoted('"', i, SqlTokenType.QuotedIdentifier, "quoted identifier")
                } else {
                    var j = i + 1
                    while (j < sql.length && sql[j] != '"') j++
                    val end = if (j < sql.length) j + 1 else sql.length
                    add(SqlTokenType.Error, i, end, error = quoteStyleError(dialect))
                    i = end
                }
            c == '`' ->
                if (dialect == Dialect.MySql) {
                    i = readQuoted('`', i, SqlTokenType.QuotedIdentifier, "quoted identifier")
                } else {
                    var j = i + 1
                    while (j < sql.length && sql[j] != '`') j++
                    val end = if (j < sql.length) j + 1 else sql.length
                    add(SqlTokenType.Error, i, end, error = quoteStyleError(dialect))
                    i = end
                }
            c == '[' ->
                if (dialect == Dialect.Mssql) {
                    val body = StringBuilder()
                    var j = i + 1
                    var closed = -1
                    while (j < sql.length) {
                        if (sql[j] == ']') {
                            if (j + 1 < sql.length && sql[j + 1] == ']') {
                                body.append(']')
                                j += 2
                                continue
                            }
                            closed = j
                            break
                        }
                        body.append(sql[j])
                        j++
                    }
                    if (closed < 0) {
                        add(
                            SqlTokenType.Error,
                            i,
                            sql.length,
                            error = "Unterminated quoted identifier",
                        )
                        i = sql.length
                    } else {
                        add(SqlTokenType.QuotedIdentifier, i, closed + 1, value = body.toString())
                        i = closed + 1
                    }
                } else {
                    var j = i + 1
                    while (j < sql.length && sql[j] != ']') j++
                    val end = if (j < sql.length) j + 1 else sql.length
                    add(SqlTokenType.Error, i, end, error = quoteStyleError(dialect))
                    i = end
                }
            c.isDigit() -> {
                var j = i
                while (j < sql.length && sql[j].isDigit()) j++
                if (j < sql.length && sql[j] == '.' && j + 1 < sql.length && sql[j + 1].isDigit()) {
                    j++
                    while (j < sql.length && sql[j].isDigit()) j++
                }
                add(SqlTokenType.NumberLiteral, i, j)
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (
                    j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '$')
                ) j++
                val word = sql.substring(i, j)
                val type =
                    if (sqlWord(word) in keywords) SqlTokenType.Keyword else SqlTokenType.Identifier
                add(type, i, j)
                i = j
            }
            c == ',' -> {
                add(SqlTokenType.Comma, i, i + 1)
                i++
            }
            c == '.' -> {
                add(SqlTokenType.Dot, i, i + 1)
                i++
            }
            c == '(' -> {
                add(SqlTokenType.LeftParen, i, i + 1)
                i++
            }
            c == ')' -> {
                add(SqlTokenType.RightParen, i, i + 1)
                i++
            }
            c == ';' -> {
                add(SqlTokenType.Semicolon, i, i + 1)
                i++
            }
            c == '*' -> {
                add(SqlTokenType.Star, i, i + 1)
                i++
            }
            c == '<' -> {
                val end =
                    when {
                        i + 1 < sql.length && (sql[i + 1] == '>' || sql[i + 1] == '=') -> i + 2
                        else -> i + 1
                    }
                add(SqlTokenType.Operator, i, end)
                i = end
            }
            c == '>' -> {
                val end = if (i + 1 < sql.length && sql[i + 1] == '=') i + 2 else i + 1
                add(SqlTokenType.Operator, i, end)
                i = end
            }
            c == '=' -> {
                add(SqlTokenType.Operator, i, i + 1)
                i++
            }
            c == '!' ->
                if (i + 1 < sql.length && sql[i + 1] == '=') {
                    add(SqlTokenType.Operator, i, i + 2)
                    i += 2
                } else {
                    add(SqlTokenType.Error, i, i + 1, error = "Unexpected character '!'")
                    i++
                }
            c == '+' || c == '-' || c == '/' || c == '%' || c == '|' || c == '&' || c == '^' -> {
                add(SqlTokenType.Operator, i, i + 1)
                i++
            }
            else -> {
                add(SqlTokenType.Error, i, i + 1, error = "Unexpected character '$c'")
                i++
            }
        }
    }
    return tokens
}

internal fun quoteStyleError(dialect: Dialect): String =
    "That quoting style isn't valid here — ${dialectDisplay(dialect)} quotes identifiers with ${quoteHint(dialect)}"

internal fun dialectDisplay(dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> "PostgreSQL"
        Dialect.MySql -> "MySQL"
        Dialect.Mssql -> "SQL Server"
        Dialect.Oracle -> "Oracle"
    }

fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var lineStart = 0
    val bounded = offset.coerceIn(0, text.length)
    for (i in 0 until bounded) {
        val ch = text[i]
        // \n and a bare \r each end a line; the \n of a \r\n pair is the same line break.
        if (ch == '\n' || (ch == '\r' && text.getOrNull(i + 1) != '\n')) {
            line++
            lineStart = i + 1
        }
    }
    return line to (bounded - lineStart + 1)
}

private fun blockCommentEnd(sql: String, start: Int, nested: Boolean): Int {
    var depth = 1
    var j = start + 2
    while (j + 2 <= sql.length) {
        when {
            nested && sql[j] == '/' && sql[j + 1] == '*' -> {
                depth++
                j += 2
            }
            sql[j] == '*' && sql[j + 1] == '/' -> {
                depth--
                j += 2
                if (depth == 0) return j
            }
            else -> j++
        }
    }
    return -1
}

// True/false when the connection's Connector/J sessionVariables property pins sql_mode, making
// the session's string-escape semantics known; null when the server's global sql_mode could still
// enable NO_BACKSLASH_ESCAPES, so parsing must treat mode-dependent strings as ambiguous.
fun mySqlBackslashEscapes(connection: ConnectionDef): Boolean? {
    if (connection.dialect != Dialect.MySql) return null
    val sessionVariables =
        connection.driverProperties
            .firstOrNull { it.name.equals("sessionVariables", ignoreCase = true) }
            ?.value ?: return null
    if (!sessionVariables.contains("sql_mode", ignoreCase = true)) return null
    return !sessionVariables.contains("NO_BACKSLASH_ESCAPES", ignoreCase = true)
}
