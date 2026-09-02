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
                reports = listOf(report("MySql", 6), report("Mssql", 2)),
                requiredEngines = setOf("mysql", "mssql"),
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mysql", 6, 0),
                IntegrationDiscoveryResult("mssql", 2, 0),
            ),
            result,
        )
    }

    @Test
    fun rejectsMissingRequiredTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = listOf(report("Oracle", 1)),
                    requiredEngines = setOf("oracle"),
                )
            }

        assertTrue(failure.message!!.contains("found 1 tests; expected at least 2"))
    }

    @Test
    fun rejectsSkippedRequiredTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = listOf(report("Postgres", 3, skipped = setOf(2))),
                    requiredEngines = setOf("postgres"),
                )
            }

        assertTrue(failure.message!!.contains("1 of 3 integration tests were skipped"))
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
    fun mysqlOnlyRequirementDoesNotDemandMcpPostgres() {
        val result =
            verifyMcpIntegrationTestDiscovery(
                reports =
                    listOf(
                        mcpReport("McpPackagedStdioIntegrationTest"),
                        mcpReport("McpMySqlIntegrationTest"),
                        mcpReport("McpPostgresIntegrationTest", skipped = true),
                    ),
                requireMysql = true,
                requirePostgres = false,
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 1, 0),
                IntegrationDiscoveryResult("mcp mysql", 1, 0),
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
                    ),
                requireMysql = false,
                requirePostgres = true,
            )
        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mcp packaged stdio", 1, 0),
                IntegrationDiscoveryResult("mcp postgres", 1, 0),
            ),
            result,
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

    private fun mcpReport(
        className: String,
        skipped: Boolean = false,
    ): String =
        "<testsuite><testcase classname=\"com.safedb.mcp.$className\" name=\"test\">" +
            (if (skipped) "<skipped/>" else "") +
            "</testcase></testsuite>"
}
