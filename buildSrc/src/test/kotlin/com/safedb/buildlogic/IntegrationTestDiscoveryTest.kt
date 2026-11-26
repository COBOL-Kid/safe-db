package com.safedb.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrationTestDiscoveryTest {
    @Test
    fun acceptsSufficientUnskippedTests() {
        val result =
            verifyIntegrationTestDiscovery(
                reports =
                    listOf(
                        report("MySql", 9),
                        nestedSreReport("MySql", 4),
                        report("Mssql", 7),
                        nestedSreReport("Mssql", 4),
                    ),
                requiredEngines = setOf("mysql", "mssql"),
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mysql", 13, 0),
                IntegrationDiscoveryResult("mssql", 11, 0),
            ),
            result,
        )
    }

    @Test
    fun rejectsMissingRequiredTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = listOf(report("Oracle", 10)),
                    requiredEngines = setOf("oracle"),
                )
            }

        assertTrue(failure.message!!.contains("found 10 tests; expected at least 11"))
    }

    @Test
    fun rejectsSkippedRequiredTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = listOf(report("Postgres", 11, skipped = setOf(2))),
                    requiredEngines = setOf("postgres"),
                )
            }

        assertTrue(failure.message!!.contains("1 of 11 integration tests were skipped"))
    }

    @Test
    fun rejectsMissingReports() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = emptyList(),
                    requiredEngines = setOf("mysql"),
                    resultsLocation = "/tmp/results",
                )
            }

        assertEquals("Integration tests produced no JUnit XML in /tmp/results", failure.message)
    }

    @Test
    fun acceptsPackagedMcpSmokeWhenMysqlIsNotRequired() {
        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport("McpMySqlIntegrationTest", skipped = true),
                    ),
                requireMysql = false,
            )

        assertEquals(
            listOf(IntegrationDiscoveryResult("mcp packaged stdio", 1, 0)),
            result,
        )
    }

    @Test
    fun requiresMcpMysqlSeparatelyFromSharedMysqlTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports =
                        listOf(
                            report("MySql", 6),
                            mcpReport("McpPackagedStdioIntegrationTest"),
                        ),
                    requireMysql = true,
                )
            }

        assertTrue(failure.message!!.contains("mcp mysql integration discovery found 0 tests"))
    }

    @Test
    fun rejectsSkippedRequiredMcpMysqlTest() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports =
                        listOf(
                            mcpReport("McpPackagedStdioIntegrationTest"),
                            mcpReport("McpMySqlIntegrationTest", skipped = true),
                        ),
                    requireMysql = true,
                )
            }

        assertTrue(failure.message!!.contains("mcp mysql is required but 1 of 1"))
    }

    @Test
    fun rejectsMissingPackagedMcpSmoke() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports = listOf(mcpReport("McpMySqlIntegrationTest", skipped = true)),
                    requireMysql = false,
                )
            }

        assertTrue(failure.message!!.contains("mcp packaged stdio integration discovery found 0"))
    }

    @Test
    fun requiresMcpPostgresSeparatelyFromSharedPostgresTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports =
                        listOf(
                            report("Postgres", 3),
                            mcpReport("McpPackagedStdioIntegrationTest"),
                        ),
                    requireMysql = false,
                    requirePostgres = true,
                )
            }

        assertTrue(failure.message!!.contains("mcp postgres integration discovery found 0 tests"))
    }

    @Test
    fun rejectsSkippedRequiredMcpPostgresTest() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports =
                        listOf(
                            mcpReport("McpPackagedStdioIntegrationTest"),
                            mcpReport("McpPostgresIntegrationTest", skipped = true),
                        ),
                    requireMysql = false,
                    requirePostgres = true,
                )
            }

        assertTrue(failure.message!!.contains("mcp postgres is required but 1 of 1"))
    }

    @Test
    fun ignoresSkippedMcpPostgresWhenPostgresIsNotRequired() {
        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport("McpPostgresIntegrationTest", skipped = true),
                    ),
                requireMysql = false,
                requirePostgres = false,
            )

        assertEquals(
            listOf(IntegrationDiscoveryResult("mcp packaged stdio", 1, 0)),
            result,
        )
    }

    @Test
    fun unitTestDiscoveryVerifiesEveryCompiledClassRanWithoutSkipsOrFailures() {
        val result =
            verifyUnitTestClassDiscovery(
                label = "mcp",
                compiledTestClasses = setOf("com.safedb.mcp.OneTest", "com.safedb.mcp.TwoTest"),
                reports =
                    listOf(
                        "<testsuite><testcase classname=\"com.safedb.mcp.OneTest\" name=\"a\"/></testsuite>",
                        "<testsuite><testcase classname=\"com.safedb.mcp.TwoTest\$Nested\" name=\"b\"/></testsuite>",
                    ),
            )

        assertEquals(UnitDiscoveryResult("mcp", executed = 2, discoveredClasses = 2), result)
    }

    @Test
    fun unitTestDiscoveryRejectsMissingTestClassFailureOrSkip() {
        val missing =
            assertFailsWith<IllegalStateException> {
                verifyUnitTestClassDiscovery(
                    label = "desktop",
                    compiledTestClasses = setOf("com.safedb.ATest", "com.safedb.BTest"),
                    reports =
                        listOf(
                            "<testsuite><testcase classname=\"com.safedb.ATest\" name=\"a\"/></testsuite>",
                        ),
                )
            }
        assertTrue(missing.message!!.contains("com.safedb.BTest"))

        val skipped =
            assertFailsWith<IllegalStateException> {
                verifyUnitTestClassDiscovery(
                    label = "shared",
                    compiledTestClasses = setOf("com.safedb.ATest"),
                    reports =
                        listOf(
                            "<testsuite><testcase classname=\"com.safedb.ATest\" name=\"a\"><skipped/></testcase></testsuite>",
                        ),
                )
            }
        assertTrue(skipped.message!!.contains("1 skipped tests"))

        val failed =
            assertFailsWith<IllegalStateException> {
                verifyUnitTestClassDiscovery(
                    label = "shared",
                    compiledTestClasses = setOf("com.safedb.ATest"),
                    reports =
                        listOf(
                            "<testsuite><testcase classname=\"com.safedb.ATest\" name=\"a\"><failure/></testcase></testsuite>",
                        ),
                )
            }
        assertTrue(failed.message!!.contains("1 failures/errors"))
    }

    @Test
    fun mysqlOnlyRequirementDoesNotDemandMcpPostgres() {
        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport("McpMySqlIntegrationTest"),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesMysql()",
                        ),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesPostgres()",
                            skipped = true,
                        ),
                        mcpReport("McpPostgresIntegrationTest", skipped = true),
                    ),
                requireMysql = true,
                requirePostgres = false,
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 1, 0),
                IntegrationDiscoveryResult("mcp mysql", 1, 0),
                IntegrationDiscoveryResult("mcp packaged mysql", 1, 0),
            ),
            result,
        )
    }

    @Test
    fun postgresRequiredWithoutMysqlFailsUntilPostgresClassRunsUnskipped() {
        val missing =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports = listOf(mcpReport("McpPackagedStdioIntegrationTest")),
                    requireMysql = false,
                    requirePostgres = true,
                )
            }
        assertTrue(missing.message!!.contains("mcp postgres integration discovery found 0 tests"))

        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport("McpPostgresIntegrationTest"),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesPostgres()",
                        ),
                    ),
                requireMysql = false,
                requirePostgres = true,
            )
        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 1, 0),
                IntegrationDiscoveryResult("mcp postgres", 1, 0),
                IntegrationDiscoveryResult("mcp packaged postgres", 1, 0),
            ),
            result,
        )
    }

    @Test
    fun packagedDialectsTrackMssqlOracleAndDb2WhenRequired() {
        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesMssql()",
                        ),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesOracle()",
                        ),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesDb2()",
                        ),
                    ),
                requireMysql = false,
                requireMssql = true,
                requireOracle = true,
                requireDb2 = true,
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 1, 0),
                IntegrationDiscoveryResult("mcp packaged mssql", 1, 0),
                IntegrationDiscoveryResult("mcp packaged oracle", 1, 0),
                IntegrationDiscoveryResult("mcp packaged db2", 1, 0),
            ),
            result,
        )
    }

    @Test
    fun mcpIntegrationDiscoveryEnforcesUserJourneyWhenEnabled() {
        val missing =
            assertFailsWith<IllegalStateException> {
                verifyMcpIntegrationTestDiscovery(
                    reports =
                        listOf(
                            mcpReport("McpPackagedStdioIntegrationTest"),
                            mcpReport("McpMySqlIntegrationTest"),
                            mcpReport(
                                "McpPackagedDialectsIntegrationTest",
                                name = "packagedJarQueriesMysql()",
                            ),
                        ),
                    requireMysql = true,
                    includeUserJourney = true,
                )
            }
        assertTrue(
            missing.message!!.contains(
                "mcp packaged user journey mysql integration discovery found 0 tests",
            ),
        )
    }

    @Test
    fun desktopDiscoveryIgnoresEmptyRequiredEngines() {
        val result =
            verifyDesktopIntegrationTestDiscovery(
                reports = emptyList(),
                requiredEngines = emptySet(),
            )
        assertEquals(emptyList(), result)
    }

    @Test
    fun desktopDiscoveryAcceptsMysqlOnlyAndFiveEngineRuns() {
        val mysqlOnly =
            verifyDesktopIntegrationTestDiscovery(
                reports =
                    listOf(
                        desktopReport("mysqlFullUserJourneyAndRestartPersistence()"),
                        desktopReport(
                            "postgresFullUserJourneyAndRestartPersistence()",
                            skipped = true,
                        ),
                        desktopReport("mssqlLiveConnectSchemaAndQuery()", skipped = true),
                        desktopReport("oracleLiveConnectSchemaAndQuery()", skipped = true),
                        desktopReport("db2LiveConnectSchemaAndQuery()", skipped = true),
                    ),
                requiredEngines = setOf("mysql"),
            )
        assertEquals(
            listOf(IntegrationDiscoveryResult("desktop mysql", 1, 0)),
            mysqlOnly,
        )

        val allFive =
            verifyDesktopIntegrationTestDiscovery(
                reports =
                    listOf(
                        desktopReport("mysqlFullUserJourneyAndRestartPersistence()"),
                        desktopReport("postgresFullUserJourneyAndRestartPersistence()"),
                        desktopReport("mssqlLiveConnectSchemaAndQuery()"),
                        desktopReport("oracleLiveConnectSchemaAndQuery()"),
                        desktopReport("db2LiveConnectSchemaAndQuery()"),
                    ),
                requiredEngines = setOf("mysql", "postgres", "mssql", "oracle", "db2"),
            )
        assertEquals(
            listOf(
                IntegrationDiscoveryResult("desktop mysql", 1, 0),
                IntegrationDiscoveryResult("desktop postgres", 1, 0),
                IntegrationDiscoveryResult("desktop mssql", 1, 0),
                IntegrationDiscoveryResult("desktop oracle", 1, 0),
                IntegrationDiscoveryResult("desktop db2", 1, 0),
            ),
            allFive,
        )
    }

    @Test
    fun desktopDiscoveryRejectsEmptyMissingOrSkippedRequiredDialect() {
        val empty =
            assertFailsWith<IllegalStateException> {
                verifyDesktopIntegrationTestDiscovery(
                    reports = emptyList(),
                    requiredEngines = setOf("mysql"),
                    resultsLocation = "/tmp/desktop-results",
                )
            }
        assertEquals(
            "Desktop integration tests produced no JUnit XML in /tmp/desktop-results",
            empty.message,
        )

        val missing =
            assertFailsWith<IllegalStateException> {
                verifyDesktopIntegrationTestDiscovery(
                    reports = listOf(desktopReport("mysqlFullUserJourneyAndRestartPersistence()")),
                    requiredEngines = setOf("mysql", "db2"),
                )
            }
        assertTrue(missing.message!!.contains("desktop db2 integration discovery found 0 tests"))

        val skipped =
            assertFailsWith<IllegalStateException> {
                verifyDesktopIntegrationTestDiscovery(
                    reports =
                        listOf(
                            desktopReport(
                                "postgresFullUserJourneyAndRestartPersistence()",
                                skipped = true,
                            ),
                        ),
                    requiredEngines = setOf("postgres"),
                )
            }
        assertTrue(skipped.message!!.contains("desktop postgres is required but 1 of 1"))
    }

    @Test
    fun npmPackagedDiscoveryRequiresFourStdioTestsEvenOffline() {
        val offline =
            verifyMcpNpmPackagedDiscovery(
                reports =
                    listOf(
                        mcpReportCount("McpPackagedStdioIntegrationTest", 4),
                        mcpReport("McpMySqlIntegrationTest", skipped = true),
                        mcpReport(
                            "McpPackagedUserJourneyIntegrationTest",
                            name = "packagedUserJourneyMysql()",
                            skipped = true,
                        ),
                    ),
                requireMysql = false,
            )
        assertEquals(
            listOf(IntegrationDiscoveryResult("mcp packaged stdio", 4, 0)),
            offline,
        )

        val tooFewStdio =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports = listOf(mcpReportCount("McpPackagedStdioIntegrationTest", 3)),
                    requireMysql = false,
                )
            }
        assertTrue(
            tooFewStdio.message!!.contains(
                "mcp packaged stdio integration discovery found 3 tests; expected at least 4",
            ),
        )

        val skippedStdio =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports =
                        listOf(
                            mcpReportCount(
                                "McpPackagedStdioIntegrationTest",
                                4,
                                skipped = setOf(1),
                            ),
                        ),
                    requireMysql = false,
                )
            }
        assertTrue(
            skippedStdio.message!!.contains("mcp packaged stdio is required but 1 of 4"),
        )
    }

    @Test
    fun npmPackagedDiscoverySupportsMysqlOnlyCiAndAllFiveEngines() {
        val mysqlOnly =
            verifyMcpNpmPackagedDiscovery(
                reports =
                    listOf(
                        mcpReportCount("McpPackagedStdioIntegrationTest", 4),
                        mcpReportCount("McpMySqlIntegrationTest", 2),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesMysql()",
                        ),
                        mcpReport(
                            "McpPackagedUserJourneyIntegrationTest",
                            name = "packagedUserJourneyMysql()",
                        ),
                        mcpReportCount("McpPostgresIntegrationTest", 2, skipped = setOf(0, 1)),
                        mcpReport(
                            "McpPackagedDialectsIntegrationTest",
                            name = "packagedJarQueriesPostgres()",
                            skipped = true,
                        ),
                        mcpReport(
                            "McpPackagedUserJourneyIntegrationTest",
                            name = "packagedUserJourneyDb2()",
                            skipped = true,
                        ),
                    ),
                requireMysql = true,
            )
        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 4, 0),
                IntegrationDiscoveryResult("mcp mysql", 2, 0),
                IntegrationDiscoveryResult("mcp packaged mysql", 1, 0),
                IntegrationDiscoveryResult("mcp packaged user journey mysql", 1, 0),
            ),
            mysqlOnly,
        )

        val allFive =
            verifyMcpNpmPackagedDiscovery(
                reports = allNpmPackagedReports(),
                requireMysql = true,
                requirePostgres = true,
                requireMssql = true,
                requireOracle = true,
                requireDb2 = true,
            )
        assertEquals(18, allFive.sumOf { it.discovered })
        assertTrue(allFive.all { it.skipped == 0 })
    }

    @Test
    fun npmPackagedDiscoveryRejectsMissingSecondTestSkippedDialectAndEmptyReports() {
        val empty =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports = emptyList(),
                    requireMysql = false,
                    resultsLocation = "/tmp/npm-results",
                )
            }
        assertEquals(
            "Packaged MCP tests produced no JUnit XML in /tmp/npm-results",
            empty.message,
        )

        val missingSecondMysql =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports =
                        listOf(
                            mcpReportCount("McpPackagedStdioIntegrationTest", 4),
                            mcpReportCount("McpMySqlIntegrationTest", 1),
                            mcpReport(
                                "McpPackagedDialectsIntegrationTest",
                                name = "packagedJarQueriesMysql()",
                            ),
                            mcpReport(
                                "McpPackagedUserJourneyIntegrationTest",
                                name = "packagedUserJourneyMysql()",
                            ),
                        ),
                    requireMysql = true,
                )
            }
        assertTrue(
            missingSecondMysql.message!!.contains(
                "mcp mysql integration discovery found 1 tests; expected at least 2",
            ),
        )

        val skippedJourney =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports =
                        listOf(
                            mcpReportCount("McpPackagedStdioIntegrationTest", 4),
                            mcpReport(
                                "McpPackagedDialectsIntegrationTest",
                                name = "packagedJarQueriesOracle()",
                            ),
                            mcpReport(
                                "McpPackagedUserJourneyIntegrationTest",
                                name = "packagedUserJourneyOracle()",
                                skipped = true,
                            ),
                        ),
                    requireMysql = false,
                    requireOracle = true,
                )
            }
        assertTrue(
            skippedJourney.message!!.contains(
                "mcp packaged user journey oracle is required but 1 of 1",
            ),
        )

        val skippedDialect =
            assertFailsWith<IllegalStateException> {
                verifyMcpNpmPackagedDiscovery(
                    reports =
                        listOf(
                            mcpReportCount("McpPackagedStdioIntegrationTest", 4),
                            mcpReport(
                                "McpPackagedDialectsIntegrationTest",
                                name = "packagedJarQueriesMssql()",
                                skipped = true,
                            ),
                            mcpReport(
                                "McpPackagedUserJourneyIntegrationTest",
                                name = "packagedUserJourneyMssql()",
                            ),
                        ),
                    requireMysql = false,
                    requireMssql = true,
                )
            }
        assertTrue(
            skippedDialect.message!!.contains("mcp packaged mssql is required but 1 of 1"),
        )
    }

    private fun report(
        classNameFragment: String,
        count: Int,
        skipped: Set<Int> = emptySet(),
    ): String = buildString {
        append("<testsuite>")
        repeat(count) { index ->
            append("<testcase classname=\"com.safedb.integration.")
            append(classNameFragment)
            append("AdapterIntegrationTest\" name=\"test")
            append(index)
            append("\">")
            if (index in skipped) append("<skipped/>")
            append("</testcase>")
        }
        append("</testsuite>")
    }

    private fun nestedSreReport(engine: String, count: Int): String = buildString {
        append("<testsuite>")
        repeat(count) { index ->
            append("<testcase classname=\"com.safedb.integration.SreResilienceIntegrationTest\$")
            append(engine)
            append("\" name=\"sreTest")
            append(index)
            append("\"/>")
        }
        append("</testsuite>")
    }

    private fun desktopReport(name: String, skipped: Boolean = false): String =
        "<testsuite><testcase classname=\"com.safedb.DesktopLiveUserJourneyIntegrationTest\" name=\"$name\">" +
            (if (skipped) "<skipped/>" else "") +
            "</testcase></testsuite>"

    private fun mcpReport(
        className: String,
        skipped: Boolean = false,
        name: String = "test",
    ): String =
        "<testsuite><testcase classname=\"com.safedb.mcp.$className\" name=\"$name\">" +
            (if (skipped) "<skipped/>" else "") +
            "</testcase></testsuite>"

    private fun mcpReportCount(
        className: String,
        count: Int,
        skipped: Set<Int> = emptySet(),
    ): String = buildString {
        append("<testsuite>")
        repeat(count) { index ->
            append("<testcase classname=\"com.safedb.mcp.")
            append(className)
            append("\" name=\"test")
            append(index)
            append("\">")
            if (index in skipped) append("<skipped/>")
            append("</testcase>")
        }
        append("</testsuite>")
    }

    private fun allNpmPackagedReports(): List<String> =
        listOf(
            mcpReportCount("McpPackagedStdioIntegrationTest", 4),
            mcpReportCount("McpMySqlIntegrationTest", 2),
            mcpReportCount("McpPostgresIntegrationTest", 2),
            mcpReport("McpPackagedDialectsIntegrationTest", name = "packagedJarQueriesMysql()"),
            mcpReport("McpPackagedDialectsIntegrationTest", name = "packagedJarQueriesPostgres()"),
            mcpReport("McpPackagedDialectsIntegrationTest", name = "packagedJarQueriesMssql()"),
            mcpReport("McpPackagedDialectsIntegrationTest", name = "packagedJarQueriesOracle()"),
            mcpReport("McpPackagedDialectsIntegrationTest", name = "packagedJarQueriesDb2()"),
            mcpReport(
                "McpPackagedUserJourneyIntegrationTest",
                name = "packagedUserJourneyMysql()",
            ),
            mcpReport(
                "McpPackagedUserJourneyIntegrationTest",
                name = "packagedUserJourneyPostgres()",
            ),
            mcpReport(
                "McpPackagedUserJourneyIntegrationTest",
                name = "packagedUserJourneyMssql()",
            ),
            mcpReport(
                "McpPackagedUserJourneyIntegrationTest",
                name = "packagedUserJourneyOracle()",
            ),
            mcpReport("McpPackagedUserJourneyIntegrationTest", name = "packagedUserJourneyDb2()"),
        )
}
