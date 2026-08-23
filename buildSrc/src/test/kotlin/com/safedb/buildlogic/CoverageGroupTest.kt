package com.safedb.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
