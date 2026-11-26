package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.ExploreMode
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.ValueAxis
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
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.createExploreSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class VisualizationConfigPanelTest {
    @Test
    fun switchingToScatterClearsIncompatibleAssignments() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("status", "varchar"), ResultColumn("amount", "decimal")),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("status"),
                values = listOf(VisualizationMeasure.countRows()),
                size = VisualizationField("status"),
            )

        val scatter = config.forChartType(ChartType.Scatter, fields)

        assertEquals(null, scatter.x)
        assertTrue(scatter.values.isEmpty())
        assertEquals(null, scatter.size)
    }

    @Test
    fun visualizationSidebarAddValueAddsSecondMeasureAndTemplatesOpensDialog() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                        ResultColumn("t0__qty", "integer"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.float(100.0),
                            ResultCell.integer(2),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        var config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "category"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "sum_amount",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Sum amount",
                        ),
                    ),
            )

        withComposeScene(
            width = 480,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            val addValueNode = scene.requireContaining("Add value")
            scene.click(addValueNode.boundsInRoot.center)
            scene.settle()

            // Value picker opens with "Count rows", not "Replace X field".
            val countRows = scene.requireContaining("Count rows")
            scene.click(countRows.boundsInRoot.center)
            scene.settle()

            assertEquals("t0__category", config.x?.column)
            assertEquals(2, config.values.size)
            assertEquals(MeasureFn.Count, config.values[1].fn)

            val templatesButton = scene.requireExact("Templates")
            scene.click(templatesButton.boundsInRoot.center)
            scene.settle()

            assertNotNull(scene.findContaining("Visualization templates"))
        }
    }

    @Test
    fun normalizeMeasureAxesAndSidebarAxisToggleWorkTogether() {
        val single = listOf(VisualizationMeasure("b", MeasureFn.Count, axis = ValueAxis.Secondary))
        assertEquals(ValueAxis.Primary, normalizeMeasureAxes(single).single().axis)
        val allSecondary =
            listOf(
                VisualizationMeasure("a", MeasureFn.Count, axis = ValueAxis.Secondary),
                VisualizationMeasure("b", MeasureFn.Count, axis = ValueAxis.Secondary),
            )
        assertEquals(
            listOf(ValueAxis.Primary, ValueAxis.Secondary),
            normalizeMeasureAxes(allSecondary).map { it.axis },
        )

        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows = listOf(listOf(ResultCell.text("Services"), ResultCell.float(100.0))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        var config by
            mutableStateOf(
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("t0__category", "category"),
                    values =
                        listOf(
                            VisualizationMeasure(
                                "sum_amount",
                                MeasureFn.Sum,
                                "t0__amount",
                                "Sum amount",
                            ),
                            VisualizationMeasure("count", MeasureFn.Count, label = "Count"),
                        ),
                ),
            )
        withComposeScene(
            width = 480,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        resolvedChartType = ChartType.Bar,
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            // Toggle second measure to Right axis.
            val rightPills = scene.nodesLabeled("Right axis")
            assertEquals(2, rightPills.size)
            scene.click(rightPills[1].boundsInRoot.center)
            scene.settle()
            assertEquals(ValueAxis.Secondary, config.values[1].axis)

            // First measure is the only Primary left, so clicking its Right axis pill is a no-op.
            scene.click(scene.nodesLabeled("Right axis")[0].boundsInRoot.center)
            scene.settle()
            assertEquals(ValueAxis.Primary, config.values[0].axis)

            // Switching to Stacked shows the shared-axis note while preserving the stored axis.
            config = config.copy(barArrangement = BarArrangement.Stacked)
            scene.settle()
            assertNotNull(scene.findContaining("Stacked bars share one axis."))
            assertEquals(ValueAxis.Secondary, config.values[1].axis)

            // Switching to Horizontal shows Bottom/Top axis labels.
            config =
                config.copy(
                    barArrangement = BarArrangement.Grouped,
                    barOrientation = BarOrientation.Horizontal,
                )
            scene.settle()
            assertEquals(2, scene.nodesLabeled("Top axis").size)
            assertEquals(2, scene.nodesLabeled("Bottom axis").size)

            // Switching to KPI via forChartType resets axis to Primary.
            val kpiConfig = config.forChartType(ChartType.Kpi, fields)
            assertEquals(ValueAxis.Primary, kpiConfig.values.single().axis)
        }
    }

    @Test
    fun histogramTransitionPromotesNumericMeasureClearsNoticeAndHidesDeadOptions() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.text("West"),
                            ResultCell.float(100.0),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        val barWithSeries =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "category"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "sum_amount",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Sum amount",
                        ),
                    ),
                series = VisualizationField("t0__region", "region"),
            )
        val promoted = barWithSeries.forChartType(ChartType.Histogram, fields)
        assertEquals("t0__amount", promoted.x?.column)
        assertEquals("amount", promoted.x?.label)
        assertTrue(promoted.values.isEmpty())
        assertNull(promoted.series)
        assertEquals(
            "Moved amount to X for histogram; removed incompatible series assignment.",
            chartTypeTransitionMessage(barWithSeries, promoted),
        )

        val barWithoutSeries = barWithSeries.copy(series = null)
        assertEquals(
            "Moved amount to X for histogram.",
            chartTypeTransitionMessage(
                barWithoutSeries,
                barWithoutSeries.forChartType(ChartType.Histogram, fields),
            ),
        )

        // Count-rows-only bar has no numeric measure to promote.
        val countOnlyBar = barWithoutSeries.copy(values = listOf(VisualizationMeasure.countRows()))
        val countOnlyHistogram = countOnlyBar.forChartType(ChartType.Histogram, fields)
        assertNull(countOnlyHistogram.x)
        assertEquals(
            "Removed incompatible X, values assignments.",
            chartTypeTransitionMessage(countOnlyBar, countOnlyHistogram),
        )

        // Options summary adapts to active chart type.
        assertEquals(
            "value sort · Top 20",
            visualizationOptionsSummary(barWithoutSeries, ChartType.Bar),
        )
        assertEquals("value sort", visualizationOptionsSummary(barWithoutSeries, ChartType.Line))
        assertEquals("Auto bins", visualizationOptionsSummary(promoted, ChartType.Histogram))
        assertEquals(
            "Bin size 250",
            visualizationOptionsSummary(
                promoted.copy(x = promoted.x?.copy(grouping = PivotGrouping.NumberBin(" 250 "))),
                ChartType.Histogram,
            ),
        )
        listOf("0", "-10", "abc").forEach { invalid ->
            assertEquals(
                "Auto bins",
                visualizationOptionsSummary(
                    promoted.copy(
                        x = promoted.x?.copy(grouping = PivotGrouping.NumberBin(invalid)),
                    ),
                    ChartType.Histogram,
                ),
            )
        }
        assertEquals(
            "Format & title",
            visualizationOptionsSummary(barWithoutSeries, ChartType.Scatter),
        )
        assertEquals("Title & display", visualizationOptionsSummary(VisualizationConfig(), null))

        var config by mutableStateOf(barWithoutSeries)
        // Keep resolvedChartType pinned to stale ChartType.Bar to verify explicit chartType
        // switches update Options immediately before debounced preview recomputation finishes.
        withComposeScene(
            width = 480,
            height = 1100,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        resolvedChartType = ChartType.Bar,
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = { config = VisualizationConfig() },
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            // Switch Bar -> Histogram: promotes amount to X and shows the transition note.
            scene.click(scene.requireExact("Histogram").boundsInRoot.center)
            scene.settle()
            assertEquals("t0__amount", config.x?.column)
            assertNotNull(scene.findContaining("Moved amount to X for histogram."))
            assertNotNull(scene.findContaining("Auto bins"))

            // Expand Options: Histogram hides Sort and Top categories, shows Bin size and value
            // labels even while preview resolvedChartType is still stale Bar.
            scene.click(scene.requireContaining("Options").boundsInRoot.center)
            scene.settle()
            val binSizeField = scene.requireContaining("Bin size (blank = automatic)")
            assertNotNull(scene.findContaining("Show value labels"))
            assertNull(scene.findExact("Sort"))
            assertNull(scene.findExact("Top categories"))

            // Editing Bin size preserves any existing start and clearing it with no start resets
            // grouping to None instead of leaving an invalid NumberBin("").
            config =
                config.copy(
                    x = config.x?.copy(grouping = PivotGrouping.NumberBin("250", start = "17")),
                )
            scene.settle()
            val setText = binSizeField.config[SemanticsActions.SetText].action!!
            setText.invoke(AnnotatedString("500"))
            scene.settle()
            assertEquals(PivotGrouping.NumberBin("500", start = "17"), config.x?.grouping)

            config = config.copy(x = config.x?.copy(grouping = PivotGrouping.NumberBin("500")))
            scene.settle()
            setText.invoke(AnnotatedString(""))
            scene.settle()
            assertEquals(PivotGrouping.Exact, config.x?.grouping)

            // Re-trigger transition note via Bar -> Histogram, then toggle Show value labels to
            // verify it clears immediately and reverting does not resurrect it.
            config = barWithoutSeries
            scene.settle()
            scene.click(scene.requireExact("Histogram").boundsInRoot.center)
            scene.settle()
            assertNotNull(scene.findContaining("Moved amount to X for histogram."))
            scene.click(scene.requireContaining("Show value labels").boundsInRoot.center)
            scene.settle()
            assertNull(scene.findContaining("Moved amount to X for histogram."))
            scene.click(scene.requireContaining("Show value labels").boundsInRoot.center)
            scene.settle()
            assertNull(scene.findContaining("Moved amount to X for histogram."))

            // Switch to Line: shows Sort, hides Top categories and Show value labels.
            scene.click(scene.requireExact("Line").boundsInRoot.center)
            scene.settle()
            assertNotNull(scene.findExact("Sort"))
            assertNull(scene.findExact("Top categories"))
            assertNull(scene.findContaining("Show value labels"))
            assertNotNull(scene.findContaining("value sort"))
        }
    }

    @Test
    fun visualizationSidebarShowsFullSubtitleAtRailWidthAndOmitsPinnedFilterTag() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows = listOf(listOf(ResultCell.text("Services"), ResultCell.float(100.0))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        var config by
            mutableStateOf(
                VisualizationConfig(
                    values = listOf(VisualizationMeasure.countRows()),
                    filters =
                        listOf(
                            PivotFilter.Members(
                                id = "legacy-pinned",
                                column = "t0__amount",
                                label = "amount",
                                pinned = true,
                            ),
                        ),
                ),
            )

        withComposeScene(
            width = 320,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = VisualizationConfig(),
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        onConfigChange = {},
                        onApplyTemplate = {},
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            val subtitle = scene.requireExact("Start with a suggestion or choose fields")
            val templates = scene.requireExact("Templates")
            assertTrue(subtitle.boundsInRoot.width > 180f)
            assertTrue(subtitle.boundsInRoot.top >= templates.boundsInRoot.bottom - 1f)
        }

        withComposeScene(
            width = 320,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            // Pre-existing pinned=true filter does not show "Pinned" in Visualization.
            assertNotNull(scene.findContaining("All values"))
            assertNull(scene.findContaining("Pinned"))

            // Newly added Visualization filter defaults to pinned=false.
            scene.click(scene.requireContaining("Add filter").boundsInRoot.center)
            scene.settle()
            scene.click(scene.requireContaining("category").boundsInRoot.center)
            scene.settle()

            val added = config.filters.last() as PivotFilter.Members
            assertEquals("t0__category", added.column)
            assertFalse(added.pinned)
            assertNull(scene.findContaining("Pinned"))
        }
    }

    @Test
    fun visualizationPaneRendersAllWarningsWhenMultipleWarningsCoOccur() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(ResultCell.text("Services"), ResultCell.float(3_600_000.0)),
                        listOf(ResultCell.text("Hardware"), ResultCell.float(1_200_000.0)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val spec =
            QuerySpec(
                tables = listOf(TableRef("public", "orders", "t0")),
                columns = emptyList(),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
            )
        val connection =
            ConnectionDef(
                id = "c1",
                name = "Local",
                dialect = Dialect.Postgres,
                host = "localhost",
                port = 5432,
                database = "app",
                username = "reader",
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val viewModel = ExploreViewModel(createExploreSession(connection, spec, sample))
            viewModel.selectMode(ExploreMode.Visualization)
            viewModel.updateVisualization {
                VisualizationConfig(
                    chartType = ChartType.Line,
                    x = VisualizationField("t0__category", "Category"),
                    values =
                        listOf(
                            VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount", "Sum amount"),
                            VisualizationMeasure.countRows(),
                        ),
                )
            }
            val expectedWarnings = viewModel.visualizationPreview.warnings
            assertEquals(2, expectedWarnings.size)

            withComposeScene(
                width = 1120,
                height = 760,
                content = {
                    SafeDbTheme(isDark = false) {
                        ExploreWindowContent(
                            viewModel = viewModel,
                            currentSpec = spec,
                            onClose = {},
                            recipesViewModel = RecipesViewModel(FakeSafeDbServiceSupport(), scope),
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                expectedWarnings.forEach { warning -> assertNotNull(scene.findContaining(warning)) }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun chartSwitchMemoryClearsStaleValuesAndSeriesOnExplicitUiSelection() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                        ResultColumn("t0__qty", "integer"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.text("East"),
                            ResultCell.float(100.0),
                            ResultCell.integer(2),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        val sumAmount =
            VisualizationMeasure("sum_amount", MeasureFn.Sum, "t0__amount", "Sum amount")
        val countRows = VisualizationMeasure.countRows("count_rows")
        var config by
            mutableStateOf(
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("t0__category", "category"),
                    values = listOf(sumAmount, countRows),
                ),
            )

        withComposeScene(
            width = 480,
            height = 1200,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            var frameNanos = 0L
            fun advance() {
                repeat(4) {
                    frameNanos += 100_000_000L
                    scene.render(frameNanos)
                }
            }

            // Bar([Sum amount, Count rows]) -> Scatter stashes both measures; selecting Series
            // clears stashed values so returning to Bar keeps 1 measure and removing Series does
            // not resurrect Count rows on Line.
            scene.click(scene.requireExact("Scatter").boundsInRoot.center)
            advance()
            assertEquals(listOf("sum_amount"), config.values.map { it.alias })

            scene.click(scene.requireContaining("Add series").boundsInRoot.center)
            advance()
            scene.click(scene.requireContaining("region").boundsInRoot.center)
            advance()
            assertEquals("t0__region", config.series?.column)

            scene.click(scene.requireExact("Bar").boundsInRoot.center)
            advance()
            assertEquals(listOf("sum_amount"), config.values.map { it.alias })
            assertEquals("t0__region", config.series?.column)

            // Removing Series and switching to Line must not resurrect Count rows from memory.
            scene.click(scene.requireExact("Remove region").boundsInRoot.center)
            advance()
            assertNull(config.series)
            scene.click(scene.requireExact("Line").boundsInRoot.center)
            advance()
            assertEquals(listOf("sum_amount"), config.values.map { it.alias })

            // Re-add Series, add a 2nd value (dropping Series), then round-trip through Histogram
            // -> Bar to verify both measures stay and Series does not resurrect.
            scene.click(scene.requireContaining("Add series").boundsInRoot.center)
            advance()
            scene.click(scene.requireContaining("region").boundsInRoot.center)
            advance()
            assertEquals("t0__region", config.series?.column)

            scene.click(scene.requireContaining("Add value").boundsInRoot.center)
            advance()
            scene.click(scene.requireContaining("Count rows").boundsInRoot.center)
            advance()
            assertEquals(2, config.values.size)
            assertNull(config.series)

            scene.click(scene.requireExact("Histogram").boundsInRoot.center)
            advance()
            assertEquals(emptyList(), config.values)

            scene.click(scene.requireExact("Bar").boundsInRoot.center)
            advance()
            assertEquals(2, config.values.size)
            assertNull(config.series)
        }
    }
}
