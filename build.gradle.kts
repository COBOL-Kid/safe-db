import com.safedb.buildlogic.VerifyCoverageRatchet
import com.safedb.buildlogic.VerifyIntegrationTestDiscovery
import com.safedb.buildlogic.VerifyUnitTestDiscovery
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlinx.kover")
    id("com.ncorti.ktfmt.gradle")
    id("org.jetbrains.qodana")
}

group = "com.safedb"

version = "0.0.1"

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(testFixtures(project(":shared")))
    kover(project(":shared"))
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
        dependsOn(tasks.test, ":shared:test")
        desktopResults.set(layout.buildDirectory.dir("test-results/test"))
        sharedResults.set(project(":shared").layout.buildDirectory.dir("test-results/test"))
        minimumDesktopTests.set(212)
        minimumSharedTests.set(348)
    }

val verifyCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyCoverageRatchet") {
        group = "verification"
        description = "Enforces checked-in line coverage floors for shared and desktop logic."
        dependsOn("koverXmlReport")
        reportFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
        coverageFloors.set(mapOf("desktop" to 72, "shared" to 66))
    }

tasks.named("koverVerify") { dependsOn(verifyCoverageRatchet) }

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
        description = "Ensures required JDBC engine suites executed instead of silently skipping."
        dependsOn(":shared:integrationTest")
        resultsDirectory.set(
            project(":shared").layout.buildDirectory.dir("test-results/integrationTest")
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
    }

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}

// Resolved lazily from inside task configuration blocks, after :shared has been evaluated.
fun sharedToolsRuntimeClasspath(): FileCollection =
    project(":shared")
        .extensions
        .getByType<SourceSetContainer>()
        .getByName("tools")
        .runtimeClasspath

tasks.register<JavaExec>("seedMysql") {
    group = "safe-db"
    description = "Seed the local safe-db MySQL test database."
    classpath = sharedToolsRuntimeClasspath()
    mainClass.set("com.safedb.tools.SeedMysqlKt")
    workingDir = projectDir
    args(splitSeedMysqlArgs(providers.gradleProperty("seedMysqlArgs").orElse("").get()))
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
        val propertyName = "${taskName}Args"
        args(
            dialect,
            *splitSeedMysqlArgs(providers.gradleProperty(propertyName).orElse("").get())
                .toTypedArray(),
        )
    }
}

registerRelationalSeedTask("seedPostgres", "postgres")

registerRelationalSeedTask("seedMssql", "mssql")

registerRelationalSeedTask("seedOracle", "oracle")

fun splitSeedMysqlArgs(raw: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    for (char in raw) {
        when {
            escaping -> {
                current.append(char)
                escaping = false
            }
            char == '\\' -> escaping = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(char)
        }
    }
    if (escaping) current.append('\\')
    if (quote != null) throw GradleException("Unclosed quote in seedMysqlArgs")
    if (current.isNotEmpty()) args.add(current.toString())
    return args
}

tasks.register<JavaExec>("renderPreview") {
    group = "safe-db"
    description = "Render main screens headlessly to /tmp/safedb-preview for visual checks."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderPreviewKt")
}

tasks.register<JavaExec>("renderThemeGallery") {
    group = "safe-db"
    description = "Render the color scheme picker and Connections screen for every scheme."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderThemeGalleryKt")
}
