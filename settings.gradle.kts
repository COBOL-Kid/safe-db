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
        kotlin("jvm") version "2.4.10"
        kotlin("plugin.serialization") version "2.4.10"
        kotlin("plugin.compose") version "2.4.10"
        id("org.jetbrains.compose") version "1.11.1"
        id("org.jetbrains.kotlinx.kover") version "0.9.9"
        id("com.ncorti.ktfmt.gradle") version "0.26.0"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
