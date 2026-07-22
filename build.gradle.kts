import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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

val verifyUnitTestDiscovery = tasks.register("verifyUnitTestDiscovery") {
    group = "verification"
    description = "Fails when JVM unit tests are missing from JUnit XML or contain failures."
    dependsOn(tasks.test, ":shared:jvmTest")

    doLast {
        fun verifySuite(label: String, resultsDir: File, minimumTests: Int) {
            val reports = resultsDir.listFiles { file ->
                file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
            }.orEmpty()
            check(reports.isNotEmpty()) { "$label produced no JUnit XML reports in $resultsDir" }
            val testcase = Regex("<testcase\\b")
            val failure = Regex("<(failure|error)\\b")
            val executed = reports.sumOf { report -> testcase.findAll(report.readText()).count() }
            val failures = reports.sumOf { report -> failure.findAll(report.readText()).count() }
            check(failures == 0) { "$label JUnit XML contains $failures failures/errors" }
            check(executed >= minimumTests) {
                "$label discovered $executed tests; expected at least $minimumTests. " +
                    "Raise this floor when intentionally adding tests, never lower it to hide missing discovery."
            }
            logger.lifecycle("$label discovery verified: $executed tests")
        }

        verifySuite("desktop", layout.buildDirectory.dir("test-results/test").get().asFile, 78)
        verifySuite("shared", project(":shared").layout.buildDirectory.dir("test-results/jvmTest").get().asFile, 212)
    }
}

val verifyCoverageRatchet = tasks.register("verifyCoverageRatchet") {
    group = "verification"
    description = "Enforces checked-in line coverage floors for shared and desktop logic."
    dependsOn("koverXmlReport")

    doLast {
        val report = layout.buildDirectory.file("reports/kover/report.xml").get().asFile
        check(report.isFile) { "Kover XML report not found at $report" }
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(report)
        val totals = mutableMapOf(
            "desktop" to intArrayOf(0, 0),
            "shared" to intArrayOf(0, 0),
        )
        val packages = document.getElementsByTagName("package")
        for (index in 0 until packages.length) {
            val packageNode = packages.item(index)
            val packageName = packageNode.attributes.getNamedItem("name").nodeValue
            val group = if (
                packageName == "com/safedb" ||
                Regex("com/safedb/(platform|export|viewmodel|ui)").matches(packageName)
            ) {
                "desktop"
            } else {
                "shared"
            }
            val children = packageNode.childNodes
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex)
                if (child.nodeName != "counter") continue
                if (child.attributes.getNamedItem("type").nodeValue != "LINE") continue
                totals.getValue(group)[0] += child.attributes.getNamedItem("covered").nodeValue.toInt()
                totals.getValue(group)[1] += child.attributes.getNamedItem("missed").nodeValue.toInt()
            }
        }

        val floors = mapOf("desktop" to 72, "shared" to 66)
        for ((group, counts) in totals) {
            val total = counts[0] + counts[1]
            check(total > 0) { "Kover report contained no $group lines" }
            val percent = counts[0] * 100.0 / total
            check(percent >= floors.getValue(group)) {
                "$group line coverage %.2f%% is below the %d%% ratchet".format(percent, floors.getValue(group))
            }
            logger.lifecycle("$group line coverage: %.2f%% (floor %d%%)".format(percent, floors.getValue(group)))
        }
    }
}

tasks.named("koverVerify") {
    dependsOn(verifyCoverageRatchet)
}

tasks.check {
    dependsOn(verifyUnitTestDiscovery, "koverVerify")
}

val verifyIntegrationTestDiscovery = tasks.register("verifyIntegrationTestDiscovery") {
    group = "verification"
    description = "Ensures required JDBC engine suites executed instead of silently skipping."
    dependsOn(":shared:integrationTest")

    doLast {
        val requireMysql = System.getenv("SAFEDB_TEST_REQUIRE_MYSQL").equals("true", ignoreCase = true)
        val requirePostgres = System.getenv("SAFEDB_TEST_REQUIRE_POSTGRES").equals("true", ignoreCase = true)
        if (!requireMysql && !requirePostgres) {
            logger.lifecycle("No JDBC engine is required; integration suites may skip locally.")
            return@doLast
        }
        val resultsDir = project(":shared").layout.buildDirectory.dir("test-results/integrationTest").get().asFile
        val reports = resultsDir.listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
        }.orEmpty()
        check(reports.isNotEmpty()) { "Integration tests produced no JUnit XML in $resultsDir" }

        data class EngineResult(var discovered: Int = 0, var skipped: Int = 0)
        val engines = mutableMapOf("mysql" to EngineResult(), "postgres" to EngineResult())
        for (report in reports) {
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(report)
            val testcases = document.getElementsByTagName("testcase")
            for (index in 0 until testcases.length) {
                val testcase = testcases.item(index)
                val className = testcase.attributes.getNamedItem("classname").nodeValue
                val engine = when {
                    className.contains("MySql") -> "mysql"
                    className.contains("Postgres") -> "postgres"
                    else -> continue
                }
                engines.getValue(engine).discovered += 1
                val children = testcase.childNodes
                for (childIndex in 0 until children.length) {
                    if (children.item(childIndex).nodeName == "skipped") {
                        engines.getValue(engine).skipped += 1
                    }
                }
            }
        }

        fun verifyRequired(engine: String, required: Boolean, minimum: Int) {
            if (!required) return
            val result = engines.getValue(engine)
            check(result.discovered >= minimum) {
                "$engine integration discovery found ${result.discovered} tests; expected at least $minimum"
            }
            check(result.skipped == 0) {
                "$engine is required but ${result.skipped} of ${result.discovered} integration tests were skipped"
            }
            logger.lifecycle("$engine integration discovery verified: ${result.discovered} executed tests")
        }
        verifyRequired("mysql", requireMysql, 5)
        verifyRequired("postgres", requirePostgres, 3)
    }
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
    val kotlinExt = shared.extensions.getByType<KotlinMultiplatformExtension>()
    val mainCompilation = kotlinExt.targets.getByName("jvm").compilations.getByName("main")
    val sharedJar = shared.tasks.named("jvmJar")
    dependsOn(sharedJar)
    classpath = files(sharedJar) + (mainCompilation.runtimeDependencyFiles ?: files())
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
