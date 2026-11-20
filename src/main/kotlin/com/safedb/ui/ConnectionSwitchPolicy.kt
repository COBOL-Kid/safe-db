package com.safedb.ui

import androidx.compose.runtime.Composable
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ConnectionDef
import com.safedb.model.Settings
import com.safedb.resolveConnectionSchemaSelection
import com.safedb.ui.components.ConfirmDialog
import com.safedb.viewmodel.AppViewModel

internal data class ConnectionSchemaHandlers(
    val onConnectionSelected: (ConnectionDef) -> Unit,
    val onSchemaSelected: (String) -> Unit,
    val onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    val onDismissSchemaHistoryError: () -> Unit,
)

internal data class PendingConnectionSwitch(
    val target: ConnectionDef,
    val thenNavigate: AppRoute?,
)

internal fun connectionSchemaHandlers(
    appState: AppState,
    viewModel: AppViewModel,
    settings: Settings,
    activeConnectionId: String?,
): ConnectionSchemaHandlers =
    ConnectionSchemaHandlers(
        onConnectionSelected = { connection ->
            appState.setActiveConnection(
                connection.id,
                resolveConnectionSchemaSelection(connection.id, settings),
            )
        },
        onSchemaSelected = { schema ->
            activeConnectionId?.let { connectionId ->
                appState.setActiveSchema(schema)
                viewModel.settings.rememberLastSchema(connectionId, schema)
            }
        },
        onUnavailableSchemaSelection = { selection ->
            activeConnectionId?.let { connectionId ->
                if (selection.source == SchemaSelectionSource.ConnectionHistory) {
                    viewModel.settings.forgetLastSchema(connectionId)
                }
            }
        },
        onDismissSchemaHistoryError = viewModel.settings::clearSchemaHistoryError,
    )

@Composable
internal fun ConnectionSwitchConfirmDialog(
    pending: PendingConnectionSwitch?,
    onConfirm: (PendingConnectionSwitch) -> Unit,
    onCancel: () -> Unit,
) {
    pending?.let { switch ->
        ConfirmDialog(
            open = true,
            title = "Switch connection?",
            message =
                "Switching to ${switch.target.name} clears the current query canvas and results. " +
                    "Saved queries are not affected.",
            confirmLabel = "Switch and clear",
            onConfirm = { onConfirm(switch) },
            onCancel = onCancel,
        )
    }
}
