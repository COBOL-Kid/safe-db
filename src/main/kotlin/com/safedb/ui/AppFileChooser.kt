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

internal fun createAppFileChooser(
    suggestedFileName: String? = null,
    extensionFilter: FileNameExtensionFilter? = null,
): JFileChooser =
    object : JFileChooser() {
            override fun createDialog(parent: Component?): JDialog =
                super.createDialog(parent).apply { iconImages = listOf(loadAppWindowIconImage()) }
        }
        .apply {
            lastChooserDirectory?.takeIf { it.isDirectory }?.let { currentDirectory = it }
            if (suggestedFileName != null) {
                selectedFile = File(currentDirectory, suggestedFileName)
            }
            if (extensionFilter != null) {
                fileFilter = extensionFilter
            }
        }

internal fun rememberChooserDirectory(file: File?) {
    file?.parentFile?.takeIf { it.isDirectory }?.let { lastChooserDirectory = it }
}
