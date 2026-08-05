package com.safedb.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.safedb.ui.theme.LabelMicro
import java.util.Locale

/**
 * Uppercase technical micro-label for section headers like "FILTER WHERE", "INDEXES", "SAVED
 * QUERIES".
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = LabelMicro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
