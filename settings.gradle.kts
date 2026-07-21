rootProject.name = "safe-db"

include(":shared")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    plugins {
        kotlin("jvm") version "2.4.0"
        kotlin("multiplatform") version "2.4.0"
        kotlin("plugin.serialization") version "2.4.0"
        kotlin("plugin.compose") version "2.4.0"
        id("org.jetbrains.compose") version "1.9.3"
        id("org.jetbrains.kotlinx.kover") version "0.9.8"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
