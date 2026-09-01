plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    id("com.ncorti.ktfmt.gradle")
    id("com.gradleup.shadow")
    application
}

ktfmt { kotlinLangStyle() }

group = "com.safedb"

version = rootProject.version

kotlin { jvmToolchain(25) }

application { mainClass.set("com.safedb.mcp.MainKt") }

kover {
    currentProject {
        instrumentation { disabledForTestTasks.add("integrationTest") }
        sources { excludedSourceSets.add("integrationTest") }
        createVariant("unit") { add("jvm") }
    }
    reports {
        filters {
            includes { classes("com.safedb.mcp.*") }
            excludes { classes("*\$\$serializer*") }
        }
        variant("unit") {
            filters {
                includes { classes("com.safedb.mcp.*") }
                excludes { classes("*\$\$serializer*") }
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":shared")))
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

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
        testFixtures(project(":shared")),
    )
    add(
        integrationTest.implementationConfigurationName,
        "io.modelcontextprotocol:kotlin-sdk-client:0.15.0",
    )
    add(
        integrationTest.implementationConfigurationName,
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0",
    )
    add(integrationTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:6.1.3")
    add(
        integrationTest.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher:6.1.3",
    )
}

val generateMcpVersion =
    tasks.register("generateMcpVersion") {
        val outputDir = layout.buildDirectory.dir("generated/resources/mcpVersion")
        val mcpVersion = provider { version.toString() }
        inputs.property("version", mcpVersion)
        outputs.dir(outputDir)
        doLast {
            val file = outputDir.get().file("mcp-version.txt").asFile
            file.parentFile.mkdirs()
            file.writeText(mcpVersion.get())
        }
    }

sourceSets.named("main") { resources.srcDir(generateMcpVersion) }

tasks.check { dependsOn("compileIntegrationTestKotlin") }

tasks.register<Test>("integrationTest") {
    description = "Runs MCP integration tests tagged @Tag(\"integration\")."
    group = "verification"
    val mcpShadowJar =
        tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val mcpShadowJarFile = mcpShadowJar.flatMap { it.archiveFile }
    dependsOn(mcpShadowJar)
    inputs.file(mcpShadowJarFile).withPropertyName("mcpShadowJar")
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    systemProperty("safedb.mcp.shadowJar", mcpShadowJarFile.get().asFile.absolutePath)
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

// Integration tests exercise internal MCP server wiring without widening the production API.
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.shadowJar {
    archiveBaseName.set("safe-db-mcp")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    manifest { attributes["Main-Class"] = "com.safedb.mcp.MainKt" }
}

tasks.test { useJUnitPlatform() }
