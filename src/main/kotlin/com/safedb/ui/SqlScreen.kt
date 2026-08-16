package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.SchemaSelectionIntent
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.Outcome
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.query.evaluateQueryRisk
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.lineColOf
import com.safedb.query.sql.parseSqlToSpec
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.ConnectionPicker
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.ToolbarHeaderVerticalPadding
import com.safedb.viewmodel.BuilderQuerySample
import com.safedb.viewmodel.SchemaViewModel
import com.safedb.viewmodel.SqlEditorViewModel

internal fun parsedSqlSpec(
    text: String,
    dialect: Dialect?,
    schema: Schema?,
    defaultSchema: String?,
): SqlParseResult? {
    if (dialect == null || schema == null || text.isBlank()) return null
    return parseSqlToSpec(text, dialect, schema, defaultSchema)
}

// Single parse site, shared with App.kt so Explore staleness/refresh sees the same spec the SQL
// screen would run. A schema loaded for another connection would resolve tables and columns the
// active database may not have, so it only counts once it matches that connection.
@Composable
internal fun rememberSqlParseResult(
    sqlText: String,
    dialect: Dialect?,
    schemaViewModel: SchemaViewModel,
    activeConnectionId: String?,
): SqlParseResult? {
    val schema =
        schemaViewModel.schema.takeIf {
            activeConnectionId != null && schemaViewModel.loadedConnectionId == activeConnectionId
        }
    val defaultSchema = schemaViewModel.selectedSchema
    return remember(sqlText, dialect, schema, defaultSchema) {
        parsedSqlSpec(sqlText, dialect, schema, defaultSchema)
    }
}

@Composable
internal fun SqlScreen(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    sqlViewModel: SqlEditorViewModel,
    schemaViewModel: SchemaViewModel,
    schemaSelection: SchemaSelectionIntent,
    schemaHistoryError: String?,
    settings: Settings,
    parseResult: SqlParseResult?,
    builderRunning: Boolean,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onSchemaSelected: (String) -> Unit,
    onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    onDismissSchemaHistoryError: () -> Unit,
    onOpenExplore: (BuilderQuerySample) -> Unit,
    onOpenConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fallbackWarning by remember(connection?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(connection?.id, schemaSelection) {
        val connectionId = connection?.id
        if (connectionId == null) {
            schemaViewModel.clear()
            return@LaunchedEffect
        }
        schemaViewModel.load(
            connectionId = connectionId,
            selection = schemaSelection,
            onUnavailableSelection = onUnavailableSchemaSelection,
        ) { loaded ->
            if (!loaded || schemaViewModel.selectedSchema != null) return@load
            val first = schemaViewModel.schemaOptions.firstOrNull() ?: return@load
            fallbackWarning =
                schemaViewModel.preferredSchemaWarning?.let { "$it Showing \"$first\" instead." }
            schemaViewModel.selectSchema(first)
            onSchemaSelected(first)
        }
    }

    val schema =
        schemaViewModel.schema.takeIf {
            connection != null && schemaViewModel.loadedConnectionId == connection.id
        }
    val selectedSchema = schemaViewModel.selectedSchema
    val dialect = connection?.dialect
    val parsedSpec = (parseResult as? SqlParseResult.Success)?.spec
    val parseIssues = (parseResult as? SqlParseResult.Failure)?.issues.orEmpty()
    val parseNotes = (parseResult as? SqlParseResult.Success)?.notes.orEmpty()

    LaunchedEffect(parsedSpec) { sqlViewModel.onParsedSpecChanged(parsedSpec) }

    val preliminaryRisk =
        remember(parsedSpec, schema, settings, dialect) {
            if (parsedSpec == null || schema == null || dialect == null) null
            else evaluateQueryRisk(parsedSpec, schema, settings, dialect)
        }
    val preliminaryEvaluation = (preliminaryRisk as? Outcome.Ok)?.value
    val riskValidationError = (preliminaryRisk as? Outcome.Err)?.message
    val finalRiskEvaluation = sqlViewModel.riskEvaluationFor(connection?.id, parsedSpec)
    val currentSample = sqlViewModel.currentSample(connection?.id, parsedSpec)
    val pendingConfirmation = sqlViewModel.pendingConfirmationFor(connection?.id, parsedSpec)

    // A builder run holds the same app-wide slot: one live query at a time.
    val canRun =
        connection != null &&
            schema != null &&
            parsedSpec != null &&
            riskValidationError == null &&
            !sqlViewModel.running &&
            !builderRunning &&
            !sqlViewModel.pendingRiskGateFor(connection.id, parsedSpec) &&
            pendingConfirmation == null

    fun runQuery() {
        val connectionId = connection?.id ?: return
        val spec = parsedSpec ?: return
        if (!canRun) return
        sqlViewModel.run(connectionId, spec)
    }

    if (pendingConfirmation != null) {
        val copy = queryConfirmationDialogCopy(pendingConfirmation)
        ConfirmDialog(
            open = true,
            title = copy.title,
            message = copy.message,
            confirmLabel = copy.confirmLabel,
            onConfirm = {
                val connectionId = connection?.id
                if (connectionId == null) {
                    sqlViewModel.dismissError()
                } else {
                    sqlViewModel.confirmPendingExecution(connectionId, parsedSpec)
                }
            },
            onCancel = sqlViewModel::dismissPendingConfirmation,
        )
    }

    Column(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceBackground)) {
        SqlScreenHeader(
            connection = connection,
            connections = connections,
            selectedSchema = selectedSchema,
            schemaOptions = schemaViewModel.schemaOptions,
            riskIndicator =
                if (connection != null) {
                    queryRiskIndicatorText(
                        preliminary = preliminaryEvaluation?.staticAssessment,
                        evaluation = finalRiskEvaluation,
                        running = sqlViewModel.running,
                        gate = settings.queryRiskGate,
                        validationError = riskValidationError,
                    )
                } else {
                    null
                },
            gateIndicator = riskGateIndicatorText(settings.queryRiskGate),
            canRun = canRun,
            onConnectionSelected = onConnectionSelected,
            onSchemaSelected = { selected ->
                fallbackWarning = null
                schemaViewModel.selectSchema(selected)
                onSchemaSelected(selected)
            },
            onRun = ::runQuery,
        )

        fallbackWarning?.let { MessageBanner(it, BannerKind.WARNING) }
        schemaHistoryError?.let { error ->
            MessageBanner(
                text = "Could not remember the selected schema: $error",
                kind = BannerKind.WARNING,
            ) {
                SecondaryButton(onClick = onDismissSchemaHistoryError) { Text("Dismiss") }
            }
        }

        when {
            connection == null ->
                EmptyState(
                    icon = Icons.Outlined.Code,
                    title = "Choose a database to query",
                    subtitle = "Select a saved connection to write a SELECT against it.",
                    action = {
                        SecondaryButton(onClick = onOpenConnections) { Text("Open Connections") }
                    },
                )
            schemaViewModel.error != null ->
                EmptyState(
                    icon = Icons.Outlined.Code,
                    title = "Could not load this schema",
                    subtitle = schemaViewModel.error.orEmpty(),
                )
            schemaViewModel.loading || schema == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Loading schema…",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            else ->
                SqlWorkspace(
                    sqlViewModel = sqlViewModel,
                    schema = schema,
                    selectedSchema = selectedSchema,
                    dialect = dialect,
                    parseIssues = parseIssues,
                    parseNotes = parseNotes,
                    riskValidationError = riskValidationError,
                    finalRiskEvaluation = finalRiskEvaluation,
                    currentSample = currentSample,
                    onRun = ::runQuery,
                    onOpenExplore = onOpenExplore,
                )
        }
    }
}

@Composable
private fun SqlWorkspace(
    sqlViewModel: SqlEditorViewModel,
    schema: Schema?,
    selectedSchema: String?,
    dialect: Dialect?,
    parseIssues: List<com.safedb.query.sql.SqlIssue>,
    parseNotes: List<String>,
    riskValidationError: String?,
    finalRiskEvaluation: com.safedb.query.QueryRiskEvaluation?,
    currentSample: BuilderQuerySample?,
    onRun: () -> Unit,
    onOpenExplore: (BuilderQuerySample) -> Unit,
) {
    val text = sqlViewModel.text
    Column(modifier = Modifier.fillMaxSize()) {
        SqlEditor(
            value = text,
            onValueChange = sqlViewModel::onTextChanged,
            dialect = dialect,
            schema = schema,
            defaultSchema = selectedSchema,
            enabled = true,
            onRun = onRun,
            modifier =
                Modifier.fillMaxWidth()
                    .height(190.dp)
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp),
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            parseIssues.forEach { issue ->
                MessageBanner(text = issueBannerText(text.text, issue), kind = BannerKind.ERROR)
            }
            parseNotes.forEach { note -> MessageBanner(text = note, kind = BannerKind.INFO) }
            riskValidationError?.let { error ->
                MessageBanner(text = error, kind = BannerKind.ERROR)
            }
            sqlViewModel.error?.let { error ->
                MessageBanner(text = error, kind = BannerKind.ERROR) {
                    SecondaryButton(onClick = sqlViewModel::dismissError) { Text("Dismiss") }
                }
            }
            planSafeguardBannerText(finalRiskEvaluation)?.let { bannerText ->
                MessageBanner(
                    text = bannerText,
                    kind =
                        if (finalRiskEvaluation?.confirmationRequirement != null) {
                            BannerKind.WARNING
                        } else {
                            BannerKind.INFO
                        },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(modifier = Modifier.fillMaxSize()) {
            // Render only the sample that still matches the editor's parsed spec. Showing raw
            // results would leave the previous query's rows sitting under freshly edited SQL.
            when {
                sqlViewModel.running ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Running query…",
                                modifier = Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                currentSample != null ->
                    ResultsTable(
                        result = currentSample.result,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PrimaryButton(onClick = { onOpenExplore(currentSample) }) {
                            Text("Explore")
                        }
                    }
                else ->
                    EmptyState(
                        icon = Icons.Outlined.TableRows,
                        title = "No results yet",
                        subtitle = "Write a SELECT above and run it to see a bounded sample here.",
                    )
            }
        }
    }
}

private fun issueBannerText(text: String, issue: com.safedb.query.sql.SqlIssue): String {
    val span = issue.span ?: return issue.message
    val (line, col) = lineColOf(text, span.start)
    return "Line $line:$col — ${issue.message}"
}

@Composable
private fun SqlScreenHeader(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    selectedSchema: String?,
    schemaOptions: List<String>,
    riskIndicator: String?,
    gateIndicator: String,
    canRun: Boolean,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onSchemaSelected: (String) -> Unit,
    onRun: () -> Unit,
) {
    var schemaMenuOpen by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SafeDbTheme.colors.workspaceHeader)
                .padding(
                    horizontal = ScreenHeaderHorizontalPadding,
                    vertical = ToolbarHeaderVerticalPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = SafeDbTheme.colors.actionPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "SQL",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    if (connection == null) {
                        "Write a SELECT; it runs through the same limits and risk checks"
                    } else {
                        buildString {
                            append(connection.database)
                            if (selectedSchema != null) append(" · $selectedSchema")
                            append(" · ${dialectLabel(connection.dialect)}")
                        }
                    },
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
                Box {
                    SecondaryButton(
                        onClick = { schemaMenuOpen = true },
                        enabled = schemaOptions.isNotEmpty(),
                        modifier = Modifier.width(166.dp),
                    ) {
                        Text(
                            selectedSchema ?: "Choose schema",
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    SafeDropdownMenu(
                        expanded = schemaMenuOpen,
                        onDismissRequest = { schemaMenuOpen = false },
                    ) {
                        schemaOptions.forEach { option ->
                            MenuActionRow(
                                text = option,
                                selected = option == selectedSchema,
                                onClick = {
                                    schemaMenuOpen = false
                                    onSchemaSelected(option)
                                },
                            )
                        }
                    }
                }
                PrimaryButton(onClick = onRun, enabled = canRun) { Text("Run") }
            }
        }
        Text(
            riskIndicator?.let { "$it · $gateIndicator" } ?: gateIndicator,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
