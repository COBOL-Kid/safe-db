package com.safedb.buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyUnitTestDiscovery : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val desktopResults: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedResults: DirectoryProperty

    @get:Input
    abstract val minimumDesktopTests: Property<Int>

    @get:Input
    abstract val minimumSharedTests: Property<Int>

    @TaskAction
    fun verify() {
        verifySuite("desktop", desktopResults.get().asFile, minimumDesktopTests.get())
        verifySuite("shared", sharedResults.get().asFile, minimumSharedTests.get())
    }

    private fun verifySuite(label: String, resultsDir: File, minimumTests: Int) {
        val reports = junitReports(resultsDir)
        check(reports.isNotEmpty()) { "$label produced no JUnit XML reports in $resultsDir" }
        val testcase = Regex("<testcase\\b")
        val failure = Regex("<(failure|error)\\b")
        val skipped = Regex("<skipped\\b")
        val executed = reports.sumOf { report -> testcase.findAll(report.readText()).count() }
        val failures = reports.sumOf { report -> failure.findAll(report.readText()).count() }
        val skippedTests = reports.sumOf { report -> skipped.findAll(report.readText()).count() }
        check(failures == 0) { "$label JUnit XML contains $failures failures/errors" }
        check(skippedTests == 0) { "$label JUnit XML contains $skippedTests skipped tests" }
        check(executed >= minimumTests) {
            "$label discovered $executed tests; expected at least $minimumTests. " +
                "Raise this floor when intentionally adding tests, never lower it to hide missing discovery."
        }
        logger.lifecycle("$label discovery verified: $executed tests")
    }
}

abstract class VerifyCoverageRatchet : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportFile: RegularFileProperty

    @get:Input
    abstract val coverageFloors: MapProperty<String, Int>

    @TaskAction
    fun verify() {
        val report = reportFile.get().asFile
        check(report.isFile) { "Kover XML report not found at $report" }
        val document = DocumentBuilderFactory.newInstance()
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

        for ((group, counts) in totals) {
            val total = counts[0] + counts[1]
            check(total > 0) { "Kover report contained no $group lines" }
            val floor = coverageFloors.get().getValue(group)
            val percent = counts[0] * 100.0 / total
            check(percent >= floor) {
                "$group line coverage %.2f%% is below the %d%% ratchet".format(percent, floor)
            }
            logger.lifecycle("$group line coverage: %.2f%% (floor %d%%)".format(percent, floor))
        }
    }
}

abstract class VerifyIntegrationTestDiscovery : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultsDirectory: DirectoryProperty

    @get:Input
    abstract val requireMysql: Property<Boolean>

    @get:Input
    abstract val requirePostgres: Property<Boolean>

    @TaskAction
    fun verify() {
        if (!requireMysql.get() && !requirePostgres.get()) {
            logger.lifecycle("No JDBC engine is required; integration suites may skip locally.")
            return
        }

        val resultsDir = resultsDirectory.get().asFile
        val reports = junitReports(resultsDir)
        check(reports.isNotEmpty()) { "Integration tests produced no JUnit XML in $resultsDir" }

        val engines = mutableMapOf(
            "mysql" to EngineResult(),
            "postgres" to EngineResult(),
        )
        for (report in reports) {
            val document = DocumentBuilderFactory.newInstance()
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

        verifyRequired("mysql", requireMysql.get(), minimum = 5, engines.getValue("mysql"))
        verifyRequired("postgres", requirePostgres.get(), minimum = 3, engines.getValue("postgres"))
    }

    private fun verifyRequired(engine: String, required: Boolean, minimum: Int, result: EngineResult) {
        if (!required) return
        check(result.discovered >= minimum) {
            "$engine integration discovery found ${result.discovered} tests; expected at least $minimum"
        }
        check(result.skipped == 0) {
            "$engine is required but ${result.skipped} of ${result.discovered} integration tests were skipped"
        }
        logger.lifecycle("$engine integration discovery verified: ${result.discovered} executed tests")
    }

    private data class EngineResult(var discovered: Int = 0, var skipped: Int = 0)
}

private fun junitReports(resultsDir: File): List<File> =
    resultsDir.listFiles { file ->
        file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
    }.orEmpty().toList()
