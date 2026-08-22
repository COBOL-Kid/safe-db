package com.safedb.query.sql

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlTokenizerTest {
    private fun significant(
        sql: String,
        dialect: Dialect = Dialect.Postgres,
        mySqlBackslashEscapes: Boolean? = null,
    ) =
        tokenizeSql(sql, dialect, mySqlBackslashEscapes).filter {
            it.type != SqlTokenType.Whitespace
        }

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
    fun lineColCountsBareCarriageReturnsAndCrLfPairs() {
        assertEquals(2 to 1, lineColOf("SELECT id\rFROM t", 10))
        assertEquals(2 to 5, lineColOf("SELECT id\rFROM t", 14))

        val crlf = "SELECT id\r\nFROM t"
        assertEquals(1 to 10, lineColOf(crlf, 9))
        assertEquals(2 to 1, lineColOf(crlf, 11))
        assertEquals(2 to 5, lineColOf(crlf, 15))
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
    fun mysqlBackslashEscapesInStringsWhenSessionModeIsPinned() {
        val quote = significant("'it\\'s'", Dialect.MySql, mySqlBackslashEscapes = true).single()
        assertEquals(SqlTokenType.StringLiteral, quote.type)
        assertEquals("it's", quote.value)

        val backslash =
            significant("'a\\\\b'", Dialect.MySql, mySqlBackslashEscapes = true).single()
        assertEquals("a\\b", backslash.value)

        val percent = significant("'100\\%'", Dialect.MySql, mySqlBackslashEscapes = true).single()
        assertEquals("100\\%", percent.value)
        assertEquals(5, percent.value.length)

        val underscore =
            significant("'100\\_'", Dialect.MySql, mySqlBackslashEscapes = true).single()
        assertEquals("100\\_", underscore.value)
        assertEquals(5, underscore.value.length)
    }

    @Test
    fun mysqlNoBackslashEscapesModeTakesBackslashLiterally() {
        val literal = significant("'a\\q'", Dialect.MySql, mySqlBackslashEscapes = false).single()
        assertEquals(SqlTokenType.StringLiteral, literal.type)
        assertEquals("a\\q", literal.value)

        val doubled = significant("'a\\\\b'", Dialect.MySql, mySqlBackslashEscapes = false).single()
        assertEquals("a\\\\b", doubled.value)
    }

    @Test
    fun mysqlAmbiguousBackslashIsRejectedWhenSessionModeIsUnknown() {
        val literal = significant("'a\\q'", Dialect.MySql).single()
        assertEquals(SqlTokenType.Error, literal.type)
        assertEquals(SqlMessages.MYSQL_BACKSLASH_AMBIGUOUS, literal.error)

        // \' is an escaped quote in one mode and a string terminator in the other.
        val quote = significant("'it\\'s'", Dialect.MySql).first()
        assertEquals(SqlTokenType.Error, quote.type)
        assertEquals(SqlMessages.MYSQL_BACKSLASH_AMBIGUOUS, quote.error)
    }

    @Test
    fun mysqlEscapedWildcardsStayAcceptedWhenSessionModeIsUnknown() {
        // \% and \_ decode identically under both sql_mode settings, so LIKE escapes keep working.
        val percent = significant("'100\\%'", Dialect.MySql).single()
        assertEquals(SqlTokenType.StringLiteral, percent.type)
        assertEquals("100\\%", percent.value)

        val plain = significant("'plain'", Dialect.MySql).single()
        assertEquals(SqlTokenType.StringLiteral, plain.type)
        assertEquals("plain", plain.value)
    }

    @Test
    fun mysqlBackslashEscapesDerivesFromSessionVariables() {
        fun connection(vararg properties: DriverProperty) =
            ConnectionDef(
                id = "c1",
                name = "Local",
                dialect = Dialect.MySql,
                host = "localhost",
                port = 3306,
                database = "test",
                username = "reader",
                driverProperties = properties.toList(),
            )

        assertNull(mySqlBackslashEscapes(connection()))
        assertNull(
            mySqlBackslashEscapes(connection(DriverProperty("sessionVariables", "wait_timeout=60")))
        )
        assertEquals(
            true,
            mySqlBackslashEscapes(
                connection(DriverProperty("sessionVariables", "sql_mode='STRICT_TRANS_TABLES'"))
            ),
        )
        assertEquals(
            false,
            mySqlBackslashEscapes(
                connection(
                    DriverProperty(
                        "sessionVariables",
                        "sql_mode='STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES'",
                    )
                )
            ),
        )
        assertNull(
            mySqlBackslashEscapes(
                connection(DriverProperty("sessionVariables", "sql_mode='ANSI'"))
                    .copy(dialect = Dialect.Postgres)
            )
        )
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
        assertTrue(
            tokenizeSql("SELECT id FROM t WHERE a = --2", Dialect.Postgres).any {
                it.type == SqlTokenType.Comment
            }
        )
    }

    @Test
    fun mysqlBackslashNewlineIsLineContinuation() {
        val token =
            significant("'foo\\\nbar'", Dialect.MySql, mySqlBackslashEscapes = true).single()
        assertEquals(SqlTokenType.StringLiteral, token.type)
        assertEquals("foobar", token.value)
    }

    @Test
    fun nestedBlockCommentsCloseAtTheMatchingTerminator() {
        // The whole input is one comment on dialects with nesting; the inner SELECT stays inert.
        val input = "/* /* */ SELECT id FROM users -- */"
        for (dialect in listOf(Dialect.Postgres, Dialect.Mssql)) {
            val tokens = tokenizeSql(input, dialect)
            assertEquals(SqlTokenType.Comment, tokens.single().type)
        }

        // MySQL and Oracle close at the first */, so the SELECT is live again.
        for (dialect in listOf(Dialect.MySql, Dialect.Oracle)) {
            val tokens = significant(input, dialect)
            assertEquals(SqlTokenType.Comment, tokens.first().type)
            assertTrue(
                tokens.any { it.type == SqlTokenType.Keyword && sqlWord(it.text) == "SELECT" }
            )
        }
    }

    @Test
    fun nestedBlockCommentBeforeARealQueryTokenizes() {
        val tokens = significant("/* /* inner */ outer */ SELECT id", Dialect.Postgres)
        assertEquals(SqlTokenType.Comment, tokens.first().type)
        assertEquals("/* /* inner */ outer */", tokens.first().text)
        assertTrue(tokens.any { it.type == SqlTokenType.Keyword && sqlWord(it.text) == "SELECT" })

        val unterminated = tokenizeSql("/* /* */ SELECT", Dialect.Postgres).single()
        assertEquals(SqlTokenType.Error, unterminated.type)
        assertEquals("Unterminated comment", unterminated.error)
    }

    @Test
    fun sqlWordUppercasesWithRootLocale() {
        assertEquals("LIKE", sqlWord("like"))
    }
}
