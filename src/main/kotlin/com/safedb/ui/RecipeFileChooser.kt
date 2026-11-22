package com.safedb.ui

import java.awt.Component
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal fun chooseRecipeFile(
    open: Boolean,
    suggestedName: String = "explore-recipe",
    parent: Component? = null,
): File? {
    val safeName =
        suggestedName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank {
            "explore-recipe"
        }
    val chooser =
        createAppFileChooser(
                suggestedFileName = if (open) null else "$safeName.safedb-recipe.json",
                extensionFilter = FileNameExtensionFilter("Safe-DB recipes (*.json)", "json"),
            )
            .apply {
                dialogTitle = if (open) "Import Explore recipe" else "Export Explore recipe"
            }
    val result = if (open) chooser.showOpenDialog(parent) else chooser.showSaveDialog(parent)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    rememberChooserDirectory(selected)
    if (open) return selected
    return if (selected.name.endsWith(".json")) selected
    else File("${selected.path}.safedb-recipe.json")
}
