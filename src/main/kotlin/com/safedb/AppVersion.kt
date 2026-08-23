package com.safedb

internal const val APP_VERSION_RESOURCE = "app-version.txt"

private object AppVersionLoader

internal fun loadAppVersion(): String {
    val stream =
        AppVersionLoader::class.java.getResourceAsStream("/$APP_VERSION_RESOURCE")
            ?: error("missing /$APP_VERSION_RESOURCE")
    return stream.use { it.readBytes().toString(Charsets.UTF_8).trim() }
}
