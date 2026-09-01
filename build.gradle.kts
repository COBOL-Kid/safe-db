import com.safedb.buildlogic.VerifyCoverageRatchet
import com.safedb.buildlogic.VerifyIntegrationTestDiscovery
import com.safedb.buildlogic.VerifyUnitTestDiscovery
import com.safedb.buildlogic.splitSeedTaskArgs
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlinx.kover")
    id("com.ncorti.ktfmt.gradle")
    id("org.jetbrains.qodana")
}

group = "com.safedb"

version = "0.1.6"

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(testFixtures(project(":shared")))
    kover(project(":shared"))
}

// Kover 0.9.9 broadens the root total report when standalone subproject report/verify tasks share
// an unqualified task-selector graph. The root aggregate and MCP unit variant are canonical.
listOf(project(":shared"), project(":mcp")).forEach { subproject ->
    subproject.pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        listOf("koverXmlReport", "koverVerify").forEach { taskName ->
            subproject.tasks.named(taskName).configure { enabled = false }
        }
    }
}

ktfmt { kotlinLangStyle() }

// Dev-only headless renderers: compiled and run on demand, never packaged into the shipped app.
val render = sourceSets.create("render")

render.compileClasspath += sourceSets.main.get().output

render.runtimeClasspath += sourceSets.main.get().output

configurations[render.implementationConfigurationName].extendsFrom(
    configurations.implementation.get()
)

configurations[render.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies { add(render.implementationConfigurationName, testFixtures(project(":shared"))) }

val generateAppVersion =
    tasks.register("generateAppVersion") {
        val outputDir = layout.buildDirectory.dir("generated/resources/appVersion")
        val appVersion = provider { version.toString() }
        inputs.property("version", appVersion)
        outputs.dir(outputDir)
        doLast {
            val file = outputDir.get().file("app-version.txt").asFile
            file.parentFile.mkdirs()
            file.writeText(appVersion.get())
        }
    }

sourceSets.named("main") { resources.srcDir(generateAppVersion) }

tasks.named<KotlinCompile>("compileRenderKotlin") {
    friendPaths.from(
        tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory }
    )
}

qodana { resultsPath.set(layout.buildDirectory.dir("qodana/results").get().asFile.absolutePath) }

tasks.qodanaScan {
    arguments.set(
        listOf(
            "--linter",
            "qodana-jvm-community",
            "--within-docker",
            "false",
            "--print-problems",
        )
    )
}

kover {
    reports {
        filters {
            includes {
                classes(
                    "com.safedb.AppState*",
                    "com.safedb.AppVersion*",
                    "com.safedb.AppWindowIcon*",
                    "com.safedb.export.*",
                    "com.safedb.platform.*",
                    "com.safedb.viewmodel.*",
                    "com.safedb.ui.ConnectionFormState*",
                    "com.safedb.adapter.*",
                    "com.safedb.connection.*",
                    "com.safedb.explore.*",
                    "com.safedb.model.*",
                    "com.safedb.persist.*",
                    "com.safedb.query.*",
                    "com.safedb.schema.*",
                    "com.safedb.secrets.*",
                    "com.safedb.service.*",
                    "com.safedb.store.*",
                )
            }
            excludes { classes("*ComposableSingletons*", "*\$\$serializer*") }
        }
        // Keep the aggregate filters attached to the total variant even when task selectors also
        // match Kover tasks in subprojects.
        total {
            filters {
                includes {
                    classes(
                        "com.safedb.AppState*",
                        "com.safedb.AppVersion*",
                        "com.safedb.AppWindowIcon*",
                        "com.safedb.export.*",
                        "com.safedb.platform.*",
                        "com.safedb.viewmodel.*",
                        "com.safedb.ui.ConnectionFormState*",
                        "com.safedb.adapter.*",
                        "com.safedb.connection.*",
                        "com.safedb.explore.*",
                        "com.safedb.model.*",
                        "com.safedb.persist.*",
                        "com.safedb.query.*",
                        "com.safedb.schema.*",
                        "com.safedb.secrets.*",
                        "com.safedb.service.*",
                        "com.safedb.store.*",
                    )
                }
                excludes { classes("*ComposableSingletons*", "*\$\$serializer*") }
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.safedb.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            appResourcesRootDir.set(layout.projectDirectory.dir("packaging/resources"))
            packageName = "safe-db"
            // The macOS jpackage tool rejects installer versions whose major component is zero.
            packageVersion = "1.0.0"
            vendor = "safe-db"
            description = "Safely explore production databases"
        }
    }
}

tasks.test { useJUnitPlatform() }

val verifyUnitTestDiscovery =
    tasks.register<VerifyUnitTestDiscovery>("verifyUnitTestDiscovery") {
        group = "verification"
        description =
            "Fails when JVM unit tests are missing from JUnit XML or contain failures or skips."
        dependsOn(tasks.test, ":shared:test", ":mcp:test")
        desktopResults.set(layout.buildDirectory.dir("test-results/test"))
        sharedResults.set(project(":shared").layout.buildDirectory.dir("test-results/test"))
        mcpResults.set(project(":mcp").layout.buildDirectory.dir("test-results/test"))
        minimumDesktopTests.set(309)
        minimumSharedTests.set(540)
        minimumMcpTests.set(84)
    }

val verifyCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyCoverageRatchet") {
        group = "verification"
        description = "Enforces checked-in line coverage floors for desktop and shared logic."
        dependsOn("koverXmlReport")
        reportFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
        coverageFloors.set(mapOf("desktop" to 90, "shared" to 85))
    }

val verifyMcpCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyMcpCoverageRatchet") {
        group = "verification"
        description = "Enforces the checked-in MCP line coverage floor."
        dependsOn(":mcp:koverXmlReportUnit")
        reportFile.set(project(":mcp").layout.buildDirectory.file("reports/kover/reportUnit.xml"))
        coverageFloors.set(mapOf("mcp" to 91))
    }

tasks.named("koverVerify") { dependsOn(verifyCoverageRatchet, verifyMcpCoverageRatchet) }

val testDockerDatabaseHarness =
    tasks.register<Exec>("testDockerDatabaseHarness") {
        group = "verification"
        description = "Tests Docker database harness orchestration without live containers."
        workingDir = projectDir
        commandLine("bash", "scripts/test_docker_test_databases.sh")
        onlyIf { !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    }

tasks.check { dependsOn(verifyUnitTestDiscovery, testDockerDatabaseHarness, "koverVerify") }

val verifyIntegrationTestDiscovery =
    tasks.register<VerifyIntegrationTestDiscovery>("verifyIntegrationTestDiscovery") {
        group = "verification"
        description =
            "Ensures required JDBC and MCP integration suites executed instead of silently skipping."
        dependsOn(":shared:integrationTest", ":mcp:integrationTest")
        sharedResultsDirectory.set(
            project(":shared").layout.buildDirectory.dir("test-results/integrationTest")
        )
        mcpResultsDirectory.set(
            project(":mcp").layout.buildDirectory.dir("test-results/integrationTest")
        )
        requireMysql.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_MYSQL")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false)
        )
        requirePostgres.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_POSTGRES")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false)
        )
        requireMssql.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_MSSQL")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false)
        )
        requireOracle.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_ORACLE")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false)
        )
    }

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC and :mcp integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}

// Resolved lazily from inside task configuration blocks, after :shared has been evaluated.
fun sharedToolsRuntimeClasspath(): FileCollection =
    project(":shared")
        .extensions
        .getByType<SourceSetContainer>()
        .getByName("tools")
        .runtimeClasspath

fun seedTaskArgs(taskName: String): List<String> {
    val propertyName = "${taskName}Args"
    return splitSeedTaskArgs(providers.gradleProperty(propertyName).orElse("").get(), propertyName)
}

tasks.register<JavaExec>("seedMysql") {
    group = "safe-db"
    description = "Seed the local safe-db MySQL test database."
    classpath = sharedToolsRuntimeClasspath()
    mainClass.set("com.safedb.tools.SeedMysqlKt")
    workingDir = projectDir
    args(seedTaskArgs("seedMysql"))
}

fun registerRelationalSeedTask(
    taskName: String,
    dialect: String,
) {
    tasks.register<JavaExec>(taskName) {
        group = "safe-db"
        description = "Seed the local safe-db $dialect test database."
        classpath = sharedToolsRuntimeClasspath()
        mainClass.set("com.safedb.tools.SeedRelationalKt")
        workingDir = projectDir
        args(dialect, *seedTaskArgs(taskName).toTypedArray())
    }
}

registerRelationalSeedTask("seedPostgres", "postgres")

registerRelationalSeedTask("seedMssql", "mssql")

registerRelationalSeedTask("seedOracle", "oracle")

tasks.register<JavaExec>("renderPreview") {
    group = "safe-db"
    description = "Render main screens headlessly to /tmp/safedb-preview for visual checks."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderPreviewKt")
}

tasks.register<JavaExec>("renderReportExport") {
    group = "safe-db"
    description = "Export a genuine Explore HTML report to /tmp/safedb-preview/report."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderReportExportKt")
}

tasks.register<JavaExec>("renderHeroFrames") {
    group = "safe-db"
    description = "Render the query-builder build-up sequence to /tmp/safedb-preview/hero."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.HeroFramesKt")
}

tasks.register<JavaExec>("renderThemeGallery") {
    group = "safe-db"
    description = "Render the color scheme picker and Connections screen for every scheme."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderThemeGalleryKt")
}
