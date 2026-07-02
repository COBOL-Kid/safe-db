package com.safedb.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.LabelMicro

@Composable
fun Kbd(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = LabelMicro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
