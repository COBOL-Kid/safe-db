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
}
