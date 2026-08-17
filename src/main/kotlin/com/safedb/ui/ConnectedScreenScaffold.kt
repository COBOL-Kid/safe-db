package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safedb.SchemaSelectionIntent
import com.safedb.model.ConnectionDef
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.ConnectionPicker
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.SchemaPicker
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.ToolbarHeaderVerticalPadding
import com.safedb.viewmodel.SchemaViewModel

// Shared chrome for the connected screens (SQL, Map): title block, connection and schema pickers,
// and a trailing divider. Screen-specific controls slot in via trailingActions/bottomContent.
@Composable
internal fun WorkspaceScreenHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    selectedSchema: String?,
    schemaOptions: List<String>,
    contentSpacing: Dp,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onSchemaSelected: (String) -> Unit,
    trailingActions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SafeDbTheme.colors.workspaceHeader)
                .padding(
                    horizontal = ScreenHeaderHorizontalPadding,
                    vertical = ToolbarHeaderVerticalPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = SafeDbTheme.colors.actionPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionPicker(
                    connection = connection,
                    connections = connections,
                    onConnectionSelected = onConnectionSelected,
                )
                SchemaPicker(
                    selectedSchema = selectedSchema,
                    schemaOptions = schemaOptions,
                    onSchemaSelected = onSchemaSelected,
                )
                trailingActions()
            }
        }
        bottomContent()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SchemaHistoryErrorBanner(
    error: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    error ?: return
    MessageBanner(
        text = "Could not remember the selected schema: $error",
        kind = BannerKind.WARNING,
        modifier = modifier,
    ) {
        SecondaryButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// Loads the schema whenever the connection or requested selection changes, falling back to the
// first available schema (with a warning in the returned state) when the remembered one is gone.
@Composable
internal fun rememberSchemaLoad(
    connection: ConnectionDef?,
    schemaViewModel: SchemaViewModel,
    schemaSelection: SchemaSelectionIntent,
    onSchemaSelected: (String) -> Unit,
    onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    retryKey: Any = Unit,
    onNoConnection: () -> Unit = {},
): MutableState<String?> {
    val fallbackWarning = remember(connection?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(connection?.id, schemaSelection, retryKey) {
        val connectionId = connection?.id
        if (connectionId == null) {
            schemaViewModel.clear()
            onNoConnection()
            return@LaunchedEffect
        }
        schemaViewModel.load(
            connectionId = connectionId,
            selection = schemaSelection,
            onUnavailableSelection = onUnavailableSchemaSelection,
        ) { loaded ->
            if (!loaded || schemaViewModel.selectedSchema != null) return@load
            val first = schemaViewModel.schemaOptions.firstOrNull() ?: return@load
            fallbackWarning.value =
                schemaViewModel.preferredSchemaWarning?.let { "$it Showing \"$first\" instead." }
            schemaViewModel.selectSchema(first)
            onSchemaSelected(first)
        }
    }
    return fallbackWarning
}
