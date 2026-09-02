package com.safedb.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoverageGroupTest {
    @Test
    fun desktopRootAndUiPackagesStayOnTheDesktopSide() {
        assertEquals("desktop", coverageGroupForPackage("com/safedb"))
        assertEquals("desktop", coverageGroupForPackage("com/safedb/export"))
        assertEquals("desktop", coverageGroupForPackage("com/safedb/viewmodel"))
        assertEquals("desktop", coverageGroupForPackage("com/safedb/ui"))
        assertEquals("desktop", coverageGroupForPackage("com/safedb/schema"))
        assertEquals("desktop", coverageGroupForPackage("com/safedb/ui/theme"))
    }

    @Test
    fun sharedLibrariesAreNotCountedAsDesktop() {
        assertEquals("shared", coverageGroupForPackage("com/safedb/platform"))
        assertEquals("shared", coverageGroupForPackage("com/safedb/adapter"))
        assertEquals("shared", coverageGroupForPackage("com/safedb/query/sql"))
        assertEquals("shared", coverageGroupForPackage("com/safedb/launch"))
        assertEquals("shared", coverageGroupForPackage("com/safedb/canvas"))
    }

    @Test
    fun mcpRootAndDescendantsHaveTheirOwnCoverageGroup() {
        assertEquals("mcp", coverageGroupForPackage("com/safedb/mcp"))
        assertEquals("mcp", coverageGroupForPackage("com/safedb/mcp/protocol"))
        assertEquals("shared", coverageGroupForPackage("com/safedb/mcproxy"))
    }

    @Test
    fun coverageRatchetAccumulatesAllConfiguredGroups() {
        val results =
            verifyCoverageFloors(
                report =
                    coverageReport(
                        "com/safedb" to (9 to 1),
                        "com/safedb/mcp" to (7 to 3),
                        "com/safedb/query" to (8 to 2),
                        "com/safedb/store" to (9 to 1),
                    ),
                floors = mapOf("desktop" to 90, "shared" to 85, "mcp" to 70),
            )

        assertEquals(
            listOf(
                CoverageRatchetResult("desktop", 9, 1, 90),
                CoverageRatchetResult("mcp", 7, 3, 70),
                CoverageRatchetResult("shared", 17, 3, 85),
            ),
            results,
        )
    }

    @Test
    fun coverageRatchetEnforcesMcpFloorIndependently() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyCoverageFloors(
                    report =
                        coverageReport(
                            "com/safedb" to (9 to 1),
                            "com/safedb/mcp" to (7 to 3),
                            "com/safedb/query" to (9 to 1),
                        ),
                    floors = mapOf("desktop" to 90, "shared" to 85, "mcp" to 71),
                )
            }

        assertTrue(failure.message!!.contains("mcp line coverage 70.00% is below the 71% ratchet"))
    }

    private fun coverageReport(vararg packages: Pair<String, Pair<Int, Int>>): String =
        buildString {
            append("<report>")
            for ((packageName, counts) in packages) {
                append("<package name=\"$packageName\">")
                append(
                    "<counter type=\"LINE\" covered=\"${counts.first}\" " +
                        "missed=\"${counts.second}\"/>"
                )
                append("</package>")
            }
            append("</report>")
        }
}
