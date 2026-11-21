import com.safedb.buildlogic.ExtractMacSkikoNative
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
    id("dev.hydraulic.conveyor")
}

group = "com.safedb"

version = "1.1.3"

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    "windowsAmd64"(libs.compose.desktop.windows.x64)
    "macAarch64"(libs.compose.desktop.macos.arm64)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":shared")))
    kover(project(":shared"))
}

// Kover 0.9.11 broadens the root total report when standalone subproject report/verify tasks share
// an unqualified task-selector graph. The root aggregate and MCP unit variant are canonical.
listOf(project(":shared"), project(":mcp")).forEach { subproject ->
    subproject.pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        listOf("koverXmlReport", "koverVerify").forEach { taskName ->
            subproject.tasks.named(taskName).configure { enabled = false }
        }
    }
}

ktfmt { kotlinLangStyle() }

// ktfmt 0.65 still pins Guava 33.5.0-jre, which is affected by GHSA-xxph-c9ww-hj94
// (CVE-2026-102554). The formatter is a build-only tool, so force the patched release on its
// isolated configuration rather than touching application dependencies.
allprojects {
    configurations
        .matching { it.name == "ktfmt" }
        .configureEach { resolutionStrategy.force("com.google.guava:guava:33.8.0-jre") }
}

// Dev-only headless renderers: compiled and run on demand, never packaged into the shipped app.
val render = sourceSets.create("render")

render.compileClasspath += sourceSets.main.get().output

render.runtimeClasspath += sourceSets.main.get().output

configurations[render.implementationConfigurationName].extendsFrom(
    configurations.implementation.get(),
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

// Skiko loads java.home/lib/libskiko-macos-arm64.dylib before it extracts the JAR copy into
// ~/.skiko. Conveyor signs the JAR copy in place and leaves the upstream .sha256 cache key, so a
// notarized Mac launch dlopens a library signed by a different team. jar produces the loose dylib
// conveyor.conf passes as a mac input; Conveyor moves that shared library next to the JVM's and
// signs it with the app. Windows keeps loading the DLL from the JAR.
val extractMacSkikoNative =
    tasks.register<ExtractMacSkikoNative>("extractMacSkikoNative") {
        group = "build"
        description =
            "Unpacks the upstream libskiko-macos-arm64.dylib so Conveyor can sign it outside the JAR."
        macRuntimeJars.from(configurations.named("macAarch64"))
        dylib.set(layout.buildDirectory.file("skiko-natives/libskiko-macos-arm64.dylib"))
    }

tasks.named("jar") { dependsOn(extractMacSkikoNative) }

tasks.named<KotlinCompile>("compileRenderKotlin") {
    friendPaths.from(
        tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory },
    )
}

qodana { resultsPath.set(layout.buildDirectory.dir("qodana/results").get().asFile.absolutePath) }

// The Qodana IntelliJ engine cannot create its directory lock (a Unix domain socket) on a Windows
// filesystem, which is where WSL checkouts under /mnt/c live. Keep the engine state on the Linux
// side so `./gradlew qodanaScan` works from WSL.
val qodanaStateDir =
    providers.systemProperty("java.io.tmpdir").map {
        "$it/qodana/safe-db-${rootDir.absolutePath.hashCode()}"
    }

tasks.qodanaScan {
    arguments.set(
        buildList {
            addAll(
                listOf(
                    "--linter",
                    "qodana-jvm-community",
                    "--within-docker",
                    "false",
                    "--print-problems",
                ),
            )
            if (providers.environmentVariable("WSL_DISTRO_NAME").isPresent) {
                val stateDir = qodanaStateDir.get()
                addAll(
                    listOf(
                        "--property",
                        "idea.config.path=$stateDir/config",
                        "--property",
                        "idea.system.path=$stateDir/system",
                    ),
                )
            }
        },
    )
}

val koverIncludedClasses =
    listOf(
        "com.safedb.AppState*",
        "com.safedb.AppVersion*",
        "com.safedb.AppWindowIcon*",
        "com.safedb.export.*",
        "com.safedb.platform.*",
        "com.safedb.viewmodel.*",
        "com.safedb.ui.ConnectionFormState*",
        "com.safedb.adapter.*",
        "com.safedb.canvas.*",
        "com.safedb.connection.*",
        "com.safedb.explore.*",
        "com.safedb.launch.*",
        "com.safedb.model.*",
        "com.safedb.persist.*",
        "com.safedb.query.*",
        "com.safedb.schema.*",
        "com.safedb.secrets.*",
        "com.safedb.service.*",
        "com.safedb.store.*",
    )

val koverExcludedClasses = listOf("*ComposableSingletons*", "*\$\$serializer*")

kover {
    reports {
        filters {
            includes { classes(*koverIncludedClasses.toTypedArray()) }
            excludes { classes(*koverExcludedClasses.toTypedArray()) }
        }
        // Keep the aggregate filters attached to the total variant even when task selectors also
        // match Kover tasks in subprojects.
        total {
            filters {
                includes { classes(*koverIncludedClasses.toTypedArray()) }
                excludes { classes(*koverExcludedClasses.toTypedArray()) }
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
            packageVersion = "1.1.3"
            vendor = "safe-db"
            description = "Safely explore production databases"
        }
    }
}

tasks.test { useJUnitPlatform() }

// Applied scripts have no type-safe accessors, so plugin DSL stays above. Dev tasks need the
// render source set, which is created before these scripts run.
apply(from = "gradle/verification.gradle.kts")

apply(from = "gradle/dev-tasks.gradle.kts")
