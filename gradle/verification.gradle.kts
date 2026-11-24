import com.safedb.buildlogic.VerifyCoverageRatchet
import com.safedb.buildlogic.VerifyIntegrationTestDiscovery
import com.safedb.buildlogic.VerifyUnitTestDiscovery

val verifyUnitTestDiscovery =
    tasks.register<VerifyUnitTestDiscovery>("verifyUnitTestDiscovery") {
        group = "verification"
        description =
            "Fails when JVM unit tests are missing from JUnit XML or contain failures or skips."
        dependsOn(tasks.named("test"), ":shared:test", ":mcp:test")
        desktopResults.set(layout.buildDirectory.dir("test-results/test"))
        sharedResults.set(project(":shared").layout.buildDirectory.dir("test-results/test"))
        mcpResults.set(project(":mcp").layout.buildDirectory.dir("test-results/test"))
        minimumDesktopTests.set(406)
        minimumSharedTests.set(758)
        minimumMcpTests.set(251)
    }

val verifyCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyCoverageRatchet") {
        group = "verification"
        description = "Enforces checked-in line coverage floors for desktop and shared logic."
        dependsOn("koverXmlReport")
        reportFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
        // Shared floor is unit coverage from `check`. JDBC adapter bodies are covered by
        // integrationTest, which kover ignores.
        coverageFloors.set(mapOf("desktop" to 92, "shared" to 89))
    }

val verifyMcpCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyMcpCoverageRatchet") {
        group = "verification"
        description = "Enforces the checked-in MCP line coverage floor."
        dependsOn(":mcp:koverXmlReportUnit")
        reportFile.set(project(":mcp").layout.buildDirectory.file("reports/kover/reportUnit.xml"))
        coverageFloors.set(mapOf("mcp" to 93))
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

val testAssertConveyorSite =
    tasks.register<Exec>("testAssertConveyorSite") {
        group = "verification"
        description = "Tests Conveyor site assertions against fixture files."
        workingDir = projectDir
        commandLine("bash", "scripts/test_assert_conveyor_site.sh")
        onlyIf { !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    }

val testAgentGuiReset =
    tasks.register<Exec>("testAgentGuiReset") {
        group = "verification"
        description =
            "Tests agent_gui.sh reset boundaries, PID guards, window matching, and CLI checks."
        workingDir = projectDir
        commandLine("bash", "scripts/test_agent_gui_reset.sh")
        onlyIf { System.getProperty("os.name").startsWith("Linux", ignoreCase = true) }
    }

tasks.named("check") {
    dependsOn(
        "compileRenderKotlin",
        verifyUnitTestDiscovery,
        testDockerDatabaseHarness,
        testAssertConveyorSite,
        testAgentGuiReset,
        "koverVerify",
    )
}

fun requireDialectFromEnv(dialect: String): Provider<Boolean> =
    providers
        .environmentVariable("SAFEDB_TEST_REQUIRE_$dialect")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

val verifyIntegrationTestDiscovery =
    tasks.register<VerifyIntegrationTestDiscovery>("verifyIntegrationTestDiscovery") {
        group = "verification"
        description =
            "Ensures required JDBC and MCP integration suites executed instead of silently skipping."
        dependsOn(":shared:integrationTest", ":mcp:integrationTest")
        sharedResultsDirectory.set(
            project(":shared").layout.buildDirectory.dir("test-results/integrationTest"),
        )
        mcpResultsDirectory.set(
            project(":mcp").layout.buildDirectory.dir("test-results/integrationTest"),
        )
        requireMysql.set(requireDialectFromEnv("MYSQL"))
        requirePostgres.set(requireDialectFromEnv("POSTGRES"))
        requireMssql.set(requireDialectFromEnv("MSSQL"))
        requireOracle.set(requireDialectFromEnv("ORACLE"))
        requireDb2.set(requireDialectFromEnv("DB2"))
    }

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC and :mcp integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}
