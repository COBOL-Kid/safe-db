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
import org.w3c.dom.Node

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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val desktopClasses: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedClasses: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mcpClasses: DirectoryProperty

    @TaskAction
    fun verify() {
        listOf(
                Triple("desktop", desktopClasses.get().asFile, desktopResults.get().asFile),
                Triple("shared", sharedClasses.get().asFile, sharedResults.get().asFile),
                Triple("mcp", mcpClasses.get().asFile, mcpResults.get().asFile),
            )
            .forEach { (label, classesDir, resultsDir) ->
                val result =
                    verifyUnitTestClassDiscovery(
                        label = label,
                        compiledTestClasses = collectCompiledTestClasses(classesDir),
                        reports = junitReports(resultsDir).map(File::readText),
                        resultsLocation = resultsDir.toString(),
                    )
                logger.lifecycle(
                    "$label discovery verified: ${result.executed} tests across ${result.discoveredClasses} classes",
                )
            }
    }
}

internal data class UnitDiscoveryResult(
    val label: String,
    val executed: Int,
    val discoveredClasses: Int,
)

internal fun collectCompiledTestClasses(classesDir: File): Set<String> {
    if (!classesDir.isDirectory) return emptySet()
    return classesDir
        .walkTopDown()
        .filter { file ->
            file.isFile && file.name.endsWith("Test.class") && '$' !in file.name
        }
        .map { file ->
            file.relativeTo(classesDir)
                .path
                .replace(File.separatorChar, '.')
                .removeSuffix(".class")
        }
        .toSet()
}

internal fun verifyUnitTestClassDiscovery(
    label: String,
    compiledTestClasses: Set<String>,
    reports: List<String>,
    resultsLocation: String = "the test results directory",
): UnitDiscoveryResult {
    check(compiledTestClasses.isNotEmpty()) { "$label compiled no *Test.class files" }
    check(reports.isNotEmpty()) { "$label produced no JUnit XML reports in $resultsLocation" }

    var executed = 0
    var failures = 0
    var skippedTests = 0
    val discoveredClasses = mutableSetOf<String>()

    val factory = DocumentBuilderFactory.newInstance()
    for (report in reports) {
        val document =
            factory.newDocumentBuilder().parse(ByteArrayInputStream(report.toByteArray(Charsets.UTF_8)))
        val testcases = document.getElementsByTagName("testcase")
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index)
            executed += 1
            val className =
                testcase.attributes.getNamedItem("classname")?.nodeValue?.substringBefore('$')
            if (!className.isNullOrBlank()) {
                discoveredClasses += className
            }
            val children = testcase.childNodes
            for (childIndex in 0 until children.length) {
                when (children.item(childIndex).nodeName) {
                    "failure",
                    "error" -> failures += 1
                    "skipped" -> skippedTests += 1
                }
            }
        }
    }

    check(failures == 0) { "$label JUnit XML contains $failures failures/errors" }
    check(skippedTests == 0) { "$label JUnit XML contains $skippedTests skipped tests" }
    val missingClasses = (compiledTestClasses - discoveredClasses).sorted()
    check(missingClasses.isEmpty()) {
        "$label compiled test classes did not report any executed testcases: " +
            missingClasses.joinToString(", ")
    }
    return UnitDiscoveryResult(label, executed, discoveredClasses.size)
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
                    .format(result.percent, result.floor),
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

    @get:Input abstract val requireDb2: Property<Boolean>

    @TaskAction
    fun verify() {
        val requiredEngines = buildSet {
            if (requireMysql.get()) add("mysql")
            if (requirePostgres.get()) add("postgres")
            if (requireMssql.get()) add("mssql")
            if (requireOracle.get()) add("oracle")
            if (requireDb2.get()) add("db2")
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
                        "${result.discovered} executed tests",
                )
            }
        }

        val mcpResultsDir = mcpResultsDirectory.get().asFile
        val mcpResults =
            verifyMcpIntegrationTestDiscovery(
                junitReports(mcpResultsDir).map(File::readText),
                requireMysql = "mysql" in requiredEngines,
                requirePostgres = "postgres" in requiredEngines,
                requireMssql = "mssql" in requiredEngines,
                requireOracle = "oracle" in requiredEngines,
                requireDb2 = "db2" in requiredEngines,
                resultsLocation = mcpResultsDir.toString(),
            )
        for (result in mcpResults) {
            logger.lifecycle(
                "${result.engine} integration discovery verified: " +
                    "${result.discovered} executed tests",
            )
        }
    }
}

// Filename allowlist for split packages. Unknown names fail the ratchet;
// never attribute these packages from the package-level LINE counter.
private val sharedSourceFilesBySplitPackage =
    mapOf(
        "com/safedb/export" to setOf("CsvWriter.kt"),
        "com/safedb/schema" to setOf("SchemaRelationships.kt"),
    )

private val desktopSourceFilesBySplitPackage =
    mapOf(
        "com/safedb/export" to
            setOf("HtmlReportAssets.kt", "HtmlReportWriter.kt", "VisualizationPngExporter.kt"),
        "com/safedb/schema" to setOf("SchemaMapGeometry.kt", "SchemaMapGraph.kt"),
    )

internal fun coverageGroupForPackage(packageName: String): String {
    if (packageName == "com/safedb/mcp" || packageName.startsWith("com/safedb/mcp/")) {
        return "mcp"
    }
    // Platform lives in :shared; schema map geometry lives in the desktop module.
    val desktopPrefixes =
        listOf("com/safedb/export", "com/safedb/viewmodel", "com/safedb/ui", "com/safedb/schema")
    val desktop =
        packageName == "com/safedb" ||
            desktopPrefixes.any { prefix ->
                packageName == prefix || packageName.startsWith("$prefix/")
            }
    return if (desktop) "desktop" else "shared"
}

internal fun coverageGroupForSourceFile(packageName: String, sourceFileName: String): String {
    val sharedFiles = sharedSourceFilesBySplitPackage[packageName]
    val desktopFiles = desktopSourceFilesBySplitPackage[packageName]
    if (sharedFiles == null && desktopFiles == null) {
        return coverageGroupForPackage(packageName)
    }
    if (sourceFileName in sharedFiles.orEmpty()) {
        return "shared"
    }
    if (sourceFileName in desktopFiles.orEmpty()) {
        return "desktop"
    }
    error(
        "Unknown split-package sourcefile $sourceFileName in $packageName " +
            "fails the coverage ratchet",
    )
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
        if (
            packageName in sharedSourceFilesBySplitPackage ||
                packageName in desktopSourceFilesBySplitPackage
        ) {
            check(accumulateSplitPackageSourceFiles(packageNode, packageName, totals)) {
                "Split package $packageName has no <sourcefile> nodes; " +
                    "refusing to count its package-level LINE counter"
            }
            continue
        }
        val group = coverageGroupForPackage(packageName)
        val counts =
            checkNotNull(totals[group]) {
                "Kover report contains $group lines, but no coverage floor is configured"
            }
        addDirectLineCounters(packageNode, counts)
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

private fun accumulateSplitPackageSourceFiles(
    packageNode: Node,
    packageName: String,
    totals: Map<String, IntArray>,
): Boolean {
    var sawSourceFile = false
    val children = packageNode.childNodes
    for (childIndex in 0 until children.length) {
        val child = children.item(childIndex)
        if (child.nodeName != "sourcefile") continue
        sawSourceFile = true
        val sourceFileName =
            child.attributes?.getNamedItem("name")?.nodeValue
                ?: error("Split package $packageName has a <sourcefile> without a name")
        val group = coverageGroupForSourceFile(packageName, sourceFileName)
        val counts =
            checkNotNull(totals[group]) {
                "Kover report contains $group lines, but no coverage floor is configured"
            }
        addDirectLineCounters(child, counts)
    }
    return sawSourceFile
}

private fun addDirectLineCounters(node: Node, counts: IntArray): Boolean {
    var added = false
    val children = node.childNodes
    for (childIndex in 0 until children.length) {
        val child = children.item(childIndex)
        if (child.nodeName != "counter") continue
        if (child.attributes.getNamedItem("type").nodeValue != "LINE") continue
        counts[0] += child.attributes.getNamedItem("covered").nodeValue.toInt()
        counts[1] += child.attributes.getNamedItem("missed").nodeValue.toInt()
        added = true
    }
    return added
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
    val methodNameFragment: String? = null,
)

private val integrationSuites =
    listOf(
        IntegrationSuite("mysql", "MySql", 6),
        IntegrationSuite("postgres", "Postgres", 3),
        IntegrationSuite("mssql", "Mssql", 2),
        IntegrationSuite("oracle", "Oracle", 2),
        IntegrationSuite("db2", "Db2", 3),
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
    requireMssql: Boolean = false,
    requireOracle: Boolean = false,
    requireDb2: Boolean = false,
    resultsLocation: String = "the MCP integration test results directory",
): List<IntegrationDiscoveryResult> {
    check(reports.isNotEmpty()) {
        "MCP integration tests produced no JUnit XML in $resultsLocation"
    }
    val suites = buildList {
        add(IntegrationSuite("mcp packaged stdio", "McpPackagedStdioIntegrationTest", 1))
        if (requireMysql) {
            add(IntegrationSuite("mcp mysql", "McpMySqlIntegrationTest", 1))
            add(
                IntegrationSuite(
                    "mcp packaged mysql",
                    "McpPackagedDialectsIntegrationTest",
                    1,
                    methodNameFragment = "Mysql",
                ),
            )
        }
        if (requirePostgres) {
            add(IntegrationSuite("mcp postgres", "McpPostgresIntegrationTest", 1))
            add(
                IntegrationSuite(
                    "mcp packaged postgres",
                    "McpPackagedDialectsIntegrationTest",
                    1,
                    methodNameFragment = "Postgres",
                ),
            )
        }
        if (requireMssql) {
            add(
                IntegrationSuite(
                    "mcp packaged mssql",
                    "McpPackagedDialectsIntegrationTest",
                    1,
                    methodNameFragment = "Mssql",
                ),
            )
        }
        if (requireOracle) {
            add(
                IntegrationSuite(
                    "mcp packaged oracle",
                    "McpPackagedDialectsIntegrationTest",
                    1,
                    methodNameFragment = "Oracle",
                ),
            )
        }
        if (requireDb2) {
            add(
                IntegrationSuite(
                    "mcp packaged db2",
                    "McpPackagedDialectsIntegrationTest",
                    1,
                    methodNameFragment = "Db2",
                ),
            )
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
            val methodName = testcase.attributes.getNamedItem("name")?.nodeValue.orEmpty()
            val suite =
                suites.firstOrNull { candidate ->
                    className.contains(candidate.classNameFragment) &&
                        (candidate.methodNameFragment == null ||
                            methodName.contains(candidate.methodNameFragment))
                } ?: continue
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
