package com.safedb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskGateState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryRunRequest
import com.safedb.service.QueryRunResult
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class BuilderLargeLimitGuidanceTest {
    @Test
    fun guidanceCopyIsOneShortLine() {
        assertEquals(
            "Large limit: add filters or pick columns to keep queries fast.",
            LARGE_LIMIT_GUIDANCE_TEXT,
        )
        assertFalse(LARGE_LIMIT_GUIDANCE_TEXT.contains('\n'))
    }

    @Test
    fun queryOptionsButtonStaysOutsideTheFilterPanel() {
        listOf(960, 1400).forEach { width ->
            val harness = GuidanceHarness()
            harness.queryViewModel.addTable(ordersTable)
            harness.queryViewModel.setUncapped(true)
            harness.queryViewModel.addFilterForColumn("t0", "customer_id", "bigint")

            ImageComposeScene(width = width, height = 600, density = Density(1f)) {
                    SafeDbTheme(isDark = false) { harness.Content() }
                }
                .use { scene ->
                    scene.render(0L)
                    val banner = scene.requireContaining(LARGE_LIMIT_GUIDANCE_TEXT)
                    val filters = scene.requireContaining("Query filters")
                    val options = scene.requireByTag("builder-query-options")
                    assertTrue(
                        banner.boundsInRoot.bottom <= filters.boundsInRoot.top + 0.5f,
                        "At ${width}px, banner ${banner.boundsInRoot} overlaps filters ${filters.boundsInRoot}",
                    )
                    assertTrue(
                        filters.boundsInRoot.bottom <= options.boundsInRoot.top + 0.5f,
                        "At ${width}px, Query options ${options.boundsInRoot} was inside the filter panel ${filters.boundsInRoot}",
                    )
                    assertTrue(
                        options.isInsideWindow(width, 600),
                        "At ${width}px, Query options was not reachable: ${options.boundsInRoot}",
                    )
                }
        }
    }

    @Test
    fun builderScreenStacksGuidanceAboveFilters() {
        val harness = GuidanceHarness()
        harness.queryViewModel.addTable(ordersTable)
        harness.queryViewModel.setUncapped(true)
        harness.queryViewModel.addFilterForColumn("t0", "customer_id", "bigint")

        ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) { harness.Content() }
            }
            .use { scene ->
                scene.render(0L)
                val banner = scene.requireContaining(LARGE_LIMIT_GUIDANCE_TEXT)
                val filters = scene.requireContaining("Query filters")
                assertEquals(
                    banner.boundsInRoot.left,
                    filters.boundsInRoot.left,
                    0.5f,
                    "Banner and filters should share the reserved chrome column",
                )
                assertTrue(
                    banner.boundsInRoot.bottom <= filters.boundsInRoot.top + 0.5f,
                    "Banner ${banner.boundsInRoot} should stack above filters ${filters.boundsInRoot}",
                )
            }
    }

    @Test
    fun builderScreenHidesGuidanceWhenResultsAreMaximized() {
        val harness = GuidanceHarness()
        harness.queryViewModel.addTable(ordersTable)
        harness.queryViewModel.setUncapped(true)
        harness.queryViewModel.run("c1")

        ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) { harness.Content() }
            }
            .use { scene ->
                scene.render(0L)
                assertNotNull(scene.findContaining(LARGE_LIMIT_GUIDANCE_TEXT))
                val maximize = scene.requireContaining("Maximize results")
                scene.click(maximize.boundsInRoot.center)
                scene.settle()
                assertNull(
                    scene.findContaining(LARGE_LIMIT_GUIDANCE_TEXT),
                    "Guidance banner should not remain as a sibling of maximized results",
                )
                assertNotNull(scene.findContaining("Minimize results"))
            }
    }
}

private class GuidanceHarness {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
    val queryViewModel = QueryViewModel(GuidanceService, scope)
    val schemaViewModel = SchemaViewModel(GuidanceService, scope)

    init {
        schemaViewModel.load("c1", selection = selection)
    }

    @Composable
    fun Content() {
        BuilderScreen(
            connection = connectionDef,
            connections = listOf(connectionDef),
            queryViewModel = queryViewModel,
            savedQueriesViewModel = SavedQueriesViewModel(GuidanceService, scope),
            recipesViewModel = RecipesViewModel(GuidanceService, scope),
            schemaViewModel = schemaViewModel,
            schemaSelection = selection,
            schemaHistoryError = null,
            settings = Settings(),
            sqlBusy = false,
            onConnectionSelected = {},
            onSchemaSelected = {},
            onUnavailableSchemaSelection = {},
            onDismissSchemaHistoryError = {},
            onOpenExplore = {},
            onOpenSettings = {},
            onApplyRecipe = { _, _ -> },
        )
    }
}

private val ordersTable =
    TableInfo(
        schema = "public",
        name = "orders",
        columns =
            listOf(
                ColumnInfo(
                    name = "customer_id",
                    dataType = "bigint",
                    nullable = false,
                    isIndexed = true,
                    joinEligible = true,
                ),
            ),
        indexes = emptyList(),
    )

private val connectionDef =
    ConnectionDef(
        id = "c1",
        name = "Test Connection",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "testdb",
        username = "readonly",
    )

private object GuidanceService : FakeSafeDbServiceSupport() {
    override suspend fun getSchema(connectionId: String) = Schema(listOf(ordersTable))

    override suspend fun runQuery(request: QueryRunRequest) =
        QueryRunResult(
            QueryResult(
                columns = listOf(ResultColumn("customer_id", "bigint")),
                rows = listOf(listOf(ResultCell.text("1"))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            ),
            QueryRiskEvaluation(
                staticAssessment = null,
                finalAssessment = null,
                planStatus = QueryPlanStatus.Disabled,
                decision =
                    QueryRiskDecision(
                        "",
                        RiskGateState.Allowed,
                        QueryRiskGate.Disabled,
                        null,
                        emptyList(),
                    ),
            ),
        )
}
