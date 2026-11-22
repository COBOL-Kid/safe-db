package com.safedb.viewmodel

import com.safedb.explore.ChartType
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.ExploreWorkspaceState
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotShowAs
import com.safedb.explore.RecipeField
import com.safedb.explore.ShowAsMode
import com.safedb.explore.SortDir
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.WorksheetAggregateFn
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetGroup
import com.safedb.explore.WorksheetValueRef
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @Test
    fun defaultConfigChoosesReadableDimensionAndCountMeasure() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))

        assertEquals(listOf("t0__status"), viewModel.config.rowDimensions.map { it.column })
        assertEquals("Count", viewModel.config.measures.single().label)
        assertEquals(listOf("status", "Count"), viewModel.preview.result.columns.map { it.name })
    }

    @Test
    fun configUpdatesRecomputePreviewWithoutService() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))

        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Measure("count"), SortDir.Desc))
        }

        assertEquals(
            "pending",
            (viewModel.preview.result.rows.first()[0] as ResultCell.TextCell).value.text,
        )
    }

    @Test
    fun debouncedPreviewPublishesOnlyTheLatestConfig() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                computationScope = this,
                computeDispatcher = dispatcher,
            )

        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Dimension("t0__status"), SortDir.Desc))
        }
        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Dimension("t0__status"), SortDir.Asc))
        }

        assertTrue(viewModel.isLoading(ExploreMode.Pivot))
        advanceTimeBy(75)
        runCurrent()

        assertFalse(viewModel.isLoading(ExploreMode.Pivot))
        assertEquals(SortDir.Asc, viewModel.config.sort?.dir)
        assertEquals(
            "pending",
            (viewModel.preview.result.rows.first().first() as ResultCell.TextCell).value.text,
        )
    }

    @Test
    fun failedPreviewSurfacesErrorPerModeAndClearsOnNextSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rows = FailingRows(sampleResult().rows)
        val viewModel =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult().copy(rows = rows)),
                computationScope = this,
                computeDispatcher = dispatcher,
            )

        ExploreMode.entries.forEach { mode ->
            viewModel.selectMode(mode)
            rows.failing = true
            viewModel.reconfigure(mode)
            advanceTimeBy(75)
            runCurrent()

            assertTrue(
                viewModel.previewError(mode)?.contains("compute failed") == true,
                "$mode should surface the compute failure",
            )
            assertFalse(viewModel.isLoading(mode))

            rows.failing = false
            viewModel.reconfigure(mode)
            advanceTimeBy(75)
            runCurrent()

            assertEquals(null, viewModel.previewError(mode), "$mode should clear on success")
            assertFalse(viewModel.isLoading(mode))
        }
    }

    @Test
    fun resetRestoresInitialConfigurationAndPreview() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(sort = ExploreSort(ExploreSortTarget.Measure("count"), SortDir.Desc))
        }

        assertFalse(viewModel.isDefaultConfig())
        viewModel.resetConfig()

        assertTrue(viewModel.isDefaultConfig())
        assertEquals(null, viewModel.config.sort)
        assertEquals(
            "pending",
            (viewModel.preview.result.rows.first()[0] as ResultCell.TextCell).value.text,
        )
    }

    @Test
    fun staleDetectionUsesBaseSpecHash() {
        val baseSpec = sampleSpec()
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), baseSpec, sampleResult()))

        assertFalse(viewModel.isStale(baseSpec))
        assertFalse(viewModel.isStale(baseSpec, "c1"))
        // Untracked sessions (sampleRunId == null) must stay fresh even when latestSampleRunId is
        // set.
        assertEquals(
            SampleStaleness.Fresh,
            viewModel.staleness(baseSpec, "c1", latestSampleRunId = 4L),
        )
        assertFalse(viewModel.isStale(baseSpec, "c1", latestSampleRunId = 4L))
        assertEquals(SampleStaleness.ConnectionChanged, viewModel.staleness(baseSpec, "c2"))
        assertTrue(viewModel.isStale(baseSpec, "c2"))
        assertEquals(SampleStaleness.SpecChanged, viewModel.staleness(baseSpec.copy(limit = 250)))
        assertTrue(viewModel.isStale(baseSpec.copy(limit = 250)))
        assertEquals(SampleStaleness.SpecChanged, viewModel.staleness(null))
        assertTrue(viewModel.isStale(null))

        val runVm =
            ExploreViewModel(
                createExploreSession(connection(), baseSpec, sampleResult()),
                sampleRunId = 3L,
            )
        assertEquals(
            SampleStaleness.Fresh,
            runVm.staleness(baseSpec, "c1", latestSampleRunId = null),
        )
        assertEquals(SampleStaleness.Fresh, runVm.staleness(baseSpec, "c1", latestSampleRunId = 3L))
        assertFalse(runVm.isStale(baseSpec, "c1", latestSampleRunId = 3L))
        assertEquals(
            SampleStaleness.NewerSample,
            runVm.staleness(baseSpec, "c1", latestSampleRunId = 4L),
        )
        assertTrue(runVm.isStale(baseSpec, "c1", latestSampleRunId = 4L))
    }

    @Test
    fun memberFiltersAndDrillThroughUseOnlySampleRows() {
        val viewModel =
            ExploreViewModel(
                createExploreSession(
                    connection(),
                    sampleSpec(),
                    sampleResult()
                        .copy(
                            warnings =
                                listOf("No columns selected — query will select all columns"),
                        ),
                ),
            )
        val options = viewModel.memberOptions("t0__status")
        assertEquals(listOf("pending", "shipped"), options.map { it.label })
        assertEquals(listOf(2, 1), options.map { it.count })

        val filter = PivotFilter.Members("status-filter", "t0__status", "Status")
        viewModel.updateConfig { it.copy(filters = listOf(filter)) }
        viewModel.updateMemberFilter(
            "status-filter",
            setOf(options.first { it.label == "pending" }.key),
        )

        assertEquals(
            listOf("pending", "Total"),
            viewModel.preview.layout.rowEntries.map { it.label },
        )
        val row = viewModel.preview.layout.rowEntries.first()
        val column = viewModel.preview.layout.columnLeaves.single()
        val detail =
            viewModel.sourceRowsFor(
                row.pathKey,
                column.pathKey,
                viewModel.config.measures.single().alias,
            )
        assertEquals(2, detail.rowCount)
        assertTrue(detail.rows.all { (it[1] as ResultCell.TextCell).value.text == "pending" })
        assertEquals(emptyList(), detail.warnings)
    }

    @Test
    fun hierarchyExpansionIsWindowLocalViewState() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(
                rowDimensions =
                    listOf(
                        PivotDimension("t0__status", id = "status"),
                        PivotDimension("t0__amount", id = "amount"),
                    ),
                showColumnTotals = false,
            )
        }
        val path = viewModel.preview.layout.rowEntries.first { it.hasChildren }.pathKey
        viewModel.toggleRowPath(path)

        assertTrue(path in viewModel.config.collapsedRowPaths)
        assertFalse(viewModel.preview.layout.rowEntries.first { it.pathKey == path }.expanded)
    }

    @Test
    fun pivotExportUsesDisplayedPercentageFormatting() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig {
            it.copy(
                measures =
                    listOf(
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
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val path = createTempFile(suffix = ".csv")

        viewModel.savePreviewCsv(path)

        assertNotNull(viewModel.exportMessage)
        assertEquals("status,Count\r\npending,2\r\nshipped,1\r\nTotal,3\r\n", path.readText())
    }

    @Test
    fun savePreviewHtmlWritesInteractiveReport() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val path = createTempFile(suffix = ".html")

        viewModel.savePreviewHtml(path)

        assertEquals("Exported HTML report", viewModel.exportMessage)
        assertTrue(Files.exists(path))
        assertTrue(Files.size(path) > 0)
    }

    @Test
    fun saveWorksheetHtmlRequiresVisibleColumns() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheetColumnLayout(
            listOf(
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__id"), visible = false),
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__status"), visible = false),
                WorksheetColumnLayout(WorksheetValueRef.Column("t0__amount"), visible = false),
            ),
        )
        val path = createTempFile(suffix = ".html")

        viewModel.saveWorksheetHtml(path)

        assertEquals(
            "Show at least one worksheet column before exporting.",
            viewModel.exportError,
        )
    }

    @Test
    fun modesKeepIndependentStateAndWorksheetExportsDisplayedRows() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(
                groups = listOf(WorksheetGroup("g", "t0__status")),
                calculations =
                    listOf(WorksheetCalculation.RowFormula("double", "Double", "[t0__amount] * 2")),
            )
        }
        val groupPath =
            viewModel.worksheetPreview.rows
                .first { it.kind == com.safedb.explore.WorksheetRowKind.Group }
                .pathKey
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
    fun worksheetExportsUseVisibleColumnLayoutOrderAndOriginalCellIndexes() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(
                calculations =
                    listOf(WorksheetCalculation.RowFormula("double", "Double", "[t0__amount] * 2")),
            )
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

        assertEquals("Double,amount\r\n200.0,100\r\n400.0,200\r\n600.0,300\r\n", path.readText())

        val htmlPath = createTempFile(suffix = ".html")
        viewModel.saveWorksheetHtml(htmlPath)

        assertEquals("Exported HTML report", viewModel.exportMessage)
        val reportJson =
            htmlPath
                .readText()
                .substringAfter("<script id=\"report-data\" type=\"application/json\">")
                .substringBefore("</script>")
        val columns =
            Json.parseToJsonElement(reportJson)
                .jsonObject
                .getValue("table")
                .jsonObject
                .getValue("columns")
                .jsonArray
                .map { it.jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("Double", "amount"), columns)
    }

    @Test
    fun groupedWorksheetCsvKeepsHierarchySeparateFromFirstCalculation() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Worksheet)
        viewModel.updateWorksheet {
            it.copy(
                groups =
                    listOf(
                        WorksheetGroup("status", "t0__status", "Status"),
                        WorksheetGroup("id", "t0__id", "ID"),
                    ),
                calculations =
                    listOf(
                        WorksheetCalculation.Aggregate(
                            id = "revenue",
                            label = "Revenue",
                            fn = WorksheetAggregateFn.Sum,
                            sourceColumn = "t0__amount",
                        ),
                    ),
                columnLayout =
                    listOf(
                        WorksheetColumnLayout(WorksheetValueRef.Calculation("revenue")),
                        WorksheetColumnLayout(WorksheetValueRef.Column("t0__id"), visible = false),
                        WorksheetColumnLayout(
                            WorksheetValueRef.Column("t0__status"),
                            visible = false,
                        ),
                        WorksheetColumnLayout(
                            WorksheetValueRef.Column("t0__amount"),
                            visible = false,
                        ),
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
            """
                .trimIndent()
                .replace("\n", "\r\n") + "\r\n",
            path.readText(),
        )
    }

    @Test
    fun recipeAppliesSelectedModesTracksDirtyAndCanClearIdentity() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.updateConfig { it.copy(showSubtotals = false) }
        val recipe =
            ExploreRecipe(
                id = "r1",
                name = "Worksheet",
                createdAt = "1",
                updatedAt = "1",
                defaultMode = ExploreMode.Worksheet,
                worksheet = WorksheetConfig(groups = listOf(WorksheetGroup("g", "t0__status"))),
                requiredFields = listOf(RecipeField("t0__status", "status", "varchar", "orders")),
            )

        assertEquals(0, viewModel.worksheetConfigReplacementRevision)
        viewModel.requestRecipe(recipe)
        assertEquals(1, viewModel.worksheetConfigReplacementRevision)
        assertEquals("r1", viewModel.appliedRecipeId)
        assertEquals(ExploreMode.Worksheet, viewModel.workspace.activeMode)
        assertFalse(viewModel.config.showSubtotals)
        assertFalse(viewModel.recipeDirty())

        val path =
            viewModel.worksheetPreview.rows
                .first { it.kind == com.safedb.explore.WorksheetRowKind.Group }
                .pathKey
        viewModel.toggleWorksheetGroup(path)
        assertEquals(1, viewModel.worksheetConfigReplacementRevision)
        assertFalse(viewModel.recipeDirty())

        viewModel.updateWorksheet {
            it.copy(filters = listOf(com.safedb.explore.WorksheetFilter("f", "t0__status")))
        }
        assertEquals(1, viewModel.worksheetConfigReplacementRevision)
        assertTrue(viewModel.recipeDirty())
        viewModel.clearAppliedRecipe()
        assertEquals(null, viewModel.appliedRecipeId)
    }

    @Test
    fun worksheetDefaultCheckIncludesPersistentLayoutButIgnoresCollapsedGroups() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        assertTrue(viewModel.isDefaultWorksheet())

        viewModel.updateWorksheetColumnLayout(
            listOf(WorksheetColumnLayout(WorksheetValueRef.Column("t0__status"), visible = false)),
        )
        assertFalse(viewModel.isDefaultWorksheet())

        viewModel.updateWorksheet { WorksheetConfig(collapsedGroupPaths = setOf("pending")) }
        assertTrue(viewModel.isDefaultWorksheet())
    }

    @Test
    fun unresolvedRecipeWaitsForManualMapping() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val recipe =
            ExploreRecipe(
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
        val original =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        val recipe =
            ExploreRecipe(
                id = "tracked",
                name = "Tracked",
                createdAt = "1",
                updatedAt = "1",
                defaultMode = ExploreMode.Pivot,
                pivot = original.config,
            )
        original.applyRecipe(recipe)
        val refreshed =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec().copy(limit = 50), sampleResult()),
                initialWorkspace = original.workspace,
            )

        refreshed.inheritRecipeTrackingFrom(original)

        assertEquals("tracked", refreshed.appliedRecipeId)
        assertFalse(refreshed.recipeDirty())
    }

    @Test
    fun visualizationUpdatesDrillsResetsAndExportsChartData() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Visualization)
        viewModel.updateVisualization {
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values =
                    listOf(VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount", "Amount")),
            )
        }

        assertTrue(viewModel.visualizationPreview.ready)
        assertEquals(2, viewModel.visualizationPreview.marks.size)
        val pending = viewModel.visualizationPreview.marks.first { it.xLabel == "pending" }
        assertEquals(2, viewModel.sourceRowsForVisualizationMark(pending.id).rowCount)
        assertFalse(viewModel.isDefaultVisualization())

        val csv = createTempFile(suffix = ".csv")
        viewModel.saveVisualizationCsv(csv)
        assertTrue(csv.readText().contains("Status,Measure,Value,Source rows"))
        assertTrue(csv.readText().contains("pending"))

        viewModel.updateVisualization { it.copy(series = VisualizationField("t0__id", "Order")) }
        val seriesCsv = createTempFile(suffix = ".csv")
        viewModel.saveVisualizationCsv(seriesCsv)
        assertTrue(seriesCsv.readText().contains("Status,Series,Measure,Value,Source rows"))
        viewModel.updateVisualization { it.copy(series = null) }

        val html = createTempFile(suffix = ".html")
        viewModel.saveVisualizationHtml(html)
        assertEquals("Exported HTML report", viewModel.exportMessage)
        assertTrue(html.readText().contains("pending"))

        viewModel.resetVisualization()
        viewModel.saveVisualizationHtml(html)
        assertEquals("Complete the chart before exporting.", viewModel.exportError)
        assertTrue(viewModel.isDefaultVisualization())
        assertFalse(viewModel.visualizationPreview.ready)
    }

    @Test
    fun visualizationPngExportsAtRequiredDimensions() {
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), sampleSpec(), sampleResult()))
        viewModel.selectMode(ExploreMode.Visualization)
        viewModel.updateVisualization {
            VisualizationConfig(
                chartType = ChartType.Kpi,
                values =
                    listOf(VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount", "Amount")),
            )
        }
        val path = createTempFile(suffix = ".png")

        viewModel.saveVisualizationPng(path, isDark = false)

        assertEquals(null, viewModel.exportError)
        val bytes = Files.readAllBytes(path)
        assertTrue(
            bytes
                .take(8)
                .toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
        val image = Image.makeFromEncoded(bytes)
        assertEquals(1600, image.width)
        assertEquals(900, image.height)
    }

    @Test
    fun drillCsvExportUsesDisplayColumnLabelsWithoutAliasMangling() {
        val joinedSpec =
            sampleSpec()
                .copy(
                    tables =
                        listOf(
                            TableRef("sales", "regions", "r"),
                            TableRef("sales", "accounts", "a"),
                            TableRef("sales", "order_items", "oi"),
                        ),
                )
        val joinedSample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("r__name", "text"),
                        ResultColumn("a__name", "text"),
                        ResultColumn("oi__line_total", "numeric"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Europe"),
                            ResultCell.text("Acme"),
                            ResultCell.float(455.5),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val viewModel =
            ExploreViewModel(createExploreSession(connection(), joinedSpec, joinedSample))
        val drill =
            viewModel.sourceRowsFor(
                viewModel.preview.layout.rowEntries.first().pathKey,
                viewModel.preview.layout.columnLeaves.single().pathKey,
                viewModel.config.measures.single().alias,
            )
        val csv = createTempFile(suffix = ".csv")

        viewModel.saveDrillResultCsv(drill, csv)

        val lines = csv.readText().trim().lines()
        assertEquals("regions.name,accounts.name,line_total", lines.first())
        assertTrue(lines[1].startsWith("Europe,Acme,455.5"))
    }

    @Test
    fun initialComputationOnlyComputesActiveMode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val asyncWorksheetVm =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                initialWorkspace =
                    ExploreWorkspaceState(
                        pivot = ExploreConfig(),
                        activeMode = ExploreMode.Worksheet,
                    ),
                computationScope = this,
                computeDispatcher = dispatcher,
            )

        assertTrue(asyncWorksheetVm.isLoading(ExploreMode.Worksheet))
        assertFalse(asyncWorksheetVm.isLoading(ExploreMode.Pivot))
        assertFalse(asyncWorksheetVm.isLoading(ExploreMode.Visualization))
        assertTrue(asyncWorksheetVm.worksheetPreview.columns.isEmpty())

        runCurrent()

        assertFalse(asyncWorksheetVm.isLoading(ExploreMode.Worksheet))
        assertTrue(asyncWorksheetVm.worksheetPreview.columns.isNotEmpty())
        assertTrue(asyncWorksheetVm.preview.result.columns.isEmpty())
        assertFalse(asyncWorksheetVm.visualizationPreview.ready)

        val syncVizVm =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                initialWorkspace =
                    ExploreWorkspaceState(
                        pivot = ExploreConfig(),
                        activeMode = ExploreMode.Visualization,
                        visualization =
                            VisualizationConfig(
                                chartType = ChartType.Kpi,
                                values =
                                    listOf(
                                        VisualizationMeasure(
                                            "amount",
                                            MeasureFn.Sum,
                                            "t0__amount",
                                            "Amount",
                                        ),
                                    ),
                            ),
                    ),
            )

        assertTrue(syncVizVm.visualizationPreview.ready)
        assertTrue(syncVizVm.preview.result.columns.isEmpty())
        assertTrue(syncVizVm.worksheetPreview.columns.isEmpty())

        syncVizVm.selectMode(ExploreMode.Pivot)
        assertTrue(syncVizVm.preview.result.columns.isNotEmpty())
    }

    @Test
    fun initialRecipeSkipsDefaultPreviewPass() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val resolvedRecipe =
            ExploreRecipe(
                id = "r-init",
                name = "Worksheet",
                createdAt = "1",
                updatedAt = "1",
                defaultMode = ExploreMode.Worksheet,
                worksheet = WorksheetConfig(groups = listOf(WorksheetGroup("g", "t0__status"))),
                requiredFields = listOf(RecipeField("t0__status", "status", "varchar", "orders")),
            )

        val resolvedVm =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                computationScope = this,
                computeDispatcher = dispatcher,
                initialRecipe = resolvedRecipe,
            )

        assertTrue(resolvedVm.isLoading(ExploreMode.Worksheet))
        assertFalse(resolvedVm.isLoading(ExploreMode.Pivot))
        assertEquals(ExploreMode.Worksheet, resolvedVm.workspace.activeMode)

        runCurrent()

        assertFalse(resolvedVm.isLoading(ExploreMode.Worksheet))
        assertEquals("r-init", resolvedVm.appliedRecipeId)
        assertTrue(resolvedVm.worksheetPreview.rows.isNotEmpty())
        assertTrue(resolvedVm.preview.result.columns.isEmpty())

        val unresolvedRecipe =
            ExploreRecipe(
                id = "r-map",
                name = "Mapped",
                createdAt = "1",
                updatedAt = "1",
                defaultMode = ExploreMode.Worksheet,
                worksheet = WorksheetConfig(groups = listOf(WorksheetGroup("g", "old_status"))),
                requiredFields = listOf(RecipeField("old_status", "Unknown status", "varchar")),
            )

        val unresolvedVm =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                computationScope = this,
                computeDispatcher = dispatcher,
                initialRecipe = unresolvedRecipe,
            )

        assertEquals("r-map", unresolvedVm.pendingRecipe?.id)
        assertTrue(unresolvedVm.isLoading(ExploreMode.Pivot))
        runCurrent()
        assertTrue(unresolvedVm.preview.result.columns.isNotEmpty())
    }

    @Test
    fun executeExportIgnoresReentrantCallsWhileExportIsInFlight() = runTest {
        val computeDispatcher = StandardTestDispatcher(testScheduler)
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel =
            ExploreViewModel(
                createExploreSession(connection(), sampleSpec(), sampleResult()),
                computationScope = this,
                computeDispatcher = computeDispatcher,
                ioDispatcher = ioDispatcher,
            )
        runCurrent()

        val firstPath = createTempFile(suffix = ".csv")
        val secondPath = createTempFile(suffix = ".csv")

        viewModel.savePreviewCsv(firstPath)
        assertTrue(viewModel.exporting)

        viewModel.savePreviewCsv(secondPath)
        advanceUntilIdle()

        assertFalse(viewModel.exporting)
        assertTrue(Files.size(firstPath) > 0L)
        assertEquals(0L, Files.size(secondPath))
    }

    private fun ExploreViewModel.reconfigure(mode: ExploreMode) {
        when (mode) {
            ExploreMode.Pivot -> updateConfig { it.copy(nullBucketLabel = "(none)") }
            ExploreMode.Worksheet ->
                updateWorksheet { it.copy(groups = listOf(WorksheetGroup("g", "t0__status"))) }
            ExploreMode.Visualization ->
                updateVisualization {
                    VisualizationConfig(
                        chartType = ChartType.Bar,
                        x = VisualizationField("t0__status", "Status"),
                        values =
                            listOf(
                                VisualizationMeasure(
                                    "amount",
                                    MeasureFn.Sum,
                                    "t0__amount",
                                    "Amount",
                                ),
                            ),
                    )
                }
        }
    }

    // Fails every cell read once failing is set, so each engine's compute throws.
    private class FailingRows(private val rows: List<List<ResultCell>>) :
        AbstractList<List<ResultCell>>() {
        var failing = false

        override val size: Int
            get() = rows.size

        override fun get(index: Int): List<ResultCell> =
            if (failing) error("compute failed") else rows[index]
    }

    private fun connection() =
        ConnectionDef(
            id = "c1",
            name = "Local",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb",
            username = "root",
        )

    private fun sampleSpec() =
        QuerySpec(
            tables = listOf(TableRef("public", "orders", "t0")),
            columns = emptyList(),
            joins = emptyList(),
            filters = FilterGroup.empty(),
            limit = 100,
        )

    private fun sampleResult() =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("t0__id", "bigint"),
                    ResultColumn("t0__status", "varchar"),
                    ResultColumn("t0__amount", "bigint"),
                ),
            rows =
                listOf(
                    listOf(
                        ResultCell.IntegerCell(1),
                        ResultCell.text("pending"),
                        ResultCell.IntegerCell(100),
                    ),
                    listOf(
                        ResultCell.IntegerCell(2),
                        ResultCell.text("pending"),
                        ResultCell.IntegerCell(200),
                    ),
                    listOf(
                        ResultCell.IntegerCell(3),
                        ResultCell.text("shipped"),
                        ResultCell.IntegerCell(300),
                    ),
                ),
            rowCount = 3,
            truncated = false,
            warnings = emptyList(),
        )
}
