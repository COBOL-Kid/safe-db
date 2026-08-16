package com.safedb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.safedb.model.Dialect
import com.safedb.query.sql.SqlTokenType
import com.safedb.query.sql.tokenizeSql
import com.safedb.ui.theme.SafeDbTheme

internal data class SqlHighlightScheme(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val quotedIdentifier: Color,
    val error: Color,
)

@Composable
internal fun rememberSqlHighlightScheme(): SqlHighlightScheme {
    val colors = SafeDbTheme.colors
    val errorColor = MaterialTheme.colorScheme.error
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(colors, errorColor, commentColor) {
        SqlHighlightScheme(
            keyword = colors.actionPrimary,
            string = colors.success,
            number = colors.uq,
            comment = commentColor,
            quotedIdentifier = colors.info,
            error = errorColor,
        )
    }
}

internal fun highlightSql(
    text: String,
    dialect: Dialect,
    scheme: SqlHighlightScheme,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (token in tokenizeSql(text, dialect)) {
        val style =
            when (token.type) {
                SqlTokenType.Keyword -> SpanStyle(color = scheme.keyword)
                SqlTokenType.StringLiteral -> SpanStyle(color = scheme.string)
                SqlTokenType.NumberLiteral -> SpanStyle(color = scheme.number)
                SqlTokenType.Comment ->
                    SpanStyle(color = scheme.comment, fontStyle = FontStyle.Italic)
                SqlTokenType.QuotedIdentifier -> SpanStyle(color = scheme.quotedIdentifier)
                SqlTokenType.Error ->
                    SpanStyle(color = scheme.error, textDecoration = TextDecoration.Underline)
                else -> null
            }
        if (style != null) addStyle(style, token.span.start, token.span.end)
    }
}

// Highlighting never changes text length, so identity offset mapping is correct.
internal class SqlSyntaxTransformation(
    private val dialect: Dialect,
    private val scheme: SqlHighlightScheme,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightSql(text.text, dialect, scheme), OffsetMapping.Identity)
}
