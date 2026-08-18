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
                reports = listOf(report("MySql", 6)),
                requiredEngines = setOf("mysql"),
            )

        assertEquals(
            listOf(
                IntegrationDiscoveryResult("mysql", 6, 0),
            ),
            result,
        )
    }

    @Test
    fun rejectsMissingRequiredTests() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyIntegrationTestDiscovery(
                    reports = listOf(report("MySql", 5)),
                    requiredEngines = setOf("mysql"),
                )
            }

        assertTrue(failure.message!!.contains("found 5 tests; expected at least 6"))
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
    ): String =
        buildString {
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
