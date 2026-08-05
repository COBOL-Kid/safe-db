package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisualizationTemplatesTest {
    @Test
    fun catalogBuildsAllCoreTemplatesAndAvoidsIdentifier() {
        val templates = visualizationTemplates(sample())

        assertEquals(
            VisualizationTemplateId.TrendOverTime,
            suggestedVisualizationTemplates(sample()).first().id,
        )
        assertTrue(templates.all { it.result is VisualizationTemplateBuildResult.Ready })
        val breakdown =
            assertIs<VisualizationTemplateBuildResult.Ready>(
                templates.first { it.id == VisualizationTemplateId.Breakdown }.result
            )
        assertEquals("status", breakdown.config.x?.column)
    }

    @Test
    fun catalogExplainsUnavailableTemplatesAndFallsBackToCount() {
        val textOnly =
            QueryResult(
                columns = listOf(ResultColumn("status", "varchar")),
                rows = listOf(listOf(ResultCell.text("open"))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val templates = visualizationTemplates(textOnly)

        assertIs<VisualizationTemplateBuildResult.Unavailable>(
            templates.first { it.id == VisualizationTemplateId.TrendOverTime }.result
        )
        val kpi =
            assertIs<VisualizationTemplateBuildResult.Ready>(
                templates.first { it.id == VisualizationTemplateId.SingleValue }.result
            )
        assertEquals(MeasureFn.Count, kpi.config.values.single().fn)
    }

    private fun sample() =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("id", "bigint"),
                    ResultColumn("status", "varchar"),
                    ResultColumn("region", "varchar"),
                    ResultColumn("created_at", "datetime"),
                    ResultColumn("amount", "decimal"),
                    ResultColumn("score", "decimal"),
                ),
            rows =
                listOf(
                    listOf(
                        ResultCell.IntegerCell(1),
                        ResultCell.text("open"),
                        ResultCell.text("West"),
                        ResultCell.text("2026-01-01"),
                        ResultCell.IntegerCell(10),
                        ResultCell.IntegerCell(2),
                    ),
                    listOf(
                        ResultCell.IntegerCell(2),
                        ResultCell.text("closed"),
                        ResultCell.text("East"),
                        ResultCell.text("2026-02-01"),
                        ResultCell.IntegerCell(20),
                        ResultCell.IntegerCell(5),
                    ),
                ),
            rowCount = 2,
            truncated = false,
            warnings = emptyList(),
        )
}
