package com.safedb.viewmodel

import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.explore.SortDir
import com.safedb.explore.MeasureFn
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
    fun resetRestoresInitialConfigurationAndPreview() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Measure("count"), SortDir.Desc))
        }

        assertFalse(viewModel.isDefaultConfig())
        viewModel.resetConfig()

        assertTrue(viewModel.isDefaultConfig())
        assertEquals(null, viewModel.config.sort)
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
    fun memberFiltersAndDrillThroughUseOnlySampleRows() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val options = viewModel.memberOptions("t0__status")
        assertEquals(listOf("pending", "shipped"), options.map { it.label })
        assertEquals(listOf(2, 1), options.map { it.count })

        val filter = PivotFilter.Members("status-filter", "t0__status", "Status")
        viewModel.updateConfig { it.copy(filters = listOf(filter)) }
        viewModel.updateMemberFilter("status-filter", setOf(options.first { it.label == "pending" }.key))

        assertEquals(listOf("pending", "Total"), viewModel.preview.layout.rowEntries.map { it.label })
        val row = viewModel.preview.layout.rowEntries.first()
        val column = viewModel.preview.layout.columnLeaves.single()
        val detail = viewModel.sourceRowsFor(row.pathKey, column.pathKey, viewModel.config.measures.single().alias)
        assertEquals(2, detail.rowCount)
        assertTrue(detail.rows.all { (it[1] as ResultCell.TextCell).value.text == "pending" })
    }

    @Test
    fun hierarchyExpansionIsWindowLocalViewState() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(
                rowDimensions = listOf(
                    PivotDimension("t0__status", id = "status"),
                    PivotDimension("t0__amount", id = "amount"),
                ),
                showColumnTotals = false,
            )
        }
        val path = viewModel.preview.layout.rowEntries.first().pathKey
        viewModel.toggleRowPath(path)

        assertTrue(path in viewModel.config.collapsedRowPaths)
        assertFalse(viewModel.preview.layout.rowEntries.first { it.pathKey == path }.expanded)
    }

    @Test
    fun pivotExportUsesDisplayedPercentageFormatting() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(
                measures = listOf(
                    PivotMeasure(
                        "share",
                        MeasureFn.Count,
                        label = "Share",
                        showAs = PivotShowAs(ShowAsMode.PercentGrandTotal),
                    ),
                ),
            )
        }
        val path = createTempFile(suffix = ".csv")
        viewModel.savePreviewCsv(path)

        assertTrue(path.readText().contains("66.67%"))
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
