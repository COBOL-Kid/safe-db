package com.safedb.ui

import java.io.File
import javax.swing.JFileChooser

internal fun chooseRecipeFile(open: Boolean, suggestedName: String = "explore-recipe"): File? {
    val safeName =
        suggestedName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank {
            "explore-recipe"
        }
    val chooser =
        JFileChooser().apply {
            dialogTitle = if (open) "Import Explore recipe" else "Export Explore recipe"
            if (!open) selectedFile = File("$safeName.safedb-recipe.json")
        }
    val result = if (open) chooser.showOpenDialog(null) else chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    if (open) return chooser.selectedFile
    val selected = chooser.selectedFile
    return if (selected.name.endsWith(".json")) selected
    else File("${selected.path}.safedb-recipe.json")
}
