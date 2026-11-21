import com.safedb.buildlogic.logIntegrationFailures

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    id("com.ncorti.ktfmt.gradle")
    `java-test-fixtures`
}

ktfmt { kotlinLangStyle() }

group = "com.safedb"

version = "0.0.1"

kotlin { jvmToolchain(25) }

kover {
    currentProject {
        instrumentation { disabledForTestTasks.add("integrationTest") }
        sources { excludedSourceSets.add("integrationTest") }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hikaricp)
    implementation(libs.jdbc.postgresql)
    implementation(libs.jdbc.mysql)
    implementation(libs.jdbc.mssql)
    implementation(libs.jdbc.oracle)
    implementation(libs.jdbc.db2)
    implementation(libs.java.keyring)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)

    testFixturesImplementation(kotlin("test"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.junit.jupiter)
}

// Dev-only seeding tooling: compiled and run on demand, never packaged into the shipped jar.
val tools = sourceSets.create("tools")

tools.compileClasspath += sourceSets.main.get().output

tools.runtimeClasspath += sourceSets.main.get().output

configurations[tools.implementationConfigurationName].extendsFrom(
    configurations.implementation.get(),
)

configurations[tools.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val integrationTest = sourceSets.create("integrationTest")

integrationTest.compileClasspath +=
    sourceSets.main.get().output + sourceSets.testFixtures.get().output

integrationTest.runtimeClasspath +=
    sourceSets.main.get().output + sourceSets.testFixtures.get().output

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)

configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

dependencies {
    add(integrationTest.implementationConfigurationName, kotlin("test"))
    add(integrationTest.implementationConfigurationName, libs.kotlinx.coroutines.test)
    add(integrationTest.implementationConfigurationName, libs.junit.jupiter)
    add(integrationTest.runtimeOnlyConfigurationName, libs.junit.platform.launcher)
}

// SeedMysqlTest stays in the standard test source set so it keeps running under `check`.
sourceSets.test.configure {
    compileClasspath += tools.output
    runtimeClasspath += tools.output
}

tasks.test { useJUnitPlatform() }

// Running the JDBC suite needs live engines, but compiling it does not; without this a source-set
// change can break it and stay hidden until someone runs integrationTest by hand.
tasks.check { dependsOn("compileIntegrationTestKotlin") }

tasks.register<Test>("integrationTest") {
    description = "Runs JDBC integration tests tagged @Tag(\"integration\")."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    logIntegrationFailures()
    shouldRunAfter(tasks.test)
}

// Associate rather than set friendPaths by hand: applying java-test-fixtures adds compilations that
// leave a raw friendPaths wiring unable to see `internal` members of main.
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

kotlin.target.compilations.named("tools") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

kotlin.target.compilations.named("test") {
    associateWith(kotlin.target.compilations.getByName("tools"))
}
