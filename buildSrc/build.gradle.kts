plugins {
    `kotlin-dsl`
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

ktfmt { kotlinLangStyle() }
