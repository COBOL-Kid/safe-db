package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.model.ThemePalette
import com.safedb.ui.theme.CardShape
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.darkScheme
import com.safedb.ui.theme.lightScheme

@Composable
fun ModeToggle(
    isDark: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, CardShape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ModeSegment("Light", selected = !isDark, onClick = { onSelect(false) }) {
            Icon(Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        ModeSegment("Dark", selected = isDark, onClick = { onSelect(true) }) {
            Icon(Icons.Outlined.DarkMode, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        selected,
        onClick,
        shape = ChipShape,
        color = if (selected) SafeDbTheme.colors.accentContainer else Color.Transparent,
        contentColor = if (selected) {
            SafeDbTheme.colors.onAccentContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun ColorSchemePicker(
    selected: ThemePalette,
    isDark: Boolean,
    onSelect: (ThemePalette) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemePalette.entries.forEach { palette ->
            ColorSchemeRow(
                palette = palette,
                isDark = isDark,
                selected = palette == selected,
                onClick = { onSelect(palette) },
            )
        }
    }
}

@Composable
private fun ColorSchemeRow(
    palette: ThemePalette,
    isDark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = when {
        selected -> SafeDbTheme.colors.actionPrimary
        hovered -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        selected,
        onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .semantics { this.selected = selected },
        shape = CardShape,
        color = if (selected) SafeDbTheme.colors.accentContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ColorSchemeSwatch(palette, isDark)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    palette.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        SafeDbTheme.colors.onAccentContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    palette.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = SafeDbTheme.colors.actionPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorSchemeSwatch(palette: ThemePalette, isDark: Boolean) {
    val resolved = remember(palette, isDark) {
        if (isDark) darkScheme(palette) else lightScheme(palette)
    }
    val material = resolved.first
    val colors = resolved.second
    Row(
        modifier = Modifier
            .size(width = 64.dp, height = 42.dp)
            .background(colors.workspaceBackground, CardShape)
            .border(1.dp, material.outline, CardShape),
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 42.dp)
                .background(colors.navigationBackground),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .background(colors.workspaceHeader, ChipShape),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(15.dp)
                        .background(material.surface, ChipShape),
                )
                Box(
                    modifier = Modifier
                        .size(width = 12.dp, height = 15.dp)
                        .background(colors.actionPrimary, ChipShape),
                )
            }
        }
    }
}
