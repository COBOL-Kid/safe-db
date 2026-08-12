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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11")
    implementation("com.oracle.database.jdbc:ojdbc11:23.26.3.0.0")
    implementation("com.github.javakeyring:java-keyring:1.0.4")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}

// Dev-only seeding tooling: compiled and run on demand, never packaged into the shipped jar.
val tools = sourceSets.create("tools")

tools.compileClasspath += sourceSets.main.get().output

tools.runtimeClasspath += sourceSets.main.get().output

configurations[tools.implementationConfigurationName].extendsFrom(
    configurations.implementation.get()
)

configurations[tools.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val integrationTest = sourceSets.create("integrationTest")

integrationTest.compileClasspath += sourceSets.main.get().output

integrationTest.runtimeClasspath += sourceSets.main.get().output

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)

configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)

dependencies {
    add(integrationTest.implementationConfigurationName, kotlin("test"))
    add(
        integrationTest.implementationConfigurationName,
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0",
    )
    add(integrationTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:6.1.2")
    add(
        integrationTest.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher:6.1.2",
    )
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
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
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
