package com.safedb.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.LabelMicro

@Composable
internal fun Kbd(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline,
) {
    Text(
        text = text,
        style = LabelMicro,
        color = contentColor,
        modifier =
            modifier
                .border(1.dp, borderColor, ChipShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
