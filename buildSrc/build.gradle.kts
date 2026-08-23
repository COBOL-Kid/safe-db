plugins {
    `kotlin-dsl`
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies { testImplementation(kotlin("test")) }

tasks.test { useJUnitPlatform() }

// Outer builds only ask buildSrc for its jar, never for `check`, so finalize the jar with the tests
// or they would never run. A dependsOn would cycle: `kotlin-dsl` puts the jar on the test
// classpath.
tasks.jar { finalizedBy(tasks.test) }

ktfmt { kotlinLangStyle() }
