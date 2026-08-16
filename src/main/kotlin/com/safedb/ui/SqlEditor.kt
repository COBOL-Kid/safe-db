package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.query.sql.SqlCompletionItem
import com.safedb.query.sql.SqlCompletionKind
import com.safedb.query.sql.SqlCompletionRequest
import com.safedb.query.sql.sqlCompletions
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme

private val EditorPadding = 12.dp

@Composable
internal fun SqlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    dialect: Dialect?,
    schema: Schema?,
    defaultSchema: String?,
    enabled: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberSqlHighlightScheme()
    var completionOpen by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current

    val completion =
        remember(value.text, value.selection, dialect, schema, defaultSchema) {
            dialect?.let {
                sqlCompletions(
                    SqlCompletionRequest(
                        text = value.text,
                        caret = value.selection.start,
                        dialect = it,
                        schema = schema,
                        defaultSchema = defaultSchema,
                    )
                )
            }
        }
    val items = completion?.items.orEmpty()
    val popupVisible = completionOpen && enabled && items.isNotEmpty() && value.selection.collapsed
    LaunchedEffect(items) { selectedIndex = 0 }

    fun applyCompletion(item: SqlCompletionItem) {
        val result = completion ?: return
        val newText =
            value.text.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)
        val caret = result.replaceStart + item.insertText.length
        onValueChange(TextFieldValue(newText, TextRange(caret)))
        // Alias/schema completions end with '.', so keep the popup open for the member list.
        completionOpen = item.insertText.endsWith(".")
    }

    Box(
        modifier =
            modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
    ) {
        BasicTextField(
            value = value,
            onValueChange = { updated ->
                val typedText = updated.text != value.text
                onValueChange(updated)
                if (typedText) {
                    completionOpen =
                        updated.text.length > value.text.length &&
                            shouldAutoOpenCompletion(
                                updated.text,
                                updated.selection.start,
                            )
                }
            },
            enabled = enabled,
            modifier =
                Modifier.fillMaxSize().padding(EditorPadding).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val shortcut = event.isCtrlPressed || event.isMetaPressed
                    when {
                        shortcut && event.key == Key.Enter -> {
                            onRun()
                            true
                        }
                        shortcut && event.key == Key.Spacebar -> {
                            completionOpen = true
                            true
                        }
                        popupVisible && event.key == Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(items.lastIndex)
                            true
                        }
                        popupVisible && event.key == Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        popupVisible && (event.key == Key.Enter || event.key == Key.Tab) -> {
                            items.getOrNull(selectedIndex)?.let(::applyCompletion)
                            true
                        }
                        popupVisible && event.key == Key.Escape -> {
                            completionOpen = false
                            true
                        }
                        else -> false
                    }
                },
            textStyle = DataMono.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(SafeDbTheme.colors.actionPrimary),
            visualTransformation =
                dialect?.let { SqlSyntaxTransformation(it, scheme) } ?: VisualTransformationNone,
            onTextLayout = { layoutResult = it },
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            "SELECT … FROM table WHERE …   (Ctrl/Cmd+Enter runs, Ctrl/Cmd+Space completes)",
                            style = DataMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )

        if (popupVisible) {
            val caretRect =
                layoutResult
                    ?.takeIf { it.layoutInput.text.text == value.text }
                    ?.getCursorRect(value.selection.start.coerceIn(0, value.text.length))
            val paddingPx = with(density) { EditorPadding.roundToPx() }
            val offset =
                IntOffset(
                    x = paddingPx + (caretRect?.left?.toInt() ?: 0),
                    y = paddingPx + (caretRect?.bottom?.toInt() ?: 0) + 4,
                )
            Popup(offset = offset, onDismissRequest = { completionOpen = false }) {
                CompletionList(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = ::applyCompletion,
                )
            }
        }
    }
}

private val VisualTransformationNone = androidx.compose.ui.text.input.VisualTransformation.None

// Digits must not open the popup (`1.` / `1.0`); `.` only after a name or quote closer.
private fun shouldAutoOpenCompletion(text: String, caret: Int): Boolean {
    val last = text.getOrNull(caret - 1) ?: return false
    if (last.isLetter() || last == '_') return true
    if (last != '.') return false
    val prev = text.getOrNull(caret - 2) ?: return false
    return prev.isLetter() || prev == '_' || prev == '"' || prev == '`' || prev == ']'
}

@Composable
private fun CompletionList(
    items: List<SqlCompletionItem>,
    selectedIndex: Int,
    onSelect: (SqlCompletionItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, items) {
        if (items.isNotEmpty()) listState.scrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(min = 220.dp, max = 340.dp).heightIn(max = 216.dp),
        ) {
            itemsIndexed(items) { index, item ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(
                                if (index == selectedIndex) {
                                    SafeDbTheme.colors.navigationSelected
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        completionKindTag(item.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = completionKindColor(item.kind),
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        item.label,
                        style = DataMono,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.detail?.let { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).widthIn(max = 110.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun completionKindTag(kind: SqlCompletionKind): String =
    when (kind) {
        SqlCompletionKind.Keyword -> "K"
        SqlCompletionKind.Table -> "T"
        SqlCompletionKind.Column -> "C"
        SqlCompletionKind.Alias -> "A"
        SqlCompletionKind.SchemaName -> "S"
    }

@Composable
private fun completionKindColor(kind: SqlCompletionKind) =
    when (kind) {
        SqlCompletionKind.Keyword -> SafeDbTheme.colors.actionPrimary
        SqlCompletionKind.Table -> SafeDbTheme.colors.info
        SqlCompletionKind.Column -> SafeDbTheme.colors.success
        SqlCompletionKind.Alias -> SafeDbTheme.colors.uq
        SqlCompletionKind.SchemaName -> MaterialTheme.colorScheme.onSurfaceVariant
    }
