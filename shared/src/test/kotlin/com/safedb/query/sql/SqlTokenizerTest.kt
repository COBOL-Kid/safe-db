package com.safedb.query.sql

import com.safedb.model.Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlTokenizerTest {
    private fun significant(sql: String, dialect: Dialect = Dialect.Postgres) =
        tokenizeSql(sql, dialect).filter { it.type != SqlTokenType.Whitespace }

    @Test
    fun classifiesKeywordsCaseInsensitively() {
        val tokens = significant("select Name FROM users")
        assertEquals(
            listOf(
                SqlTokenType.Keyword,
                SqlTokenType.Identifier,
                SqlTokenType.Keyword,
                SqlTokenType.Identifier,
            ),
            tokens.map { it.type },
        )
    }

    @Test
    fun recordsSpans() {
        val tokens = significant("select id")
        assertEquals(SqlSpan(0, 6), tokens[0].span)
        assertEquals(SqlSpan(7, 9), tokens[1].span)
    }

    @Test
    fun postgresDoubleQuotedIdentifierUnescapes() {
        val token = significant("\"we\"\"ird\"").single()
        assertEquals(SqlTokenType.QuotedIdentifier, token.type)
        assertEquals("we\"ird", token.value)
    }

    @Test
    fun mysqlBacktickIdentifierUnescapes() {
        val token = significant("`a``b`", Dialect.MySql).single()
        assertEquals(SqlTokenType.QuotedIdentifier, token.type)
        assertEquals("a`b", token.value)
    }

    @Test
    fun mssqlBracketIdentifierUnescapes() {
        val token = significant("[a]]b]", Dialect.Mssql).single()
        assertEquals(SqlTokenType.QuotedIdentifier, token.type)
        assertEquals("a]b", token.value)
    }

    @Test
    fun wrongQuoteStyleIsAnErrorWithDialectHint() {
        val doubleOnMysql = significant("\"name\"", Dialect.MySql).single()
        assertEquals(SqlTokenType.Error, doubleOnMysql.type)
        assertTrue(doubleOnMysql.error!!.contains("backticks"))

        val bracketOnPostgres = significant("[name]", Dialect.Postgres).single()
        assertEquals(SqlTokenType.Error, bracketOnPostgres.type)
        assertTrue(bracketOnPostgres.error!!.contains("double quotes"))

        val backtickOnMssql = significant("`name`", Dialect.Mssql).single()
        assertEquals(SqlTokenType.Error, backtickOnMssql.type)
        assertTrue(backtickOnMssql.error!!.contains("brackets"))
    }

    @Test
    fun stringLiteralUnescapesDoubledQuotes() {
        val token = significant("'it''s'").single()
        assertEquals(SqlTokenType.StringLiteral, token.type)
        assertEquals("it's", token.value)
    }

    @Test
    fun unterminatedStringIsError() {
        val token = significant("'oops").single()
        assertEquals(SqlTokenType.Error, token.type)
        assertEquals("Unterminated string", token.error)
    }

    @Test
    fun commentsTokenize() {
        val line = tokenizeSql("id -- trailing", Dialect.Postgres)
        assertEquals(SqlTokenType.Comment, line.last().type)

        val block = tokenizeSql("/* x */ id", Dialect.Postgres)
        assertEquals(SqlTokenType.Comment, block.first().type)

        val unterminated = tokenizeSql("/* x", Dialect.Postgres).single()
        assertEquals(SqlTokenType.Error, unterminated.type)
        assertEquals("Unterminated comment", unterminated.error)
    }

    @Test
    fun hashCommentIsMysqlOnly() {
        assertEquals(SqlTokenType.Comment, significant("# note", Dialect.MySql).single().type)
        assertEquals(SqlTokenType.Error, significant("# note", Dialect.Postgres).first().type)
    }

    @Test
    fun multiCharacterOperators() {
        val tokens = significant("a <> 1 >= 2 != 3 <= 4")
        val ops = tokens.filter { it.type == SqlTokenType.Operator }.map { it.text }
        assertEquals(listOf("<>", ">=", "!=", "<="), ops)
    }

    @Test
    fun numbersWithDecimals() {
        val tokens = significant("1 2.5")
        assertEquals(listOf("1", "2.5"), tokens.map { it.text })
        assertTrue(tokens.all { it.type == SqlTokenType.NumberLiteral })
    }

    @Test
    fun lineColConversion() {
        val text = "select id\nfrom users"
        assertEquals(1 to 1, lineColOf(text, 0))
        assertEquals(1 to 8, lineColOf(text, 7))
        assertEquals(2 to 1, lineColOf(text, 10))
        assertEquals(2 to 6, lineColOf(text, 15))
    }

    @Test
    fun noErrorsInWellFormedInput() {
        val tokens =
            tokenizeSql(
                "SELECT u.id FROM public.users AS u WHERE u.name = 'x' LIMIT 5",
                Dialect.Postgres,
            )
        assertNull(tokens.firstOrNull { it.type == SqlTokenType.Error })
    }

    @Test
    fun mysqlBackslashEscapesInStrings() {
        val quote = significant("'it\\'s'", Dialect.MySql).single()
        assertEquals(SqlTokenType.StringLiteral, quote.type)
        assertEquals("it's", quote.value)

        val backslash = significant("'a\\\\b'", Dialect.MySql).single()
        assertEquals("a\\b", backslash.value)
    }

    @Test
    fun ansiDialectsTakeBackslashLiterally() {
        val doubled = significant("'it''s'").single()
        assertEquals("it's", doubled.value)

        val backslash = significant("'a\\\\b'").single()
        assertEquals("a\\\\b", backslash.value)
    }

    @Test
    fun mysqlExecutableCommentIsRejectedNotDiscarded() {
        val token =
            tokenizeSql("SELECT id FROM users /*! WHERE id = 1 */", Dialect.MySql).single {
                it.type == SqlTokenType.Error
            }
        assertEquals(SqlMessages.MYSQL_EXEC_COMMENT, token.error)
    }

    @Test
    fun nationalStringLiteralIsRejected() {
        val token =
            tokenizeSql("SELECT id FROM t WHERE name = N'alice'", Dialect.Mssql).single {
                it.type == SqlTokenType.Error
            }
        assertEquals(SqlMessages.NATIONAL_STRING, token.error)
    }

    @Test
    fun mysqlDashDashNeedsWhitespaceToStartAComment() {
        assertTrue(
            tokenizeSql("SELECT id -- note", Dialect.MySql).any { it.type == SqlTokenType.Comment }
        )
        // MySQL reads `--2` as two unary minuses, not a comment swallowing the rest of the line.
        assertTrue(
            tokenizeSql("SELECT id FROM t WHERE a = --2", Dialect.MySql).none {
                it.type == SqlTokenType.Comment
            }
        )
        // Other dialects keep the ANSI rule.
        assertTrue(
            tokenizeSql("SELECT id FROM t WHERE a = --2", Dialect.Postgres).any {
                it.type == SqlTokenType.Comment
            }
        )
    }
}
