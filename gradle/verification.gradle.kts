import com.safedb.buildlogic.VerifyCoverageRatchet
import com.safedb.buildlogic.VerifyIntegrationTestDiscovery
import com.safedb.buildlogic.VerifyUnitTestDiscovery

val verifyUnitTestDiscovery =
    tasks.register<VerifyUnitTestDiscovery>("verifyUnitTestDiscovery") {
        group = "verification"
        description =
            "Fails when compiled unit test classes are missing from JUnit XML or contain failures or skips."
        dependsOn(tasks.named("test"), ":shared:test", ":mcp:test")
        desktopResults.set(layout.buildDirectory.dir("test-results/test"))
        sharedResults.set(project(":shared").layout.buildDirectory.dir("test-results/test"))
        mcpResults.set(project(":mcp").layout.buildDirectory.dir("test-results/test"))
        desktopClasses.set(layout.buildDirectory.dir("classes/kotlin/test"))
        sharedClasses.set(project(":shared").layout.buildDirectory.dir("classes/kotlin/test"))
        mcpClasses.set(project(":mcp").layout.buildDirectory.dir("classes/kotlin/test"))
    }

val verifyCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyCoverageRatchet") {
        group = "verification"
        description = "Enforces checked-in line coverage floors for desktop and shared logic."
        dependsOn("koverXmlReport")
        reportFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
        // Shared floor is unit coverage from `check`. Live-JDBC adapter objects, platform keyring
        // delegates, and testFixtures are excluded so integrationTest-only plumbing does not skew it.
        coverageFloors.set(mapOf("desktop" to 95, "shared" to 92))
    }

val verifyMcpCoverageRatchet =
    tasks.register<VerifyCoverageRatchet>("verifyMcpCoverageRatchet") {
        group = "verification"
        description = "Enforces the checked-in MCP line coverage floor."
        dependsOn(":mcp:koverXmlReportUnit")
        reportFile.set(project(":mcp").layout.buildDirectory.file("reports/kover/reportUnit.xml"))
        coverageFloors.set(mapOf("mcp" to 94))
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

val testVerifyScript =
    tasks.register<Exec>("testVerifyScript") {
        group = "verification"
        description =
            "Tests unified verify.sh tier routing, flags, and fail-fast behavior without live containers."
        workingDir = projectDir
        commandLine("bash", "scripts/test_verify.sh")
        onlyIf { !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    }

tasks.named("check") {
    dependsOn(
        "compileRenderKotlin",
        verifyUnitTestDiscovery,
        testDockerDatabaseHarness,
        testAssertConveyorSite,
        testVerifyScript,
        testAgentGuiReset,
        "koverVerify",
    )
}

fun requireDialectFromEnv(dialect: String): Provider<Boolean> =
    providers
        .environmentVariable("SAFEDB_TEST_REQUIRE_$dialect")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

// Serialize live DB suites so they do not triple-hammer Docker containers (notably MSSQL/Oracle/Db2).
tasks.named("desktopIntegrationTest") { mustRunAfter(":shared:integrationTest") }

project(":mcp").tasks.matching { it.name == "integrationTest" }.configureEach {
    mustRunAfter(":shared:integrationTest", ":desktopIntegrationTest")
}

val verifyIntegrationTestDiscovery =
    tasks.register<VerifyIntegrationTestDiscovery>("verifyIntegrationTestDiscovery") {
        group = "verification"
        description =
            "Ensures required JDBC, MCP, and desktop integration suites executed instead of silently skipping."
        dependsOn(":shared:integrationTest", ":mcp:integrationTest", ":desktopIntegrationTest")
        sharedResultsDirectory.set(
            project(":shared").layout.buildDirectory.dir("test-results/integrationTest"),
        )
        desktopResultsDirectory.set(
            layout.buildDirectory.dir("test-results/desktopIntegrationTest"),
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
    description = "Runs :shared JDBC, :mcp, and desktop integration tests."
    dependsOn(verifyIntegrationTestDiscovery)
}
