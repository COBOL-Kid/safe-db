package com.safedb.ui.util

import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.JoinSpec
import com.safedb.model.QuerySpec
import com.safedb.model.TableRef
import com.safedb.ui.sanitizeFileNameSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {
    @Test
    fun formatTimeCoversRelativeBucketsFormattedDateAndInvalidInput() {
        val baseSec = 1_700_000_000L
        val nowMs = baseSec * 1000L
        assertEquals("", formatTime("not-a-timestamp", nowMs))
        assertEquals("just now", formatTime((baseSec - 30).toString(), nowMs))
        assertEquals("5m ago", formatTime((baseSec - 5 * 60).toString(), nowMs))
        assertEquals("3h ago", formatTime((baseSec - 3 * 3600).toString(), nowMs))
        assertEquals("2d ago", formatTime((baseSec - 2 * 86_400).toString(), nowMs))
        val formattedDate = formatTime((baseSec - 10 * 86_400).toString(), nowMs)
        assertTrue(formattedDate.isNotBlank() && !formattedDate.endsWith("ago"))
    }

    @Test
    fun summarizeSpecPluralizesColumnsAndJoinsAndFormatsLimits() {
        val singleSpec =
            HistoryEntry(
                id = "h1",
                connectionId = "c1",
                connectionName = "DB",
                spec =
                    QuerySpec(
                        tables = listOf(TableRef("public", "orders", "t0")),
                        columns = listOf(ColumnSel("t0", "id")),
                        joins = emptyList(),
                        filters = FilterGroup.empty(),
                        limit = 50,
                    ),
                rowCount = 1,
                warnings = emptyList(),
                timestamp = "1",
            )
        assertEquals("orders · 1 col · limit 50", summarizeSpec(singleSpec))

        val multiSpec =
            singleSpec.copy(
                spec =
                    singleSpec.spec.copy(
                        tables =
                            listOf(
                                TableRef("public", "orders", "t0"),
                                TableRef("public", "users", "t1"),
                            ),
                        columns = listOf(ColumnSel("t0", "id"), ColumnSel("t0", "total")),
                        joins = listOf(JoinSpec("t0", "user_id", "t1", "id")),
                        uncapped = true,
                    ),
            )
        assertEquals("orders, users · 2 cols · 1 join · limit ALL", summarizeSpec(multiSpec))
    }

    @Test
    fun sanitizeFileNameSegmentSlugifiesAndFallsBackWhenEmpty() {
        assertEquals("orders-q1-2025", sanitizeFileNameSegment("Orders / Q1 (2025)!", "export"))
        assertEquals("export", sanitizeFileNameSegment("!!!---", "export"))
    }
}
