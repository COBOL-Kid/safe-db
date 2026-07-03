package com.safedb.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.LabelMicro
import com.safedb.ui.theme.PillShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.model.GroupConnector

/**
 * AND/OR connector pill between filter group children. AND uses the neutral
 * primary container; OR uses warning amber. Clickable to toggle.
 */
@Composable
fun AndOrConnector(
    connector: GroupConnector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SafeDbTheme.colors
    val (container, content) = if (connector == GroupConnector.And) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        c.warningContainer to c.onWarningContainer
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        shape = PillShape,
        elevation = ButtonDefaults.elevatedButtonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(
            connector.name.uppercase(),
            style = LabelMicro,
        )
    }
}
