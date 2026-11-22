package com.safedb.ui

import java.awt.Component
import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter

internal fun chooseRecipeFile(
    open: Boolean,
    suggestedName: String = "explore-recipe",
    parent: Component? = null,
): File? {
    val safeName = sanitizeFileNameSegment(suggestedName, "explore-recipe")
    val selected =
        chooseAppFile(
            open = open,
            title = if (open) "Import Explore recipe" else "Export Explore recipe",
            suggestedFileName = if (open) null else "$safeName.safedb-recipe.json",
            extensionFilter = FileNameExtensionFilter("Safe-DB recipes (*.json)", "json"),
            parent = parent,
        ) ?: return null
    if (open) return selected
    return if (selected.name.endsWith(".json", ignoreCase = true)) selected
    else File("${selected.path}.safedb-recipe.json")
}
