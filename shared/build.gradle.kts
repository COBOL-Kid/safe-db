import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

group = "com.safedb"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.mysql:mysql-connector-j:9.3.0")
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.10.0.jre11")
    implementation("com.oracle.database.jdbc:ojdbc11:23.8.0.25.04")
    implementation("com.github.javakeyring:java-keyring:1.0.4")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

val integrationTest = sourceSets.create("integrationTest")
integrationTest.compileClasspath += sourceSets.main.get().output
integrationTest.runtimeClasspath += sourceSets.main.get().output

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, kotlin("test"))
    add(integrationTest.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    add(integrationTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.12.2")
    add(integrationTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs JDBC integration tests tagged @Tag(\"integration\")."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    shouldRunAfter(tasks.test)
}

tasks.named<KotlinCompile>("compileIntegrationTestKotlin") {
    friendPaths.from(tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory })
}
