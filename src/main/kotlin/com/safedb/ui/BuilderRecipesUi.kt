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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.safedb.explore.recipeFields
import com.safedb.explore.resolveRecipeFields
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.RecipesViewModel
import java.time.Instant

@Composable
internal fun BuilderRecipeButton(
    recipesViewModel: RecipesViewModel,
    connections: List<ConnectionDef>,
    activeConnection: ConnectionDef?,
    currentSample: QueryResult?,
    currentSpec: QuerySpec,
    onApply: (ExploreRecipe, ConnectionDef) -> Unit,
) {
    val recipes by recipesViewModel.recipes.collectAsState()
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExploreRecipe?>(null) }
    var connectionId by remember(activeConnection?.id) { mutableStateOf(activeConnection?.id) }
    var deleting by remember { mutableStateOf<ExploreRecipe?>(null) }
    var renaming by remember { mutableStateOf<ExploreRecipe?>(null) }
    var renameValue by remember { mutableStateOf("") }

    TextButton(onClick = { open = true }) {
        Icon(Icons.Default.Bookmarks, contentDescription = null, modifier = Modifier.size(17.dp))
        Text("Recipes", modifier = Modifier.padding(start = 5.dp))
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
    if (!open) return
    val target = connections.firstOrNull { it.id == connectionId }
    val fields =
        remember(currentSample, currentSpec.tables) {
            currentSample?.let { buildExploreFieldOptions(it, currentSpec.tables) }.orEmpty()
        }
    val builtins = currentSample?.let { listExploreTemplates(fields) }
    AlertDialog(
        onDismissRequest = { open = false },
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (builtins == null) {
                    Text(
                        "Run a query to use built-in Pivot recipes with the current sample.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    builtins.forEach { item ->
                        Surface(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (item.available)
                                            Modifier.clickable {
                                                val result = resolveExploreTemplate(item.id, fields)
                                                if (result is ExploreTemplateBuildResult.Ready) {
                                                    val now = Instant.now().epochSecond.toString()
                                                    selected =
                                                        ExploreRecipe(
                                                            id = "builtin:${item.id}",
                                                            name = item.name,
                                                            description = item.description,
                                                            createdAt = now,
                                                            updatedAt = now,
                                                            defaultMode = ExploreMode.Pivot,
                                                            pivot = result.config,
                                                            requiredFields =
                                                                recipeFields(
                                                                    currentSample,
                                                                    currentSpec,
                                                                    result.config,
                                                                    null,
                                                                ),
                                                        )
                                                }
                                            }
                                        else Modifier
                                    ),
                            shape = RoundedCornerShape(3.dp),
                            color =
                                if (selected?.id == "builtin:${item.id}")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            border =
                                BorderStroke(
                                    1.dp,
                                    if (selected?.id == "builtin:${item.id}")
                                        SafeDbTheme.colors.actionPrimary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)
                            ) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    item.unavailableReason ?: item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "My recipes",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
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
                        "Save a recipe from Explore or import a shared recipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                recipes.forEach { recipe ->
                    val available =
                        recipe.querySpec != null ||
                            (activeConnection != null &&
                                currentSample != null &&
                                resolveRecipeFields(recipe, currentSample, currentSpec)
                                    .unresolved
                                    .isEmpty())
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .then(
                                    if (available) Modifier.clickable { selected = recipe }
                                    else Modifier
                                ),
                        shape = RoundedCornerShape(3.dp),
                        color =
                            if (selected?.id == recipe.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        border =
                            BorderStroke(
                                1.dp,
                                if (selected?.id == recipe.id) SafeDbTheme.colors.actionPrimary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(
                                    start = 11.dp,
                                    top = 9.dp,
                                    bottom = 9.dp,
                                    end = 3.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(recipe.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        recipe.querySpec != null ->
                                            "Query included · limit ${recipe.querySpec?.limit}"
                                        currentSample == null ->
                                            "Run a query before applying this view-only recipe"
                                        !available ->
                                            "The current result needs field mapping in Explore"
                                        else -> recipe.description.ifBlank { "View-only recipe" }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    recipe.includedModes.forEach {
                                        ModeIcon(it, Modifier.size(14.dp))
                                    }
                                }
                            }
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
                                    chooseRecipeFile(false, recipe.name)?.let {
                                        recipesViewModel.export(recipe, it.toPath())
                                    }
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
                        }
                    }
                }
                if (selected?.querySpec != null) {
                    Text(
                        "Run on connection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        connections.forEach { connection ->
                            BuilderRecipePill(connection.name, connection.id == connectionId) {
                                connectionId = connection.id
                            }
                        }
                    }
                    selected?.let { recipe ->
                        Text(
                            "This restores ${recipe.querySpec?.tables?.size ?: 0} table${if (recipe.querySpec?.tables?.size == 1) "" else "s"}, runs through the normal query safeguards, and opens ${recipe.defaultMode.displayName()}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    val recipe = selected ?: return@PrimaryButton
                    val connection = if (recipe.querySpec != null) target else activeConnection
                    if (connection != null) {
                        onApply(recipe, connection)
                        open = false
                    }
                },
                enabled =
                    selected != null &&
                        (if (selected?.querySpec != null) target != null
                        else activeConnection != null && currentSample != null),
            ) {
                Text(if (selected?.querySpec != null) "Confirm and run" else "Open in Explore")
            }
        },
        dismissButton = { SecondaryButton(onClick = { open = false }) { Text("Close") } },
    )
}

@Composable
private fun BuilderRecipePill(label: String, selected: Boolean, onClick: () -> Unit) {
    SelectablePill(label, selected, onClick)
}
