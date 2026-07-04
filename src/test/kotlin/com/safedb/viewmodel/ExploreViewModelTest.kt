package com.safedb.viewmodel

import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.SortDir
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExploreViewModelTest {
    @Test
    fun defaultConfigChoosesReadableDimensionAndCountMeasure() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))

        assertEquals(listOf("t0__status"), viewModel.config.rowDimensions.map { it.column })
        assertEquals("Count", viewModel.config.measures.single().label)
        assertEquals(listOf("status", "Count"), viewModel.preview.result.columns.map { it.name })
    }

    @Test
    fun configUpdatesRecomputePreviewWithoutService() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))

        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Measure("count"), SortDir.Desc))
        }

        assertEquals("pending", (viewModel.preview.result.rows.first()[0] as ResultCell.TextCell).value.text)
    }

    @Test
    fun staleDetectionUsesBaseSpecHash() {
        val baseSpec = sampleSpec()
        val viewModel = ExploreViewModel(createExploreSession(connection(), baseSpec, sampleResult()))

        assertFalse(viewModel.isStale(baseSpec))
        assertTrue(viewModel.isStale(baseSpec.copy(limit = 250)))
    }

    @Test
    fun savePreviewCsvExportsCurrentView() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val path = createTempFile(suffix = ".csv")

        viewModel.savePreviewCsv(path)

        assertNotNull(viewModel.exportMessage)
        assertEquals(
            "status,Count\r\npending,2\r\nshipped,1\r\nTotal,3\r\n",
            path.readText(),
        )
    }

    private fun connection() = ConnectionDef(
        id = "c1",
        name = "Local",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb",
        username = "root",
    )

    private fun sampleSpec() = QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )

    private fun sampleResult() = QueryResult(
        columns = listOf(
            ResultColumn("t0__id", "bigint"),
            ResultColumn("t0__status", "varchar"),
            ResultColumn("t0__amount", "bigint"),
        ),
        rows = listOf(
            listOf(ResultCell.IntegerCell(1), ResultCell.text("pending"), ResultCell.IntegerCell(100)),
            listOf(ResultCell.IntegerCell(2), ResultCell.text("pending"), ResultCell.IntegerCell(200)),
            listOf(ResultCell.IntegerCell(3), ResultCell.text("shipped"), ResultCell.IntegerCell(300)),
        ),
        rowCount = 3,
        truncated = false,
        warnings = emptyList(),
    )
}
