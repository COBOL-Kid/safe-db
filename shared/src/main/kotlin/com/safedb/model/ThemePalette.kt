package com.safedb.model

enum class ThemePalette(val id: String, val label: String, val tagline: String) {
    ControlBlue(
        id = "control-blue",
        label = "Control Blue",
        tagline = "Cool steel with a cobalt signal",
    ),
    SignalTeal(
        id = "signal-teal",
        label = "Signal Teal",
        tagline = "Blue-green steel with a teal signal",
    ),
    Oxide(id = "oxide", label = "Oxide", tagline = "Warm alloy with a rust signal"),
    CommandViolet(
        id = "command-violet",
        label = "Command Violet",
        tagline = "Cool slate with a violet signal",
    );

    companion object {
        val DEFAULT: ThemePalette = ControlBlue

        fun fromId(id: String?): ThemePalette {
            val normalized = id?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}
