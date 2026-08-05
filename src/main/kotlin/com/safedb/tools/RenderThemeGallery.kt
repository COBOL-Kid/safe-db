package com.safedb.tools

import com.safedb.AppRoute
import com.safedb.model.ThemePalette

/** Dev-only comparison render for the persisted Control Plane color schemes. */
fun main() {
    System.setProperty("java.awt.headless", "false")

    ThemePalette.entries.forEach { palette ->
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            val suffix = "${palette.id}-$mode"
            render("scheme-connections-$suffix", dark, palette) { state, _ ->
                state.navigate(AppRoute.Connections)
            }
            render("scheme-settings-$suffix", dark, palette) { state, _ -> state.openSettings() }
        }
    }
}
