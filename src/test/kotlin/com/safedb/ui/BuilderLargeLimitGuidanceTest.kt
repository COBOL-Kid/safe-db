package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
    fun composedGuidanceStripDoesNotOverlapQueryOptions() {
        listOf(500, 800, 1100, 1400).forEach { width ->
            var bannerLeft = 0f
            var bannerRight = 0f
            var bannerBottom = 0f
            var filterLeft = 0f
            var filterTop = 0f
            var optionsLeft = 0f
            ImageComposeScene(width = width, height = 240, density = Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(
                                        top = QueryControlsVerticalPadding,
                                        end = QueryOptionsPanelEndPadding,
                                    )
                                    .width(QueryOptionsPanelMaxWidth)
                                    .height(80.dp)
                                    .onGloballyPositioned { coords ->
                                        optionsLeft = coords.positionInRoot().x
                                    }
                        )
                        Column(
                            modifier =
                                Modifier.align(Alignment.TopStart)
                                    .padding(
                                        start = QueryControlsStartPadding,
                                        top = QueryControlsVerticalPadding,
                                        end = QueryOptionsOverlayEndPadding,
                                    )
                                    .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(QueryStartOverlayGap),
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth().height(36.dp).onGloballyPositioned {
                                        coords ->
                                        bannerLeft = coords.positionInRoot().x
                                        bannerRight =
                                            coords.positionInRoot().x + coords.size.width.toFloat()
                                        bannerBottom =
                                            coords.positionInRoot().y + coords.size.height.toFloat()
                                    }
                            )
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth().height(80.dp).onGloballyPositioned {
                                        coords ->
                                        filterLeft = coords.positionInRoot().x
                                        filterTop = coords.positionInRoot().y
                                    }
                            )
                        }
                    }
                }
                .use { scene ->
                    scene.render(0L)
                    assertEquals(
                        bannerLeft,
                        filterLeft,
                        0.01f,
                        "At ${width}px, banner and filters should share TopStart padding",
                    )
                    assertTrue(
                        bannerBottom <= filterTop,
                        "At ${width}px, banner bottom $bannerBottom overlaps filters at $filterTop",
                    )
                    assertTrue(
                        bannerRight <= optionsLeft,
                        "At ${width}px, banner [$bannerLeft, $bannerRight] overlaps options at $optionsLeft",
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
                val banner = scene.requireNode(LARGE_LIMIT_GUIDANCE_TEXT)
                val filters = scene.requireNode("Query filters")
                assertEquals(
                    banner.boundsInRoot.left,
                    filters.boundsInRoot.left,
                    0.5f,
                    "Banner and filters should share the TopStart overlay column",
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
                assertNotNull(scene.findNode(LARGE_LIMIT_GUIDANCE_TEXT))
                val maximize = scene.requireNode("Maximize results")
                scene.click(maximize.boundsInRoot.center)
                scene.render(50_000_000L)
                assertNull(
                    scene.findNode(LARGE_LIMIT_GUIDANCE_TEXT),
                    "Guidance banner should not remain as a sibling of maximized results",
                )
                assertNotNull(scene.findNode("Minimize results"))
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

private fun ImageComposeScene.findNode(matching: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label().contains(matching) }

private fun ImageComposeScene.requireNode(matching: String): SemanticsNode =
    findNode(matching)
        ?: error(
            "No node containing '$matching' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

private fun ImageComposeScene.allSemanticsNodes(): List<SemanticsNode> {
    val nodes = mutableListOf<SemanticsNode>()
    for (owner in semanticsOwners) {
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk(owner.rootSemanticsNode)
    }
    return nodes
}

private fun SemanticsNode.label(): String {
    val texts =
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString { it.text }
        } else {
            ""
        }
    val descriptions =
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].joinToString()
        } else {
            ""
        }
    return listOf(texts, descriptions).filter { it.isNotEmpty() }.joinToString(" | ")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.click(position: Offset) {
    sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
    sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
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
                )
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
