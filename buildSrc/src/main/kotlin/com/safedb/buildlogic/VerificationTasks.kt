package com.safedb.buildlogic

import java.io.ByteArrayInputStream
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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mcpResults: DirectoryProperty

    @get:Input abstract val minimumDesktopTests: Property<Int>

    @get:Input abstract val minimumSharedTests: Property<Int>

    @get:Input abstract val minimumMcpTests: Property<Int>

    @TaskAction
    fun verify() {
        verifySuite("desktop", desktopResults.get().asFile, minimumDesktopTests.get())
        verifySuite("shared", sharedResults.get().asFile, minimumSharedTests.get())
        verifySuite("mcp", mcpResults.get().asFile, minimumMcpTests.get())
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

    @get:Input abstract val coverageFloors: MapProperty<String, Int>

    @TaskAction
    fun verify() {
        val report = reportFile.get().asFile
        check(report.isFile) { "Kover XML report not found at $report" }
        for (result in verifyCoverageFloors(report.readText(), coverageFloors.get())) {
            logger.lifecycle(
                "${result.group} line coverage: %.2f%% (floor %d%%)"
                    .format(result.percent, result.floor)
            )
        }
    }
}

abstract class VerifyIntegrationTestDiscovery : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedResultsDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mcpResultsDirectory: DirectoryProperty

    @get:Input abstract val requireMysql: Property<Boolean>

    @get:Input abstract val requirePostgres: Property<Boolean>

    @get:Input abstract val requireMssql: Property<Boolean>

    @get:Input abstract val requireOracle: Property<Boolean>

    @TaskAction
    fun verify() {
        val requiredEngines = buildSet {
            if (requireMysql.get()) add("mysql")
            if (requirePostgres.get()) add("postgres")
            if (requireMssql.get()) add("mssql")
            if (requireOracle.get()) add("oracle")
        }
        if (requiredEngines.isEmpty()) {
            logger.lifecycle("No JDBC engine is required; integration suites may skip locally.")
        } else {
            val sharedResultsDir = sharedResultsDirectory.get().asFile
            val results =
                verifyIntegrationTestDiscovery(
                    junitReports(sharedResultsDir).map(File::readText),
                    requiredEngines,
                    sharedResultsDir.toString(),
                )
            for (result in results) {
                logger.lifecycle(
                    "${result.engine} integration discovery verified: " +
                        "${result.discovered} executed tests"
                )
            }
        }

        val mcpResultsDir = mcpResultsDirectory.get().asFile
        val mcpResults =
            verifyMcpIntegrationTestDiscovery(
                junitReports(mcpResultsDir).map(File::readText),
                requireMysql = "mysql" in requiredEngines,
                requirePostgres = "postgres" in requiredEngines,
                resultsLocation = mcpResultsDir.toString(),
            )
        for (result in mcpResults) {
            logger.lifecycle(
                "${result.engine} integration discovery verified: " +
                    "${result.discovered} executed tests"
            )
        }
    }
}

internal fun coverageGroupForPackage(packageName: String): String {
    if (packageName == "com/safedb/mcp" || packageName.startsWith("com/safedb/mcp/")) {
        return "mcp"
    }
    // platform lives in :shared; schema map geometry lives in the desktop module.
    val desktopPrefixes =
        listOf("com/safedb/export", "com/safedb/viewmodel", "com/safedb/ui", "com/safedb/schema")
    val desktop =
        packageName == "com/safedb" ||
            desktopPrefixes.any { prefix ->
                packageName == prefix || packageName.startsWith("$prefix/")
            }
    return if (desktop) "desktop" else "shared"
}

internal data class CoverageRatchetResult(
    val group: String,
    val covered: Int,
    val missed: Int,
    val floor: Int,
) {
    val percent: Double
        get() = covered * 100.0 / (covered + missed)
}

internal fun verifyCoverageFloors(
    report: String,
    floors: Map<String, Int>,
): List<CoverageRatchetResult> {
    val totals = floors.keys.associateWith { intArrayOf(0, 0) }
    val document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(report.toByteArray(Charsets.UTF_8)))
    val packages = document.getElementsByTagName("package")
    for (index in 0 until packages.length) {
        val packageNode = packages.item(index)
        val packageName = packageNode.attributes.getNamedItem("name").nodeValue
        val group = coverageGroupForPackage(packageName)
        val counts =
            checkNotNull(totals[group]) {
                "Kover report contains $group lines, but no coverage floor is configured"
            }
        val children = packageNode.childNodes
        for (childIndex in 0 until children.length) {
            val child = children.item(childIndex)
            if (child.nodeName != "counter") continue
            if (child.attributes.getNamedItem("type").nodeValue != "LINE") continue
            counts[0] += child.attributes.getNamedItem("covered").nodeValue.toInt()
            counts[1] += child.attributes.getNamedItem("missed").nodeValue.toInt()
        }
    }

    return floors.toSortedMap().map { (group, floor) ->
        val counts = totals.getValue(group)
        val result = CoverageRatchetResult(group, counts[0], counts[1], floor)
        check(result.covered + result.missed > 0) { "Kover report contained no $group lines" }
        check(result.percent >= floor) {
            "$group line coverage %.2f%% is below the %d%% ratchet".format(result.percent, floor)
        }
        result
    }
}

internal data class IntegrationDiscoveryResult(
    val engine: String,
    val discovered: Int,
    val skipped: Int,
)

private data class IntegrationSuite(
    val engine: String,
    val classNameFragment: String,
    val minimum: Int,
)

private val integrationSuites =
    listOf(
        IntegrationSuite("mysql", "MySql", 6),
        IntegrationSuite("postgres", "Postgres", 3),
        IntegrationSuite("mssql", "Mssql", 2),
        IntegrationSuite("oracle", "Oracle", 2),
    )

internal fun verifyIntegrationTestDiscovery(
    reports: List<String>,
    requiredEngines: Set<String>,
    resultsLocation: String = "the integration test results directory",
): List<IntegrationDiscoveryResult> {
    if (requiredEngines.isEmpty()) return emptyList()
    check(reports.isNotEmpty()) { "Integration tests produced no JUnit XML in $resultsLocation" }

    val counts = integrationSuites.associate { it.engine to intArrayOf(0, 0) }
    for (report in reports) {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(report.toByteArray(Charsets.UTF_8)))
        val testcases = document.getElementsByTagName("testcase")
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index)
            val className = testcase.attributes.getNamedItem("classname")?.nodeValue ?: continue
            val suite =
                integrationSuites.firstOrNull { className.contains(it.classNameFragment) }
                    ?: continue
            counts.getValue(suite.engine)[0] += 1
            val children = testcase.childNodes
            for (childIndex in 0 until children.length) {
                if (children.item(childIndex).nodeName == "skipped") {
                    counts.getValue(suite.engine)[1] += 1
                }
            }
        }
    }

    return integrationSuites
        .filter { it.engine in requiredEngines }
        .map { suite ->
            val (discovered, skipped) = counts.getValue(suite.engine)
            check(discovered >= suite.minimum) {
                "${suite.engine} integration discovery found $discovered tests; expected at least ${suite.minimum}"
            }
            check(skipped == 0) {
                "${suite.engine} is required but $skipped of $discovered integration tests were skipped"
            }
            IntegrationDiscoveryResult(suite.engine, discovered, skipped)
        }
}

internal fun verifyMcpIntegrationTestDiscovery(
    reports: List<String>,
    requireMysql: Boolean,
    requirePostgres: Boolean = false,
    resultsLocation: String = "the MCP integration test results directory",
): List<IntegrationDiscoveryResult> {
    check(reports.isNotEmpty()) {
        "MCP integration tests produced no JUnit XML in $resultsLocation"
    }
    val suites = buildList {
        add(IntegrationSuite("mcp packaged stdio", "McpPackagedStdioIntegrationTest", 1))
        if (requireMysql) {
            add(IntegrationSuite("mcp mysql", "McpMySqlIntegrationTest", 1))
        }
        if (requirePostgres) {
            add(IntegrationSuite("mcp postgres", "McpPostgresIntegrationTest", 1))
        }
    }
    val counts = suites.associate { it.engine to intArrayOf(0, 0) }
    for (report in reports) {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(report.toByteArray(Charsets.UTF_8)))
        val testcases = document.getElementsByTagName("testcase")
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index)
            val className = testcase.attributes.getNamedItem("classname")?.nodeValue ?: continue
            val suite = suites.firstOrNull { className.contains(it.classNameFragment) } ?: continue
            counts.getValue(suite.engine)[0] += 1
            val children = testcase.childNodes
            for (childIndex in 0 until children.length) {
                if (children.item(childIndex).nodeName == "skipped") {
                    counts.getValue(suite.engine)[1] += 1
                }
            }
        }
    }

    return suites.map { suite ->
        val (discovered, skipped) = counts.getValue(suite.engine)
        check(discovered >= suite.minimum) {
            "${suite.engine} integration discovery found $discovered tests; " +
                "expected at least ${suite.minimum}"
        }
        check(skipped == 0) {
            "${suite.engine} is required but $skipped of $discovered integration tests were skipped"
        }
        IntegrationDiscoveryResult(suite.engine, discovered, skipped)
    }
}

private fun junitReports(resultsDir: File): List<File> =
    resultsDir
        .listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
        }
        .orEmpty()
        .toList()
