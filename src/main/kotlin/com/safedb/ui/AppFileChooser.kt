package com.safedb.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.safedb.loadAppWindowIconImage
import java.awt.Component
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFileChooser

val LocalAppWindow = staticCompositionLocalOf<Window?> { null }

internal fun createAppFileChooser(): JFileChooser =
    object : JFileChooser() {
        override fun createDialog(parent: Component?): JDialog =
            super.createDialog(parent).apply { iconImages = listOf(loadAppWindowIconImage()) }
    }
