package com.safedb.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.safedb.loadAppWindowIconImage
import java.awt.Component
import java.awt.Window
import java.io.File
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

val LocalAppWindow = staticCompositionLocalOf<Window?> { null }

private var lastChooserDirectory: File? = null

internal fun sanitizeFileNameSegment(raw: String, fallback: String): String =
    raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { fallback }

internal fun chooseAppFile(
    open: Boolean,
    title: String,
    suggestedFileName: String? = null,
    extensionFilter: FileNameExtensionFilter? = null,
    parent: Component? = null,
): File? {
    val chooser =
        object : JFileChooser() {
                override fun createDialog(parent: Component?): JDialog =
                    super.createDialog(parent).apply {
                        iconImages = listOf(loadAppWindowIconImage())
                    }
            }
            .apply {
                dialogTitle = title
                lastChooserDirectory?.takeIf { it.isDirectory }?.let { currentDirectory = it }
                if (suggestedFileName != null) {
                    selectedFile = File(currentDirectory, suggestedFileName)
                }
                if (extensionFilter != null) {
                    fileFilter = extensionFilter
                }
            }
    val result = if (open) chooser.showOpenDialog(parent) else chooser.showSaveDialog(parent)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile ?: return null
    (selected.parentFile ?: chooser.currentDirectory)
        ?.takeIf { it.isDirectory }
        ?.let { lastChooserDirectory = it }
    return selected
}
