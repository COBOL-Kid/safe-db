package com.safedb.platform

import java.util.Locale

enum class DesktopPlatform {
    MacOs,
    Windows,
    Linux;

    val supportsDesktopApp: Boolean
        get() = this == MacOs || this == Windows

    companion object {
        fun current(): DesktopPlatform = resolve(System.getProperty("os.name").orEmpty())

        fun resolve(osName: String): DesktopPlatform {
            val normalized = osName.trim().lowercase(Locale.ROOT)
            return when {
                normalized.startsWith("mac") || normalized == "darwin" -> MacOs
                normalized.startsWith("windows") -> Windows
                normalized.startsWith("linux") -> Linux
                else -> throw UnsupportedDesktopPlatformException(osName)
            }
        }
    }
}

class UnsupportedDesktopPlatformException(osName: String) :
    IllegalStateException(
        "unsupported operating system '$osName'; supported platforms are macOS, Windows, and Linux"
    )

class DesktopStoreUnavailableException(message: String) : IllegalStateException(message)
