plugins {
    `kotlin-dsl`
    id("com.ncorti.ktfmt.gradle") version "0.26.0"
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

ktfmt { kotlinLangStyle() }
