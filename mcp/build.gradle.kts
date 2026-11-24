import com.safedb.buildlogic.logIntegrationFailures

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
        instrumentation {
            disabledForTestTasks.add("integrationTest")
            disabledForTestTasks.add("npmPackagedTest")
        }
        sources { excludedSourceSets.add("integrationTest") }
        createVariant("unit") { add("jvm") }
    }
    reports {
        filters {
            includes { classes("com.safedb.mcp.*") }
            excludes {
                classes(
                    $$$"*$$serializer*",
                    "com.safedb.mcp.MainKt*",
                    "com.safedb.mcp.SystemCliIo*",
                )
            }
        }
        variant("unit") {
            filters {
                includes { classes("com.safedb.mcp.*") }
                excludes {
                    classes(
                        $$$"*$$serializer*",
                        "com.safedb.mcp.MainKt*",
                        "com.safedb.mcp.SystemCliIo*",
                    )
                }
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.sdk.server)
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.mcp.sdk.client)
    testImplementation(libs.kotlinx.coroutines.test)
}

val integrationTest = sourceSets.create("integrationTest")

integrationTest.compileClasspath += sourceSets.main.get().output

integrationTest.runtimeClasspath += sourceSets.main.get().output

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)

configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

dependencies {
    add(integrationTest.implementationConfigurationName, kotlin("test"))
    add(
        integrationTest.implementationConfigurationName,
        testFixtures(project(":shared")),
    )
    add(integrationTest.implementationConfigurationName, libs.mcp.sdk.client)
    add(integrationTest.implementationConfigurationName, libs.kotlinx.coroutines.test)
    add(integrationTest.implementationConfigurationName, libs.junit.jupiter)
    add(integrationTest.runtimeOnlyConfigurationName, libs.junit.platform.launcher)
}

val generateMcpVersion =
    tasks.register<com.safedb.buildlogic.WriteVersionResource>("generateMcpVersion") {
        appVersion.set(provider { version.toString() })
        fileName.set("mcp-version.txt")
        outputDir.set(layout.buildDirectory.dir("generated/resources/mcpVersion"))
    }

sourceSets.named("main") { resources.srcDir(generateMcpVersion) }

// Running the MCP suite needs live engines, but compiling it does not; without this a source-set
// change can break it and stay hidden until someone runs integrationTest by hand.
tasks.check { dependsOn("compileIntegrationTestKotlin", "npmCliTest") }

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
    logIntegrationFailures()
    shouldRunAfter(tasks.test)
}

// Custom integrationTest is not a friend of main unless associateWith is set, so tests can call
// internal MCP APIs without making those declarations public.
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.shadowJar {
    archiveBaseName.set("safe-db-mcp")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // EXCLUDE drops extras before transformers. Services and .kotlin_module must stay
    // INCLUDE so mergeServiceFiles and KotlinModuleMetadataTransformer see every copy.
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest { attributes["Main-Class"] = "com.safedb.mcp.MainKt" }
}

tasks.test { useJUnitPlatform() }

val npmSourceDir = layout.projectDirectory.dir("npm")
val temurinManifestFile = npmSourceDir.file("temurin.json")
val currentMcpNpmPlatform = provider {
    com.safedb.buildlogic.npmPlatform(
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
    )
}

fun configureMcpNpm(task: com.safedb.buildlogic.AssembleMcpNpm) {
    task.group = "distribution"
    task.temurinManifest.set(temurinManifestFile)
    task.jlinkModulesFile.set(npmSourceDir.file("jlink-modules.txt"))
    task.npmSource.set(npmSourceDir)
    task.platformTemplate.set(npmSourceDir.file("platform-package.json"))
    task.license.set(rootProject.layout.projectDirectory.file("LICENSE.txt"))
    task.shadowJar.set(
        tasks
            .named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
            .flatMap { it.archiveFile },
    )
    task.packageVersion.set(provider { version.toString() })
    task.cacheDir.set(layout.buildDirectory.dir("npm-cache"))
    task.outputDir.set(layout.buildDirectory.dir("npm"))
    task.dependsOn(tasks.named("shadowJar"))
}

val assembleNpm =
    tasks.register<com.safedb.buildlogic.AssembleMcpNpm>("assembleNpm") {
        description = "jlink the current OS into @safe-db/mcp plus one platform package."
        configureMcpNpm(this)
        platforms.set(currentMcpNpmPlatform.map { listOf(it) })
        val downloadHostJlinkJdk = com.safedb.buildlogic.isLinuxX64()
        downloadJlinkJdk.set(downloadHostJlinkJdk)
        if (!downloadHostJlinkJdk) {
            hostJavaHome.set(System.getProperty("java.home"))
            hostJavaVersion.set(System.getProperty("java.version"))
        }
    }

tasks.register<com.safedb.buildlogic.AssembleMcpNpm>("assembleNpmAllPlatforms") {
    description = "jlink every MCP npm platform package. Requires Linux (Temurin jlink JDK)."
    configureMcpNpm(this)
    // Shared npm-cache is not safe under configuration-cache parallel execution.
    mustRunAfter(assembleNpm)
    outputDir.set(layout.buildDirectory.dir("npm-all"))
    platforms.set(
        com.safedb.buildlogic.parseTemurinManifest(temurinManifestFile.asFile).platforms.map {
            it.npm
        },
    )
    downloadJlinkJdk.set(true)
    onlyIf { com.safedb.buildlogic.isLinuxX64() }
}

tasks.register<Exec>("npmCliTest") {
    group = "verification"
    description = "Runs node --test for the @safe-db/mcp CLI shim."
    workingDir = npmSourceDir.asFile
    commandLine("node", "--test", "cli.test.js")
    onlyIf { com.safedb.buildlogic.nodeOnPath() }
}

val npmPackagedTest =
    tasks.register<Test>("npmPackagedTest") {
        description = "Runs MCP packaged tests against the current-OS jlink runtime."
        group = "verification"
        dependsOn(assembleNpm)
        val bundledJava = assembleNpm.flatMap { task ->
            task.outputDir.file(
                currentMcpNpmPlatform.map { platform ->
                    val java = com.safedb.buildlogic.javaExecutableName(platform)
                    "@safe-db/mcp-$platform/jre/bin/$java"
                },
            )
        }
        val bundledJar = assembleNpm.flatMap { task ->
            task.outputDir.file(
                currentMcpNpmPlatform.map { platform ->
                    "@safe-db/mcp-$platform/lib/safe-db-mcp.jar"
                },
            )
        }
        inputs.file(bundledJava)
        inputs.file(bundledJar)
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        systemProperty("safedb.mcp.shadowJar", bundledJar.get().asFile.absolutePath)
        systemProperty("safedb.mcp.bundledJava", bundledJava.get().asFile.absolutePath)
        useJUnitPlatform { includeTags("integration") }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        shouldRunAfter(tasks.named("integrationTest"))
    }
