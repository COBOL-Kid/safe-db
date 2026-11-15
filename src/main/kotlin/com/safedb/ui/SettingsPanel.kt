package com.safedb.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.loadAppVersion
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryRiskGate
import com.safedb.model.Settings
import com.safedb.ui.components.ColorSchemePicker
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.ModeToggle
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.ScrollableMenuColumn
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.DialogShape
import com.safedb.viewmodel.SettingsViewModel

internal fun parseQueryTimeoutSeconds(text: String): Int? {
    val seconds = text.trim().toIntOrNull() ?: return null
    return seconds.takeIf {
        it in Settings.MIN_QUERY_TIMEOUT_SECONDS..Settings.MAX_QUERY_TIMEOUT_SECONDS
    }
}

@Composable
fun SettingsPanel(
    open: Boolean,
    viewModel: SettingsViewModel,
    connections: List<ConnectionDef>,
    onDefaultLocationChanged: (String, String) -> Unit,
    onDefaultLocationCleared: () -> Unit,
    onClose: () -> Unit,
) {
    if (!open) return

    val settings by viewModel.settings.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val schemaOptions by viewModel.defaultSchemaOptions.collectAsState()
    val schemaLoading by viewModel.defaultSchemaLoading.collectAsState()
    val schemaError by viewModel.defaultSchemaError.collectAsState()
    val settingsScrollState = rememberScrollState()
    val appVersion = remember { loadAppVersion() }
    var candidateConnectionId by remember { mutableStateOf<String?>(null) }
    var databaseMenuOpen by remember { mutableStateOf(false) }
    var schemaMenuOpen by remember { mutableStateOf(false) }
    var timeoutDraft by remember { mutableStateOf(settings.queryTimeoutSeconds.toString()) }
    var timeoutFocused by remember { mutableStateOf(false) }
    var timeoutError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.clearSaveError()
        candidateConnectionId = settings.defaultConnectionId
        val candidateExists = connections.any { it.id == settings.defaultConnectionId }
        val defaultConnectionId = settings.defaultConnectionId
        if (candidateExists && defaultConnectionId != null) {
            viewModel.loadDefaultSchemaOptions(defaultConnectionId)
        } else {
            viewModel.clearDefaultSchemaOptions()
        }
    }

    LaunchedEffect(settings.queryTimeoutSeconds) {
        // Skip the reset while focused so an invalid draft survives an external seconds change.
        if (!timeoutFocused) {
            timeoutDraft = settings.queryTimeoutSeconds.toString()
            timeoutError = null
        }
    }

    fun commitTimeoutDraft() {
        val seconds = parseQueryTimeoutSeconds(timeoutDraft)
        if (seconds == null) {
            timeoutError =
                "Enter a whole number of seconds from " +
                    "${Settings.MIN_QUERY_TIMEOUT_SECONDS} to " +
                    "${Settings.MAX_QUERY_TIMEOUT_SECONDS}."
            return
        }
        timeoutError = null
        viewModel.setQueryTimeoutSeconds(seconds)
    }

    val candidateConnection = connections.firstOrNull { it.id == candidateConnectionId }
    val databaseLabel =
        when {
            candidateConnection != null ->
                "${candidateConnection.name} · ${candidateConnection.database}"
            candidateConnectionId != null -> "Unavailable connection · $candidateConnectionId"
            else -> "None"
        }
    val schemaLabel =
        when {
            candidateConnectionId == settings.defaultConnectionId &&
                settings.defaultSchema != null -> settings.defaultSchema.orEmpty()
            schemaLoading -> "Loading schemas…"
            else -> "Select a schema"
        }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.widthIn(min = 520.dp, max = 660.dp),
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Version $appVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(end = if (settingsScrollState.maxValue > 0) 12.dp else 0.dp)
                            .verticalScroll(settingsScrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    loadError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    saveError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column {
                        Text(
                            "Default query location",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Default database",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(bottom = 5.dp),
                                )
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    SecondaryButton(
                                        onClick = { databaseMenuOpen = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding =
                                            PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            databaseLabel,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                                    }
                                    SafeDropdownMenu(
                                        expanded = databaseMenuOpen,
                                        onDismissRequest = { databaseMenuOpen = false },
                                        modifier = Modifier.width(280.dp),
                                        minWidth = 280.dp,
                                    ) {
                                        ScrollableMenuColumn {
                                            MenuActionRow(
                                                text = "None",
                                                selected = candidateConnectionId == null,
                                                onClick = {
                                                    databaseMenuOpen = false
                                                    candidateConnectionId = null
                                                    viewModel.clearDefaultSchemaOptions()
                                                    viewModel.clearDefaultLocation(
                                                        onDefaultLocationCleared,
                                                    )
                                                },
                                            )
                                            connections.forEach { connection ->
                                                MenuActionRow(
                                                    text = connection.name,
                                                    supportingText = connection.database,
                                                    selected =
                                                        connection.id == candidateConnectionId,
                                                    onClick = {
                                                        databaseMenuOpen = false
                                                        candidateConnectionId = connection.id
                                                        viewModel.loadDefaultSchemaOptions(
                                                            connection.id,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Default schema",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(bottom = 5.dp),
                                )
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    SecondaryButton(
                                        onClick = { schemaMenuOpen = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled =
                                            candidateConnection != null &&
                                                schemaOptions.isNotEmpty() &&
                                                !schemaLoading,
                                        contentPadding =
                                            PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        if (schemaLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.padding(end = 8.dp).size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                        Text(
                                            schemaLabel,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                                    }
                                    SafeDropdownMenu(
                                        expanded = schemaMenuOpen,
                                        onDismissRequest = { schemaMenuOpen = false },
                                        modifier = Modifier.width(280.dp),
                                        minWidth = 280.dp,
                                    ) {
                                        ScrollableMenuColumn {
                                            schemaOptions.forEach { schema ->
                                                MenuActionRow(
                                                    text = schema,
                                                    selected =
                                                        candidateConnectionId ==
                                                            settings.defaultConnectionId &&
                                                            schema == settings.defaultSchema,
                                                    onClick = {
                                                        schemaMenuOpen = false
                                                        val connectionId =
                                                            candidateConnectionId
                                                                ?: return@MenuActionRow
                                                        viewModel.saveDefaultLocation(
                                                            connectionId,
                                                            schema,
                                                        ) {
                                                            onDefaultLocationChanged(
                                                                connectionId,
                                                                schema,
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        schemaError?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column {
                        Text(
                            "Appearance",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ModeToggle(
                            isDark = settings.isDark(),
                            onSelect = viewModel::setDarkMode,
                        )
                        Text(
                            "Color scheme",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                        )
                        ColorSchemePicker(
                            selected = settings.palette(),
                            isDark = settings.isDark(),
                            onSelect = viewModel::setColorScheme,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column {
                        Text("Query risk gate", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Controls which descriptive risk levels make Run unavailable. It does not replace read-only checks, restricted schemas, row limits, or timeouts.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((gate, label) in
                                listOf(
                                    QueryRiskGate.Cautious to "Cautious",
                                    QueryRiskGate.Standard to "Standard",
                                    QueryRiskGate.Flexible to "Flexible",
                                    QueryRiskGate.Disabled to "Off",
                                )) {
                                SelectablePill(
                                    label = label,
                                    selected = settings.queryRiskGate == gate,
                                    onClick = { viewModel.setQueryRiskGate(gate) },
                                )
                            }
                        }
                        Text(
                            when (settings.queryRiskGate) {
                                QueryRiskGate.Cautious -> "Blocks Elevated concern and above."
                                QueryRiskGate.Standard -> "Blocks High concern and above."
                                QueryRiskGate.Flexible -> "Blocks Very high concern."
                                QueryRiskGate.Disabled ->
                                    "Risk calculation is not required before Run."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column {
                        Text("Query timeout", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Applies to the next query run and its plan check, in whole seconds from 1 to 120.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
                        )
                        OutlinedTextField(
                            value = timeoutDraft,
                            onValueChange = {
                                timeoutDraft = it
                                timeoutError = null
                            },
                            modifier =
                                Modifier.width(120.dp).height(60.dp).onFocusChanged { state ->
                                    if (timeoutFocused && !state.isFocused) {
                                        commitTimeoutDraft()
                                    }
                                    timeoutFocused = state.isFocused
                                },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            label = {
                                Text("Seconds", style = MaterialTheme.typography.labelMedium)
                            },
                            isError = timeoutError != null,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions =
                                KeyboardActions(onDone = { focusManager.clearFocus() }),
                        )
                        timeoutError?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                if (settingsScrollState.maxValue > 0) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(settingsScrollState),
                        modifier =
                            Modifier.align(Alignment.CenterEnd).offset(x = 16.dp).fillMaxHeight(),
                    )
                }
            }
        },
        confirmButton = { SecondaryButton(onClick = onClose) { Text("Close") } },
    )
}
