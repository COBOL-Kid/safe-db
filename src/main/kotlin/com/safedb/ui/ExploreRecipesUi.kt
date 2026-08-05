package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.VisualizationTemplateBuildResult
import com.safedb.explore.VisualizationTemplateId
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.recipeCandidateColumns
import com.safedb.explore.recipeFields
import com.safedb.explore.remapRecipe
import com.safedb.explore.resolveRecipeFields
import com.safedb.explore.visualizationTemplates
import com.safedb.explore.withoutTransientState
import com.safedb.model.ConnectionDef
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.RecipesViewModel
import java.time.Instant
import java.util.UUID

@Composable
internal fun ExploreRecipeActions(
    explore: ExploreViewModel,
    recipesViewModel: RecipesViewModel,
    connections: List<ConnectionDef>,
    onRunRecipe: (ExploreRecipe, ConnectionDef) -> Unit,
) {
    val recipes by recipesViewModel.recipes.collectAsState()
    var libraryOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var updateExisting by remember { mutableStateOf(false) }
    var pendingApply by remember { mutableStateOf<ExploreRecipe?>(null) }
    var runConnectionId by
        remember(explore.session.connectionId) { mutableStateOf(explore.session.connectionId) }
    val applied = recipes.firstOrNull { it.id == explore.appliedRecipeId }

    explore.pendingRecipe?.let { pending ->
        RecipeMappingDialog(
            recipe = pending,
            explore = explore,
            onApply = explore::applyPendingRecipe,
            onDismiss = explore::dismissPendingRecipe,
        )
    }
    pendingApply
        ?.takeIf { it.querySpec != null }
        ?.let { recipe ->
            RunRecipeDialog(
                recipe = recipe,
                connections = connections,
                selectedConnectionId = runConnectionId,
                onSelectConnection = { runConnectionId = it },
                onRun = { connection ->
                    pendingApply = null
                    onRunRecipe(recipe, connection)
                },
                onDismiss = { pendingApply = null },
            )
        }
    ConfirmDialog(
        open = pendingApply != null && pendingApply?.querySpec == null,
        title = "Replace included views?",
        message =
            pendingApply
                ?.let { recipe ->
                    "This recipe will replace its ${recipe.includedModes.joinToString { it.displayName() }} configuration. Other modes will be kept."
                }
                .orEmpty(),
        confirmLabel = "Apply recipe",
        onConfirm = {
            pendingApply?.let { recipe -> explore.requestRecipe(recipe) }
            pendingApply = null
        },
        onCancel = { pendingApply = null },
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { libraryOpen = true }) {
            Icon(
                Icons.Default.Bookmarks,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Text("Recipes", modifier = Modifier.padding(start = 5.dp))
        }
        if (applied != null && explore.recipeDirty()) {
            TextButton(
                onClick = {
                    updateExisting = true
                    saveOpen = true
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("Update recipe", modifier = Modifier.padding(start = 5.dp))
            }
            TextButton(
                onClick = {
                    updateExisting = false
                    saveOpen = true
                }
            ) {
                Text("Save as new")
            }
        } else {
            TextButton(
                onClick = {
                    updateExisting = false
                    saveOpen = true
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("Save recipe", modifier = Modifier.padding(start = 5.dp))
            }
        }
    }

    if (saveOpen) {
        SaveRecipeDialog(
            explore = explore,
            existing = if (updateExisting) applied else null,
            onSave = { recipe ->
                recipesViewModel.save(recipe) { saved -> if (saved) explore.applyRecipe(recipe) }
                saveOpen = false
            },
            onDismiss = { saveOpen = false },
        )
    }
    if (libraryOpen) {
        RecipeLibraryDialog(
            explore = explore,
            recipes = recipes,
            recipesViewModel = recipesViewModel,
            onApply = { recipe ->
                if (
                    recipe.querySpec != null ||
                        explore.recipeDirty() ||
                        explore.isDirty() ||
                        explore.isDefaultVisualization().not() ||
                        explore.isDefaultWorksheet().not()
                ) {
                    pendingApply = recipe
                } else {
                    explore.requestRecipe(recipe)
                }
                libraryOpen = false
            },
            onDismiss = { libraryOpen = false },
        )
    }
}

@Composable
private fun RunRecipeDialog(
    recipe: ExploreRecipe,
    connections: List<ConnectionDef>,
    selectedConnectionId: String?,
    onSelectConnection: (String) -> Unit,
    onRun: (ConnectionDef) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = connections.firstOrNull { it.id == selectedConnectionId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run recipe query?") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${recipe.querySpec?.tables?.size ?: 0} table${if (recipe.querySpec?.tables?.size == 1) "" else "s"} · limit ${recipe.querySpec?.limit} · opens ${recipe.defaultMode.displayName()}"
                )
                RecipeLabel("Run on connection")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    connections.forEach { connection ->
                        RecipePill(connection.name, connection.id == selectedConnectionId) {
                            onSelectConnection(connection.id)
                        }
                    }
                }
                Text(
                    "The query will use the normal read-only, cost, row-limit, and timeout safeguards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PrimaryButton(onClick = { selected?.let(onRun) }, enabled = selected != null) {
                Text("Confirm and run")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaveRecipeDialog(
    explore: ExploreViewModel,
    existing: ExploreRecipe?,
    onSave: (ExploreRecipe) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var included by remember {
        mutableStateOf(existing?.includedModes ?: setOf(explore.workspace.activeMode))
    }
    var defaultMode by remember {
        mutableStateOf(existing?.defaultMode ?: explore.workspace.activeMode)
    }
    var includeQuery by remember { mutableStateOf(existing?.querySpec != null) }
    val now = Instant.now().epochSecond.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Save recipe" else "Update recipe") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                RecipeLabel("Include modes")
                ExploreMode.entries.forEach { mode ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().clickable {
                                included =
                                    if (mode in included && included.size > 1) included - mode
                                    else included + mode
                                if (defaultMode !in included) defaultMode = included.first()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = mode in included, onCheckedChange = null)
                        ModeIcon(mode)
                        Text(mode.displayName(), modifier = Modifier.padding(start = 7.dp))
                    }
                }
                RecipeLabel("Open in")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ExploreMode.entries
                        .filter { it in included }
                        .forEach { mode ->
                            RecipePill(mode.displayName(), defaultMode == mode) {
                                defaultMode = mode
                            }
                        }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeQuery = !includeQuery },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeQuery, onCheckedChange = null)
                    Column {
                        Text("Include Builder query")
                        Text(
                            "The recipe can restore and run this query on a selected connection.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    val pivot =
                        explore.workspace.pivot.withoutTransientState().takeIf {
                            ExploreMode.Pivot in included
                        }
                    val worksheet =
                        explore.workspace.worksheet.withoutTransientState().takeIf {
                            ExploreMode.Worksheet in included
                        }
                    val visualization =
                        explore.workspace.visualization.takeIf {
                            ExploreMode.Visualization in included
                        }
                    onSave(
                        ExploreRecipe(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            description = description.trim(),
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                            defaultMode = defaultMode,
                            pivot = pivot,
                            worksheet = worksheet,
                            visualization = visualization,
                            requiredFields =
                                recipeFields(
                                    explore.session.sample,
                                    explore.session.baseSpec,
                                    pivot,
                                    worksheet,
                                    visualization,
                                ),
                            querySpec = explore.session.baseSpec.takeIf { includeQuery },
                        )
                    )
                },
                enabled = name.isNotBlank() && included.isNotEmpty() && defaultMode in included,
            ) {
                Text(if (existing == null) "Save" else "Update")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RecipeLibraryDialog(
    explore: ExploreViewModel,
    recipes: List<ExploreRecipe>,
    recipesViewModel: RecipesViewModel,
    onApply: (ExploreRecipe) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields =
        remember(explore.session.sample, explore.session.baseSpec) {
            buildExploreFieldOptions(explore.session.sample, explore.session.baseSpec.tables)
        }
    val pivotTemplates =
        remember(explore.session.sample, fields) {
            listExploreTemplates(explore.session.sample, fields).filterNot { it.isUserTemplate }
        }
    val visualizationTemplateCatalog =
        remember(explore.session.sample, explore.session.baseSpec.tables) {
            visualizationTemplates(explore.session.sample, explore.session.baseSpec.tables)
        }
    var selectedBuiltin by remember { mutableStateOf<ExploreBuiltinTemplateId?>(null) }
    var selectedVisualization by remember { mutableStateOf<VisualizationTemplateId?>(null) }
    var pendingMapping by remember { mutableStateOf<ExploreRecipe?>(null) }
    var deleting by remember { mutableStateOf<ExploreRecipe?>(null) }
    var renaming by remember { mutableStateOf<ExploreRecipe?>(null) }
    var renameValue by remember { mutableStateOf("") }

    pendingMapping?.let { recipe ->
        RecipeMappingDialog(
            recipe = recipe,
            explore = explore,
            onApply = { mapping ->
                pendingMapping = null
                onApply(remapRecipe(recipe, mapping))
            },
            onDismiss = { pendingMapping = null },
        )
    }
    RecipeMaintenanceDialogs(
        deleting = deleting,
        renaming = renaming,
        renameValue = renameValue,
        onRenameValueChange = { renameValue = it },
        onDelete = {
            recipesViewModel.delete(it.id)
            deleting = null
        },
        onDeleteDismiss = { deleting = null },
        onRename = { recipe, name ->
            recipesViewModel.save(
                recipe.copy(name = name, updatedAt = Instant.now().epochSecond.toString())
            )
            renaming = null
        },
        onRenameDismiss = { renaming = null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recipes") },
        text = {
            Column(
                modifier =
                    Modifier.widthIn(min = 620.dp, max = 700.dp)
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Built-in recipes",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                pivotTemplates.forEach { item ->
                    RecipeCard(
                        name = item.name,
                        description = item.description,
                        modes = setOf(ExploreMode.Pivot),
                        queryBacked = false,
                        available = item.available,
                        reason = item.unavailableReason,
                        selected = selectedBuiltin == item.id,
                        onSelect = {
                            selectedBuiltin = item.id
                            selectedVisualization = null
                        },
                    )
                }
                Text(
                    "Visualization templates",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                visualizationTemplateCatalog.forEach { template ->
                    val available = template.result is VisualizationTemplateBuildResult.Ready
                    RecipeCard(
                        name = template.name,
                        description = template.description,
                        modes = setOf(ExploreMode.Visualization),
                        queryBacked = false,
                        available = available,
                        reason =
                            (template.result as? VisualizationTemplateBuildResult.Unavailable)
                                ?.reason,
                        selected = selectedVisualization == template.id,
                        onSelect = {
                            selectedVisualization = template.id
                            selectedBuiltin = null
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 5.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "My recipes",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = {
                            chooseRecipeFile(open = true)?.let {
                                recipesViewModel.import(it.toPath())
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Import", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                if (recipes.isEmpty())
                    Text(
                        "Saved recipes will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                recipes.forEach { recipe ->
                    val mapping =
                        resolveRecipeFields(
                            recipe,
                            explore.session.sample,
                            explore.session.baseSpec,
                        )
                    RecipeCard(
                        name = recipe.name,
                        description = recipe.description.ifBlank { "Updated ${recipe.updatedAt}" },
                        modes = recipe.includedModes,
                        queryBacked = recipe.querySpec != null,
                        available =
                            recipe.querySpec != null ||
                                mapping.unresolved.isEmpty() ||
                                recipe.requiredFields.isNotEmpty(),
                        reason =
                            mapping.unresolved
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    "${it.size} field${if (it.size == 1) "" else "s"} need mapping"
                                },
                        selected = false,
                        onSelect = {
                            if (recipe.querySpec != null || mapping.unresolved.isEmpty())
                                onApply(recipe)
                            else pendingMapping = recipe
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    renaming = recipe
                                    renameValue = recipe.name
                                },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    "Rename ${recipe.name}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    chooseRecipeFile(open = false, suggestedName = recipe.name)
                                        ?.let { recipesViewModel.export(recipe, it.toPath()) }
                                },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    "Export ${recipe.name}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { deleting = recipe },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Delete ${recipe.name}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    selectedBuiltin?.let { selected ->
                        val result =
                            resolveExploreTemplate(selected, explore.session.sample, fields)
                        if (result !is ExploreTemplateBuildResult.Ready) return@let
                        val definition = pivotTemplates.first { it.id == selected }
                        val now = Instant.now().epochSecond.toString()
                        onApply(
                            ExploreRecipe(
                                id = "builtin:$selected",
                                name = definition.name,
                                description = definition.description,
                                createdAt = now,
                                updatedAt = now,
                                defaultMode = ExploreMode.Pivot,
                                pivot = result.config,
                                requiredFields =
                                    recipeFields(
                                        explore.session.sample,
                                        explore.session.baseSpec,
                                        result.config,
                                        null,
                                    ),
                            )
                        )
                        onDismiss()
                        return@PrimaryButton
                    }
                    val selected = selectedVisualization ?: return@PrimaryButton
                    val definition = visualizationTemplateCatalog.first { it.id == selected }
                    val result =
                        definition.result as? VisualizationTemplateBuildResult.Ready
                            ?: return@PrimaryButton
                    val now = Instant.now().epochSecond.toString()
                    onApply(
                        ExploreRecipe(
                            id = "builtin:visualization:$selected",
                            name = definition.name,
                            description = definition.description,
                            createdAt = now,
                            updatedAt = now,
                            defaultMode = ExploreMode.Visualization,
                            visualization = result.config,
                            requiredFields =
                                recipeFields(
                                    explore.session.sample,
                                    explore.session.baseSpec,
                                    null,
                                    null,
                                    result.config,
                                ),
                        )
                    )
                    onDismiss()
                },
                enabled = selectedBuiltin != null || selectedVisualization != null,
            ) {
                Text("Apply built-in")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RecipeMappingDialog(
    recipe: ExploreRecipe,
    explore: ExploreViewModel,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val automatic = resolveRecipeFields(recipe, explore.session.sample, explore.session.baseSpec)
    var manual by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map recipe fields") },
        text = {
            Column(
                modifier =
                    Modifier.widthIn(min = 560.dp)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Choose a compatible sample field for each unresolved recipe field.",
                    style = MaterialTheme.typography.bodySmall,
                )
                automatic.unresolved.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(field.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            field.dataType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            recipeCandidateColumns(field, explore.session.sample.columns).forEach {
                                column ->
                                RecipePill(
                                    displayColumnLabel(column.name),
                                    manual[field.column] == column.name,
                                ) {
                                    manual = manual + (field.column to column.name)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = { onApply(automatic.resolved + manual) },
                enabled = automatic.unresolved.all { it.column in manual },
            ) {
                Text("Apply recipe")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecipeCard(
    name: String,
    description: String,
    modes: Set<ExploreMode>,
    queryBacked: Boolean,
    available: Boolean,
    reason: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (available) Modifier.clickable(onClick = onSelect) else Modifier),
        shape = RoundedCornerShape(3.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                1.dp,
                if (selected) SafeDbTheme.colors.actionPrimary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (available) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (description.isNotBlank())
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    modes.forEach { mode -> ModeIcon(mode, Modifier.size(14.dp)) }
                    if (queryBacked)
                        Text(
                            "Query included",
                            style = MaterialTheme.typography.labelSmall,
                            color = SafeDbTheme.colors.actionPrimary,
                        )
                    reason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            actions()
        }
    }
}

@Composable
internal fun ModeIcon(mode: ExploreMode, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        when (mode) {
            ExploreMode.Pivot -> Icons.Default.TableChart
            ExploreMode.Visualization -> Icons.Default.BarChart
            ExploreMode.Worksheet -> Icons.Default.GridOn
        },
        contentDescription = mode.displayName(),
        modifier = modifier,
    )
}

internal fun ExploreMode.displayName(): String =
    when (this) {
        ExploreMode.Pivot -> "Pivot"
        ExploreMode.Visualization -> "Visualization"
        ExploreMode.Worksheet -> "Worksheet"
    }

@Composable
private fun RecipePill(label: String, selected: Boolean, onClick: () -> Unit) {
    SelectablePill(label, selected, onClick)
}

@Composable
private fun RecipeLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}
