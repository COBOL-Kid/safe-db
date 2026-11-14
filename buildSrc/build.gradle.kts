plugins {
    `kotlin-dsl`
    id("com.ncorti.ktfmt.gradle") version "0.28.0"
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

// ktfmt 0.65 still pins Guava 33.5.0-jre (GHSA-xxph-c9ww-hj94); use the patched release.
configurations
    .matching { it.name == "ktfmt" }
    .configureEach { resolutionStrategy.force("com.google.guava:guava:33.8.0-jre") }
