package com.safedb.ui

import com.safedb.explore.LabelFilterOp
import com.safedb.explore.PivotFilter
import com.safedb.explore.ValueFilterOp
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterDraftTest {
    @Test
    fun membersFilterRoundTrips() {
        val pinned = PivotFilter.Members("f1", "status", "Status", setOf("a", "b"), pinned = true)
        assertEquals(FilterEditorKind.Members, FilterDraft.from(pinned).kind)
        assertEquals(pinned, FilterDraft.from(pinned).toFilter())

        val unpinned = pinned.copy(includedKeys = emptySet(), pinned = false)
        assertEquals(unpinned, FilterDraft.from(unpinned).toFilter())
    }

    @Test
    fun labelFilterRoundTripsEveryOperator() {
        LabelFilterOp.entries.forEach { op ->
            val filter = PivotFilter.Label("f2", "name", "Name", op, "widget")
            assertEquals(FilterEditorKind.Label, FilterDraft.from(filter).kind)
            assertEquals(filter, FilterDraft.from(filter).toFilter())
        }
    }

    @Test
    fun comparisonValueFilterRoundTripsEveryNonTopBottomOperator() {
        ValueFilterOp.entries
            .filterNot { it == ValueFilterOp.Top || it == ValueFilterOp.Bottom }
            .forEach { op ->
                val filter =
                    PivotFilter.Value(
                        id = "f3",
                        column = "amount",
                        label = "Amount",
                        measureAlias = "m_sum",
                        op = op,
                        value = "12.5",
                        secondValue = if (op == ValueFilterOp.Between) "20" else null,
                    )
                assertEquals(FilterEditorKind.Value, FilterDraft.from(filter).kind)
                assertEquals(filter, FilterDraft.from(filter).toFilter())
            }
    }

    @Test
    fun topAndBottomValueFiltersRoundTripAsTopN() {
        listOf(ValueFilterOp.Top, ValueFilterOp.Bottom).forEach { op ->
            val filter =
                PivotFilter.Value(
                    id = "f4",
                    column = "amount",
                    label = "Amount",
                    measureAlias = "m_sum",
                    op = op,
                    count = 25,
                )
            assertEquals(FilterEditorKind.TopN, FilterDraft.from(filter).kind)
            assertEquals(filter, FilterDraft.from(filter).toFilter())
        }
    }

    // The round trip is deliberately narrowing: each editor kind writes only the fields it exposes
    // and lets the model default supply the rest.
    @Test
    fun roundTripDropsFieldsTheEditorKindDoesNotExpose() {
        val comparisonWithTopNCount =
            PivotFilter.Value(
                id = "f5",
                column = "amount",
                label = "Amount",
                measureAlias = "m_sum",
                op = ValueFilterOp.GreaterThan,
                value = "1",
                count = 25,
            )
        assertEquals(
            comparisonWithTopNCount.copy(count = 10),
            FilterDraft.from(comparisonWithTopNCount).toFilter(),
        )

        val topNWithComparisonValues =
            comparisonWithTopNCount.copy(
                op = ValueFilterOp.Top,
                value = "1",
                secondValue = "9",
                count = 25,
            )
        assertEquals(
            topNWithComparisonValues.copy(value = "", secondValue = null),
            FilterDraft.from(topNWithComparisonValues).toFilter(),
        )
    }

    @Test
    fun roundTripNormalizesTextAndClampsCount() {
        val paddedLabel = PivotFilter.Label("f6", "name", "Name", LabelFilterOp.Equals, "  widget ")
        assertEquals(
            paddedLabel.copy(value = "widget"),
            FilterDraft.from(paddedLabel).toFilter(),
        )

        val paddedValue =
            PivotFilter.Value(
                id = "f7",
                column = "amount",
                label = "Amount",
                measureAlias = "m_sum",
                op = ValueFilterOp.Between,
                value = " 1 ",
                secondValue = "   ",
            )
        assertEquals(
            paddedValue.copy(value = "1", secondValue = null),
            FilterDraft.from(paddedValue).toFilter(),
        )

        val zeroCount =
            PivotFilter.Value(
                id = "f8",
                column = "amount",
                label = "Amount",
                measureAlias = "m_sum",
                op = ValueFilterOp.Top,
                count = 0,
            )
        assertEquals(zeroCount.copy(count = 1), FilterDraft.from(zeroCount).toFilter())
    }

    @Test
    fun pinnedIsScopedToMembersFilters() {
        val pinnedLabel =
            PivotFilter.Label("f9", "name", "Name", LabelFilterOp.Contains, "x", pinned = true)
        assertEquals(pinnedLabel.copy(pinned = false), FilterDraft.from(pinnedLabel).toFilter())

        val pinnedValue =
            PivotFilter.Value(
                id = "f10",
                column = "amount",
                label = "Amount",
                measureAlias = "m_sum",
                op = ValueFilterOp.LessThan,
                value = "3",
                pinned = true,
            )
        assertEquals(pinnedValue.copy(pinned = false), FilterDraft.from(pinnedValue).toFilter())

        // Switching kind carries the Members pin only into a Members filter.
        val draft = FilterDraft.from(pinnedLabel).copy(kind = FilterEditorKind.Members)
        assertEquals(true, (draft.toFilter() as PivotFilter.Members).pinned)
    }

    @Test
    fun measureAliasFallsBackOnlyForFiltersThatCarryNone() {
        val members = PivotFilter.Members("f11", "status", "Status")
        assertEquals("m_sum", FilterDraft.from(members, defaultMeasureAlias = "m_sum").measureAlias)

        val value =
            PivotFilter.Value(
                id = "f12",
                column = "amount",
                label = "Amount",
                measureAlias = "m_count",
                op = ValueFilterOp.GreaterThan,
                value = "1",
            )
        assertEquals("m_count", FilterDraft.from(value, defaultMeasureAlias = "m_sum").measureAlias)
    }
}
