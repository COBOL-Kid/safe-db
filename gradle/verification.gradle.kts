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

tasks.named("check") {
    dependsOn(
        "compileRenderKotlin",
        verifyUnitTestDiscovery,
        testDockerDatabaseHarness,
        testAssertConveyorSite,
        "koverVerify",
    )
}

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
        requireMysql.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_MYSQL")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false),
        )
        requirePostgres.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_POSTGRES")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false),
        )
        requireMssql.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_MSSQL")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false),
        )
        requireOracle.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_ORACLE")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false),
        )
        requireDb2.set(
            providers
                .environmentVariable("SAFEDB_TEST_REQUIRE_DB2")
                .map { it.equals("true", ignoreCase = true) }
                .orElse(false),
        )
    }

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC and :mcp integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}
