package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.LARGE_LIMIT_WARNING_THRESHOLD
import com.safedb.query.MAX_LIMIT
import com.safedb.query.parseLimit
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.SchemaViewModel

private fun dialectLabel(dialect: Dialect): String = when (dialect) {
    Dialect.Postgres -> "PostgreSQL"
    Dialect.MySql -> "MySQL"
    Dialect.Mssql -> "SQL Server"
    Dialect.Oracle -> "Oracle"
}

private data class CostGuardDialogCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

private fun costGuardDialogCopy(reason: String?): CostGuardDialogCopy {
    val normalized = reason.orEmpty()
    val highCost = normalized.contains("Estimated query cost exceeds threshold")
    return if (highCost) {
        CostGuardDialogCopy(
            title = "This query may scan more data than expected",
            message = "Safe DB estimated this query may be expensive. It will still be limited and stopped if it runs too long.",
            confirmLabel = "Run with safeguards",
        )
    } else {
        CostGuardDialogCopy(
            title = "Safe DB could not preview this query",
            message = "The database did not return a usable estimate. The query will still run with Safe DB protections: read-only access, a row limit, and a timeout.",
            confirmLabel = "Run with safeguards",
        )
    }
}

@Composable
fun BuilderScreen(
    connection: ConnectionDef?,
    queryViewModel: QueryViewModel,
    schemaViewModel: SchemaViewModel,
    modifier: Modifier = Modifier,
) {
    var showCostGuardConfirm by remember { mutableStateOf(false) }
    var resultsHeight by remember { mutableFloatStateOf(240f) }
    var resizing by remember { mutableStateOf(false) }
    val limitChoices = listOf(DEFAULT_LIMIT, LARGE_LIMIT_WARNING_THRESHOLD, 5000, MAX_LIMIT)

    LaunchedEffect(queryViewModel.pendingCostGuard) {
        if (queryViewModel.pendingCostGuard) {
            showCostGuardConfirm = true
        }
    }

    LaunchedEffect(connection?.id) {
        val connectionId = connection?.id
        if (connectionId != null &&
            schemaViewModel.loadedConnectionId != connectionId &&
            !schemaViewModel.loading
        ) {
            schemaViewModel.load(connectionId)
        }
    }

    val costGuardCopy = costGuardDialogCopy(queryViewModel.error)
    val visibleQueryError = if (queryViewModel.pendingCostGuard) null else queryViewModel.error
    val showLargeLimitGuidance = connection != null &&
        queryViewModel.canvasTables.isNotEmpty() &&
        queryViewModel.limit > LARGE_LIMIT_WARNING_THRESHOLD

    if (showCostGuardConfirm) {
        AlertDialog(
            onDismissRequest = {
                showCostGuardConfirm = false
                queryViewModel.dismissError()
            },
            title = { Text(costGuardCopy.title) },
            text = { Text(costGuardCopy.message) },
            confirmButton = {
                Button(
                    onClick = {
                        showCostGuardConfirm = false
                        queryViewModel.clearPendingCostGuard()
                        connection?.id?.let { queryViewModel.runForced(it) }
                    },
                ) {
                    Text(costGuardCopy.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCostGuardConfirm = false
                        queryViewModel.dismissError()
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (resizing) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { resizing = false },
                            onDragCancel = { resizing = false },
                            onDrag = { change, _ ->
                                resultsHeight = (resultsHeight - change.position.y).coerceIn(100f, 600f)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connection != null) {
                Column {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${dialectLabel(connection.dialect)} · ${connection.database}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Query Builder", style = MaterialTheme.typography.titleLarge)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (queryViewModel.canvasTables.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.medium,
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Limit", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = queryViewModel.limit.toString(),
                            onValueChange = { queryViewModel.setLimit(parseLimit(it)) },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                        )
                        for (choice in limitChoices) {
                            TextButton(onClick = { queryViewModel.setLimit(choice) }) {
                                Text(
                                    "%,d".format(choice),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    TextButton(onClick = { queryViewModel.clear() }) {
                        Text("Clear")
                    }
                }
                IconButton(
                    onClick = { connection?.id?.let { queryViewModel.run(it) } },
                    enabled = queryViewModel.canRun && connection != null,
                ) {
                    if (queryViewModel.running) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run query")
                    }
                }
            }
        }

        if (showLargeLimitGuidance) {
            Text(
                "Large result limit. Higher limits are useful for reporting, but filters, selected columns, and indexed predicates make queries faster and easier to reuse.",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (connection == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connect to a database to start building queries.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .width(288.dp)
                        .fillMaxHeight(),
                    tonalElevation = 1.dp,
                ) {
                    SchemaBrowser(
                        schemaViewModel = schemaViewModel,
                        onAddTable = { queryViewModel.addTable(it) },
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    queryViewModel.hydrationWarning?.let { warning ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { queryViewModel.dismissHydrationWarning() }) {
                                Text("Dismiss")
                            }
                        }
                    }

                    if (queryViewModel.joins.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            queryViewModel.joins.forEachIndexed { index, join ->
                                val leftName = queryViewModel.canvasTables
                                    .find { it.alias == join.leftAlias }?.tableInfo?.name ?: join.leftAlias
                                val rightName = queryViewModel.canvasTables
                                    .find { it.alias == join.rightAlias }?.tableInfo?.name ?: join.rightAlias
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "join: $leftName.${join.leftColumn} = $rightName.${join.rightColumn}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        IconButton(onClick = { queryViewModel.removeJoin(index) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove join")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (queryViewModel.canvasTables.isNotEmpty()) {
                        FilterBuilder(
                            queryViewModel = queryViewModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (queryViewModel.canvasTables.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Click + next to a table in the sidebar to add it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Canvas(queryViewModel = queryViewModel, modifier = Modifier.fillMaxSize())
                        }
                    }

                    visibleQueryError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    queryViewModel.results?.let { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(resultsHeight.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { resizing = true },
                                            onDragEnd = { resizing = false },
                                            onDragCancel = { resizing = false },
                                            onDrag = { change, _ ->
                                                resultsHeight = (resultsHeight - change.position.y).coerceIn(100f, 600f)
                                            },
                                        )
                                    },
                            )
                            ResultsTable(result = result, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
