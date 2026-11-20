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
    fun splitPackageSourceFilesAttributeSharedAndDesktopFiles() {
        assertEquals("shared", coverageGroupForSourceFile("com/safedb/export", "CsvWriter.kt"))
        assertEquals(
            "desktop",
            coverageGroupForSourceFile("com/safedb/export", "HtmlReportWriter.kt"),
        )
        assertEquals(
            "desktop",
            coverageGroupForSourceFile("com/safedb/export", "HtmlReportAssets.kt"),
        )
        assertEquals(
            "desktop",
            coverageGroupForSourceFile("com/safedb/export", "VisualizationPngExporter.kt"),
        )
        assertEquals(
            "shared",
            coverageGroupForSourceFile("com/safedb/schema", "SchemaRelationships.kt"),
        )
        assertEquals(
            "desktop",
            coverageGroupForSourceFile("com/safedb/schema", "SchemaMapGraph.kt"),
        )
        assertEquals(
            "desktop",
            coverageGroupForSourceFile("com/safedb/schema", "SchemaMapGeometry.kt"),
        )
        assertEquals("shared", coverageGroupForSourceFile("com/safedb/query", "HydrateQuery.kt"))
    }

    @Test
    fun coverageRatchetAttributesSplitPackagesBySourceFileWithoutDoubleCounting() {
        val report = buildString {
            append("<report>")
            append("<package name=\"com/safedb/export\">")
            append("<sourcefile name=\"HtmlReportWriter.kt\">")
            append("<counter type=\"LINE\" covered=\"18\" missed=\"2\"/>")
            append("</sourcefile>")
            append("<sourcefile name=\"CsvWriter.kt\">")
            append("<counter type=\"LINE\" covered=\"8\" missed=\"2\"/>")
            append("</sourcefile>")
            append("<counter type=\"LINE\" covered=\"26\" missed=\"4\"/>")
            append("</package>")
            append("<package name=\"com/safedb/schema\">")
            append("<sourcefile name=\"SchemaMapGraph.kt\">")
            append("<counter type=\"LINE\" covered=\"10\" missed=\"0\"/>")
            append("</sourcefile>")
            append("<sourcefile name=\"SchemaRelationships.kt\">")
            append("<counter type=\"LINE\" covered=\"20\" missed=\"0\"/>")
            append("</sourcefile>")
            append("<counter type=\"LINE\" covered=\"30\" missed=\"0\"/>")
            append("</package>")
            append("<package name=\"com/safedb/query\">")
            append("<counter type=\"LINE\" covered=\"9\" missed=\"1\"/>")
            append("</package>")
            append("</report>")
        }

        val results =
            verifyCoverageFloors(report = report, floors = mapOf("desktop" to 90, "shared" to 85))

        assertEquals(
            listOf(
                CoverageRatchetResult("desktop", 28, 2, 90),
                CoverageRatchetResult("shared", 37, 3, 85),
            ),
            results,
        )
    }

    @Test
    fun coverageRatchetFailsWhenSplitPackageHasNoSourceFiles() {
        val failure =
            assertFailsWith<IllegalStateException> {
                verifyCoverageFloors(
                    report =
                        coverageReport(
                            "com/safedb/export" to (9 to 1),
                            "com/safedb/query" to (9 to 1),
                        ),
                    floors = mapOf("desktop" to 90, "shared" to 85),
                )
            }

        assertTrue(failure.message!!.contains("com/safedb/export"))
        assertTrue(failure.message!!.contains("sourcefile"))
    }

    @Test
    fun coverageRatchetFailsWhenSplitPackageContainsUnknownSourceFile() {
        val report = buildString {
            append("<report>")
            append("<package name=\"com/safedb/export\">")
            append("<sourcefile name=\"NewExport.kt\">")
            append("<counter type=\"LINE\" covered=\"9\" missed=\"1\"/>")
            append("</sourcefile>")
            append("<counter type=\"LINE\" covered=\"9\" missed=\"1\"/>")
            append("</package>")
            append("<package name=\"com/safedb/query\">")
            append("<counter type=\"LINE\" covered=\"9\" missed=\"1\"/>")
            append("</package>")
            append("</report>")
        }

        val failure =
            assertFailsWith<IllegalStateException> {
                verifyCoverageFloors(
                    report = report,
                    floors = mapOf("desktop" to 90, "shared" to 85),
                )
            }

        assertTrue(failure.message!!.contains("NewExport.kt"))
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
                        "missed=\"${counts.second}\"/>",
                )
                append("</package>")
            }
            append("</report>")
        }
}
