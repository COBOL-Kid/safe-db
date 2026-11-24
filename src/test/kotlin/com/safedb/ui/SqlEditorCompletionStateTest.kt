package com.safedb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.safedb.model.ColumnInfo
import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.query.sql.SqlCompletionRequest
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.theme.SafeDbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                    ),
                ),
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
            shouldAutoOpenCompletion(request(dotText, caret = "SELECT u.".length), typed = true),
        )
    }

    @Test
    fun spaceOpensInTableAndColumnContexts() {
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM "), typed = true))
        val selectGap = "SELECT  FROM users"
        assertTrue(
            shouldAutoOpenCompletion(request(selectGap, caret = "SELECT ".length), typed = true),
        )
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM users WHERE "), typed = true))
    }

    @Test
    fun spaceStaysClosedWithoutRealSuggestions() {
        // Keyword-only contexts: no FROM table resolves, or the previous token is an identifier.
        assertFalse(shouldAutoOpenCompletion(request("SELECT "), typed = true))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id "), typed = true))
        assertFalse(
            shouldAutoOpenCompletion(request("SELECT id FROM ", schema = null), typed = true),
        )
        assertFalse(shouldAutoOpenCompletion(request("SELECT 'a '"), typed = true))
    }

    @Test
    fun caretMoveUsesOnlyTheWhitespaceProbe() {
        assertTrue(shouldAutoOpenCompletion(request("SELECT id FROM "), typed = false))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id FROM u"), typed = false))
        assertFalse(shouldAutoOpenCompletion(request("SELECT id "), typed = false))
    }

    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    @Test
    fun completionPopupAllowsFirstClickOnRunAndStillInsertsClickedCompletion() {
        var value by mutableStateOf(TextFieldValue("SELECT id FROM u", TextRange(16)))
        var runCount = 0

        withComposeScene(
            width = 640,
            height = 420,
            content = {
                SafeDbTheme(isDark = false) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PrimaryButton(onClick = { runCount += 1 }) { Text("Run") }
                        SqlEditor(
                            value = value,
                            onValueChange = { value = it },
                            dialect = Dialect.Postgres,
                            schema = schema,
                            defaultSchema = "public",
                            enabled = true,
                            onRun = { runCount += 1 },
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                        )
                    }
                }
            },
        ) { scene ->
            // Focus editor and open completion via Ctrl+Space.
            val editor = scene.requireContaining("SELECT id FROM u")
            editor.config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.settle()
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.settle()
            assertNotNull(scene.findContaining("T, users"))

            // Clicking Run fires on the very first click even while the completion popup is open,
            // and closes the popup.
            val runButton = scene.requireExact("Run")
            scene.click(runButton.boundsInRoot.center)
            scene.settle()
            assertEquals(1, runCount)
            assertNull(scene.findContaining("T, users"))

            // Re-open completion and click the suggestion item to verify insertion still works.
            editor.config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.settle()
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.settle()
            val suggestion = scene.requireContaining("T, users")
            scene.click(suggestion.boundsInRoot.center)
            scene.settle()
            assertEquals("SELECT id FROM users", value.text)
            assertNull(scene.findContaining("T, users"))

            // Clicking a schema completion ending with '.' inserts it and keeps the popup open for
            // table members without losing editor focus.
            value = TextFieldValue("SELECT id FROM pub", TextRange(18))
            scene.settle()
            editor.config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.settle()
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.settle()
            val schemaSuggestion = scene.requireContaining("S, public.")
            scene.click(schemaSuggestion.boundsInRoot.center)
            scene.settle()
            assertEquals("SELECT id FROM public.", value.text)
            assertNotNull(scene.findContaining("T, users"))
        }
    }
}
