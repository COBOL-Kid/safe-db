package com.safedb.ui

import com.safedb.explore.DateGroupUnit
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.ValueFilterOp
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExploreTemplatesTest {
    @Test
    fun breakdownTemplateUsesFirstGroupableField() {
        val fields = sampleFields()
        val result = resolveExploreTemplate(ExploreBuiltinTemplateId.Breakdown, sample(), fields)
        val ready = assertIs<ExploreTemplateBuildResult.Ready>(result)
        assertEquals(listOf("status"), ready.config.rowDimensions.map { it.column })
        assertEquals(MeasureFn.Count, ready.config.measures.single().fn)
    }

    @Test
    fun trendTemplateRequiresDateAndNumericColumns() {
        val fields = sampleFields()
        val missingDate =
            resolveExploreTemplate(
                ExploreBuiltinTemplateId.TrendOverTime,
                sample(),
                fields.filterNot { it.column == "created_at" },
            )
        assertIs<ExploreTemplateBuildResult.Unavailable>(missingDate)

        val ready =
            assertIs<ExploreTemplateBuildResult.Ready>(
                resolveExploreTemplate(ExploreBuiltinTemplateId.TrendOverTime, sample(), fields)
            )
        assertEquals(
            PivotGrouping.Date(DateGroupUnit.Month),
            ready.config.rowDimensions.single().grouping,
        )
        assertEquals(MeasureFn.Sum, ready.config.measures.single().fn)
        assertEquals("amount", ready.config.measures.single().sourceColumn)
    }

    @Test
    fun compareTemplateNeedsTwoGroupableFields() {
        val unavailable =
            resolveExploreTemplate(
                ExploreBuiltinTemplateId.CompareCategories,
                sample(),
                sampleFields().filter { it.column == "status" },
            )
        assertIs<ExploreTemplateBuildResult.Unavailable>(unavailable)

        val ready =
            assertIs<ExploreTemplateBuildResult.Ready>(
                resolveExploreTemplate(
                    ExploreBuiltinTemplateId.CompareCategories,
                    sample(),
                    sampleFields(),
                )
            )
        assertEquals("status", ready.config.rowDimensions.single().column)
        assertEquals("region", ready.config.effectiveColumnDimensions.single().column)
    }

    @Test
    fun topNTemplateAddsTopFilter() {
        val ready =
            assertIs<ExploreTemplateBuildResult.Ready>(
                resolveExploreTemplate(ExploreBuiltinTemplateId.TopN, sample(), sampleFields())
            )
        val filter = ready.config.filters.single() as PivotFilter.Value
        assertEquals(ValueFilterOp.Top, filter.op)
        assertEquals(10, filter.count)
    }

    @Test
    fun templateListMarksUnavailableTemplates() {
        val items = listExploreTemplates(sample(), sampleFields().filter { it.column == "amount" })
        val trend = items.first { it.id == ExploreBuiltinTemplateId.TrendOverTime }
        assertFalse(trend.available)
        assertEquals("Needs a date or datetime column in your sample.", trend.unavailableReason)
        val breakdown = items.first { it.id == ExploreBuiltinTemplateId.Breakdown }
        assertTrue(breakdown.available)
    }

    private fun sample(): QueryResult =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("id", "bigint"),
                    ResultColumn("status", "varchar"),
                    ResultColumn("region", "varchar"),
                    ResultColumn("created_at", "datetime"),
                    ResultColumn("amount", "decimal"),
                ),
            rows = emptyList(),
            rowCount = 0,
            truncated = false,
            warnings = emptyList(),
        )

    private fun sampleFields(): List<ExploreFieldOption> =
        buildExploreFieldOptions(sample(), emptyList())
}
