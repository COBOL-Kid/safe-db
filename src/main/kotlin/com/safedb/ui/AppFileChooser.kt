package com.safedb.ui

import com.safedb.loadAppWindowIconImage
import java.awt.Component
import javax.swing.JDialog
import javax.swing.JFileChooser

internal fun createAppFileChooser(): JFileChooser =
    object : JFileChooser() {
        override fun createDialog(parent: Component?): JDialog =
            super.createDialog(parent).apply { iconImages = listOf(loadAppWindowIconImage()) }
    }
