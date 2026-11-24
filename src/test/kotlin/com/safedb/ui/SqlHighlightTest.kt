package com.safedb.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import com.safedb.model.Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlHighlightTest {
    private val scheme =
        SqlHighlightScheme(
            keyword = Color.Blue,
            string = Color.Green,
            number = Color.Magenta,
            comment = Color.Gray,
            quotedIdentifier = Color.Cyan,
            error = Color.Red,
        )

    @Test
    fun highlightSqlAppliesExpectedSpanStylesAcrossTokensAndDialects() {
        val sql = "SELECT `id`, 'a\\'b', 42 -- note\n'unterminated"
        val highlighted = highlightSql(sql, Dialect.MySql, scheme, mySqlBackslashEscapes = true)

        assertEquals(sql, highlighted.text)
        assertTrue(highlighted.spanStyles.any { it.item.color == scheme.keyword })
        assertTrue(highlighted.spanStyles.any { it.item.color == scheme.quotedIdentifier })
        assertTrue(highlighted.spanStyles.any { it.item.color == scheme.string })
        assertTrue(highlighted.spanStyles.any { it.item.color == scheme.number })
        assertTrue(
            highlighted.spanStyles.any {
                it.item.color == scheme.comment && it.item.fontStyle == FontStyle.Italic
            },
        )
        assertTrue(
            highlighted.spanStyles.any {
                it.item.color == scheme.error && it.item.textDecoration == TextDecoration.Underline
            },
        )

        val pgSql = "SELECT \"id\""
        val transformed =
            SqlSyntaxTransformation(Dialect.Postgres, scheme).filter(AnnotatedString(pgSql))
        assertEquals(pgSql, transformed.text.text)
        assertTrue(transformed.text.spanStyles.any { it.item.color == scheme.quotedIdentifier })
    }
}
