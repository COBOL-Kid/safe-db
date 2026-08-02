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
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.RecipeField
import com.safedb.explore.WorksheetAggregateFn
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetGroup
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
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
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Image

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun debouncedPreviewPublishesOnlyTheLatestConfig() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ExploreViewModel(
            createExploreSession(connection(), sampleSpec(), sampleResult()),
            computationScope = this,
            computeDispatcher = dispatcher,
        )

        viewModel.updateConfig { it.copy(sort = ExploreSort(ExploreSortTarget.Dimension("t0__status"), SortDir.Desc)) }
        viewModel.updateConfig { it.copy(sort = ExploreSort(ExploreSortTarget.Dimension("t0__status"), SortDir.Asc)) }

        assertTrue(viewModel.pivotPreviewState.loading)
        advanceTimeBy(75)
        runCurrent()

        assertFalse(viewModel.pivotPreviewState.loading)
        assertEquals(SortDir.Asc, viewModel.config.sort?.dir)
        assertEquals("pending", (viewModel.preview.result.rows.first().first() as ResultCell.TextCell).value.text)
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

    @Test
    fun modesKeepIndependentStateAndWorksheetExportsDisplayedRows() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(
                groups = listOf(WorksheetGroup("g", "t0__status")),
                calculations = listOf(WorksheetCalculation.RowFormula("double", "Double", "[t0__amount] * 2")),
            )
        }
        val groupPath = viewModel.worksheetPreview.rows.first { it.kind == com.safedb.explore.WorksheetRowKind.Group }.pathKey
        viewModel.toggleWorksheetGroup(groupPath)
        val path = createTempFile(suffix = ".csv")
        viewModel.saveWorksheetCsv(path)

        assertEquals(ExploreMode.Worksheet, viewModel.workspace.activeMode)
        assertEquals(listOf("t0__status"), viewModel.worksheetConfig.groups.map { it.column })
        assertTrue(groupPath in viewModel.worksheetConfig.collapsedGroupPaths)
        assertTrue(path.readText().contains("Double"))
        viewModel.selectMode(ExploreMode.Pivot)
        assertEquals(listOf("t0__status"), viewModel.config.rowDimensions.map { it.column })
    }

    @Test
    fun worksheetCsvUsesVisibleColumnLayoutOrderAndOriginalCellIndexes() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(calculations = listOf(WorksheetCalculation.RowFormula("double", "Double", "[t0__amount] * 2")))
        }
        viewModel.updateWorksheetColumnLayout(
            listOf(
                WorksheetColumnLayout(WorksheetValueRef.Calculation("double")),
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__status"), visible = false),
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__amount")),
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__id"), visible = false),
            ),
        )
        val path = createTempFile(suffix = ".csv")

        viewModel.saveWorksheetCsv(path)

        assertEquals(
            "Double,amount\r\n200.0,100\r\n400.0,200\r\n600.0,300\r\n",
            path.readText(),
        )
    }

    @Test
    fun groupedWorksheetCsvKeepsHierarchySeparateFromFirstCalculation() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(
                groups = listOf(
                    WorksheetGroup("status", "t0__status", "Status"),
                    WorksheetGroup("id", "t0__id", "ID"),
                ),
                calculations = listOf(
                    WorksheetCalculation.Aggregate(
                        id = "revenue",
                        label = "Revenue",
                        fn = WorksheetAggregateFn.Sum,
                        sourceColumn = "t0__amount",
                    ),
                ),
                columnLayout = listOf(
                    WorksheetColumnLayout(WorksheetValueRef.Calculation("revenue")),
                    WorksheetColumnLayout(WorksheetValueRef.Column("t0__id"), visible = false),
                    WorksheetColumnLayout(WorksheetValueRef.Column("t0__status"), visible = false),
                    WorksheetColumnLayout(WorksheetValueRef.Column("t0__amount"), visible = false),
                ),
            )
        }
        val path = createTempFile(suffix = ".csv")

        viewModel.saveWorksheetCsv(path)

        assertEquals(
            """
            Group,Revenue
            Status: pending,300.0
            ID: 1,100.0
            ,
            ID: 2,200.0
            ,
            Status: shipped,300.0
            ID: 3,300.0
            ,
            Grand total,600.0
            """.trimIndent().replace("\n", "\r\n") + "\r\n",
            path.readText(),
        )
    }

    @Test
    fun recipeAppliesSelectedModesTracksDirtyAndCanClearIdentity() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig { it.copy(showSubtotals = false) }
        val recipe = ExploreRecipe(
            id = "r1",
            name = "Worksheet",
            createdAt = "1",
            updatedAt = "1",
            defaultMode = ExploreMode.Worksheet,
            worksheet = WorksheetConfig(groups = listOf(WorksheetGroup("g", "t0__status"))),
            requiredFields = listOf(RecipeField("t0__status", "status", "varchar", "orders")),
        )

        viewModel.requestRecipe(recipe)
        assertEquals("r1", viewModel.appliedRecipeId)
        assertEquals(ExploreMode.Worksheet, viewModel.workspace.activeMode)
        assertFalse(viewModel.config.showSubtotals)
        assertFalse(viewModel.recipeDirty())

        val path = viewModel.worksheetPreview.rows.first { it.kind == com.safedb.explore.WorksheetRowKind.Group }.pathKey
        viewModel.toggleWorksheetGroup(path)
        assertFalse(viewModel.recipeDirty())

        viewModel.updateWorksheet { it.copy(filters = listOf(com.safedb.explore.WorksheetFilter("f", "t0__status"))) }
        assertTrue(viewModel.recipeDirty())
        viewModel.clearAppliedRecipe()
        assertEquals(null, viewModel.appliedRecipeId)
    }

    @Test
    fun unresolvedRecipeWaitsForManualMapping() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val recipe = ExploreRecipe(
            id = "r2",
            name = "Mapped",
            createdAt = "1",
            updatedAt = "1",
            defaultMode = ExploreMode.Worksheet,
            worksheet = WorksheetConfig(groups = listOf(WorksheetGroup("g", "old_status"))),
            requiredFields = listOf(RecipeField("old_status", "Unknown status", "varchar")),
        )

        viewModel.requestRecipe(recipe)
        assertEquals("r2", viewModel.pendingRecipe?.id)
        viewModel.applyPendingRecipe(mapOf("old_status" to "t0__status"))
        assertEquals("t0__status", viewModel.worksheetConfig.groups.single().column)
        assertEquals(null, viewModel.pendingRecipe)

        viewModel.requestRecipe(recipe)
        viewModel.dismissPendingRecipe()
        assertEquals(null, viewModel.pendingRecipe)
    }

    @Test
    fun refreshedWorkspaceCanPreserveAppliedRecipeTracking() {
        val original = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val recipe = ExploreRecipe(
            id = "tracked", name = "Tracked", createdAt = "1", updatedAt = "1",
            defaultMode = ExploreMode.Pivot, pivot = original.config,
        )
        original.applyRecipe(recipe)
        val refreshed = ExploreViewModel(
            createExploreSession(connection(), sampleSpec().copy(limit = 50), sampleResult()),
            initialWorkspace = original.workspace,
        )

        refreshed.inheritRecipeTrackingFrom(original)

        assertEquals("tracked", refreshed.appliedRecipeId)
        assertFalse(refreshed.recipeDirty())
    }

    @Test
    fun visualizationUpdatesDrillsResetsAndExportsChartData() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Visualization)
        viewModel.updateVisualization {
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount", "Amount")),
            )
        }

        assertTrue(viewModel.visualizationPreview.ready)
        assertEquals(2, viewModel.visualizationPreview.marks.size)
        val pending = viewModel.visualizationPreview.marks.first { it.xLabel == "pending" }
        assertEquals(2, viewModel.sourceRowsForVisualizationMark(pending.id).rowCount)
        assertFalse(viewModel.isDefaultVisualization())

        val csv = createTempFile(suffix = ".csv")
        viewModel.saveVisualizationCsv(csv)
        assertTrue(csv.readText().contains("Status,Series,Measure,Value,Source rows"))
        assertTrue(csv.readText().contains("pending"))

        viewModel.resetVisualization()
        assertTrue(viewModel.isDefaultVisualization())
        assertFalse(viewModel.visualizationPreview.ready)
    }

    @Test
    fun visualizationPngExportsAtRequiredDimensions() {
        val viewModel = ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Visualization)
        viewModel.updateVisualization {
            VisualizationConfig(
                chartType = ChartType.Kpi,
                values = listOf(VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount", "Amount")),
            )
        }
        val path = createTempFile(suffix = ".png")

        viewModel.saveVisualizationPng(path, isDark = false)

        assertEquals(null, viewModel.exportError)
        val bytes = Files.readAllBytes(path)
        assertTrue(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        val image = Image.makeFromEncoded(bytes)
        assertEquals(1600, image.width)
        assertEquals(900, image.height)
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
