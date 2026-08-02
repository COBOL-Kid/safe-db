import com.safedb.buildlogic.VerifyCoverageRatchet
import com.safedb.buildlogic.VerifyIntegrationTestDiscovery
import com.safedb.buildlogic.VerifyUnitTestDiscovery
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlinx.kover")
}

group = "com.safedb"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    kover(project(":shared"))
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
                    "com.safedb.secrets.*",
                    "com.safedb.service.*",
                    "com.safedb.store.*",
                    "com.safedb.tools.SeedMysql*",
                )
            }
            excludes {
                classes(
                    "*ComposableSingletons*",
                    "*\$\$serializer*",
                    "com.safedb.tools.RenderPreview*",
                )
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.safedb.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Rpm)
            packageName = "safe-db"
            packageVersion = "0.1.0"
            vendor = "safe-db"
            description = "Safely explore production databases"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

val verifyUnitTestDiscovery = tasks.register<VerifyUnitTestDiscovery>("verifyUnitTestDiscovery") {
    group = "verification"
    description = "Fails when JVM unit tests are missing from JUnit XML or contain failures or skips."
    dependsOn(tasks.test, ":shared:test")
    desktopResults.set(layout.buildDirectory.dir("test-results/test"))
    sharedResults.set(project(":shared").layout.buildDirectory.dir("test-results/test"))
    minimumDesktopTests.set(143)
    minimumSharedTests.set(295)
}

val verifyCoverageRatchet = tasks.register<VerifyCoverageRatchet>("verifyCoverageRatchet") {
    group = "verification"
    description = "Enforces checked-in line coverage floors for shared and desktop logic."
    dependsOn("koverXmlReport")
    reportFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
    coverageFloors.set(mapOf("desktop" to 72, "shared" to 66))
}

tasks.named("koverVerify") {
    dependsOn(verifyCoverageRatchet)
}

tasks.check {
    dependsOn(verifyUnitTestDiscovery, "koverVerify")
}

val verifyIntegrationTestDiscovery =
    tasks.register<VerifyIntegrationTestDiscovery>("verifyIntegrationTestDiscovery") {
    group = "verification"
    description = "Ensures required JDBC engine suites executed instead of silently skipping."
    dependsOn(":shared:integrationTest")
    resultsDirectory.set(project(":shared").layout.buildDirectory.dir("test-results/integrationTest"))
    requireMysql.set(
        providers.environmentVariable("SAFEDB_TEST_REQUIRE_MYSQL")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false),
    )
    requirePostgres.set(
        providers.environmentVariable("SAFEDB_TEST_REQUIRE_POSTGRES")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false),
    )
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}

tasks.register<JavaExec>("seedMysql") {
    group = "safe-db"
    description = "Seed the local safe-db MySQL test database."
    val shared = project(":shared")
    val sharedJar = shared.tasks.named("jar")
    dependsOn(sharedJar)
    classpath = files(sharedJar, shared.configurations.named("runtimeClasspath"))
    mainClass.set("com.safedb.tools.SeedMysqlKt")
    workingDir = projectDir
    args(splitSeedMysqlArgs(providers.gradleProperty("seedMysqlArgs").orElse("").get()))
}

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
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.safedb.tools.RenderPreviewKt")
}

tasks.register<JavaExec>("renderThemeGallery") {
    group = "safe-db"
    description = "Render the color scheme picker and Connections screen for every scheme."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.safedb.tools.RenderThemeGalleryKt")
}
