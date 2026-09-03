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
            excludes { classes($$$"*$$serializer*") }
        }
        variant("unit") {
            filters {
                includes { classes("com.safedb.mcp.*") }
                excludes { classes($$$"*$$serializer*") }
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
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
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
            .flatMap { it.archiveFile }
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
        }
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
                }
            )
        }
        val bundledJar = assembleNpm.flatMap { task ->
            task.outputDir.file(
                currentMcpNpmPlatform.map { platform ->
                    "@safe-db/mcp-$platform/lib/safe-db-mcp.jar"
                }
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
