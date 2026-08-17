package com.safedb.ui

import com.safedb.model.ColumnInfo
import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.query.sql.SqlCompletionRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlEditorCompletionStateTest {
    private val schema =
        Schema(
            tables =
                listOf(
                    TableInfo(
                        schema = "public",
                        name = "users",
                        columns =
                            listOf(
                                ColumnInfo("id", "int", nullable = false),
                                ColumnInfo("email", "text", nullable = true),
                            ),
                        indexes = emptyList(),
                    )
                )
        )

    private fun request(
        text: String,
        caret: Int = text.length,
        schema: Schema? = this.schema,
    ): SqlCompletionRequest =
        SqlCompletionRequest(
            text = text,
            caret = caret,
            dialect = Dialect.Postgres,
            schema = schema,
            defaultSchema = "public",
        )

    @Test
    fun typingIdentifierCharsOpensButDigitsDoNot() {
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM u"), typed = true))
        assertFalse(shouldAutoOpenCompletion(request("SELECT 1"), typed = true))
        assertFalse(shouldAutoOpenCompletion(request("SELECT 1."), typed = true))
        val dotText = "SELECT u. FROM users u"
        assertTrue(
            shouldAutoOpenCompletion(request(dotText, caret = "SELECT u.".length), typed = true)
        )
    }

    @Test
    fun spaceOpensInTableAndColumnContexts() {
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM "), typed = true))
        val selectGap = "SELECT  FROM users"
        assertTrue(
            shouldAutoOpenCompletion(request(selectGap, caret = "SELECT ".length), typed = true)
        )
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM users WHERE "), typed = true))
    }

    @Test
    fun spaceStaysClosedWithoutRealSuggestions() {
        // Keyword-only contexts: no FROM table resolves, or the previous token is an identifier.
        assertFalse(shouldAutoOpenCompletion(request("SELECT "), typed = true))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id "), typed = true))
        assertFalse(
            shouldAutoOpenCompletion(request("SELECT id FROM ", schema = null), typed = true)
        )
        assertFalse(shouldAutoOpenCompletion(request("SELECT 'a '"), typed = true))
    }

    @Test
    fun caretMoveUsesOnlyTheWhitespaceProbe() {
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM "), typed = false))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id FROM u"), typed = false))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id "), typed = false))
    }
}
