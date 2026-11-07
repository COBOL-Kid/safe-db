package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.AppRoute
import com.safedb.model.ColumnInfo
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryRunRequest
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.queryRunResult
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryScreenTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun historyScreenShowsRecentEntriesAndActions() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                assertNotNull(scene.findExact("History"))
                assertNotNull(scene.findExact("Your recent and saved queries."))
                assertNotNull(scene.findExact("Clear History"))
                assertNotNull(scene.findExact("Production Replica"))
                assertNotNull(scene.findExact("Local MySQL"))
                assertNotNull(scene.findContaining("87 rows"))
                assertNotNull(scene.findExact("failed"))
                assertNotNull(scene.findContaining("orders · limit 100"))
                assertNotNull(scene.findContaining("Join on non-indexed column rejected"))
                assertEquals(2, scene.nodesLabeled("Delete history entry").size)
                assertEquals(1, scene.nodesLabeled("Save as query").size)
                assertEquals(2, scene.nodesLabeled("Rerun").size)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun emptyHistoryShowsEmptyStateAndHidesClear() {
        val harness = HistoryScreenHarness(entries = mutableListOf())
        var navigated: AppRoute? = null
        try {
            withHistoryScreen(harness.viewModel, onNavigate = { navigated = it }) { scene ->
                assertNotNull(scene.findExact("No query history yet"))
                assertNotNull(scene.findExact("Run your first query to see it here."))
                assertNull(scene.findExact("Clear History"))
                assertNull(scene.findExact("Delete history entry"))
                scene.click(scene.requireExact("Build a Query").boundsInRoot.center)
                scene.render(50_000_000L)
                assertEquals(AppRoute.Builder, navigated)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun confirmingDeleteRemovesOnlyThatHistoryEntry() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.clickActionNear("Production Replica", "Delete history entry")
                scene.render(50_000_000L)
                assertNotNull(scene.findExact("Delete history entry?"))
                assertNotNull(
                    scene.findExact(
                        "This query will be removed from history. This cannot be undone."
                    )
                )
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.render(50_000_000L)

                assertNull(scene.findExact("Production Replica"))
                assertNotNull(scene.findExact("Local MySQL"))
                assertEquals(listOf("h1"), harness.service.deletedIds)
                assertEquals(listOf("h2"), harness.viewModel.history.entries.value.map { it.id })
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun cancelingDeleteLeavesHistoryUnchanged() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.clickActionNear("Production Replica", "Delete history entry")
                scene.render(50_000_000L)
                scene.click(scene.requireExact("Cancel").boundsInRoot.center)
                scene.render(50_000_000L)

                assertNull(scene.findExact("Delete history entry?"))
                assertNotNull(scene.findExact("Production Replica"))
                assertNotNull(scene.findExact("Local MySQL"))
                assertTrue(harness.service.deletedIds.isEmpty())
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun failedEntryCanBeDeletedAndHasNoSave() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                assertEquals(1, scene.nodesLabeled("Save as query").size)
                scene.clickActionNear("Local MySQL", "Delete history entry")
                scene.render(50_000_000L)
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.render(50_000_000L)

                assertNull(scene.findExact("Local MySQL"))
                assertNotNull(scene.findExact("Production Replica"))
                assertNotNull(scene.findExact("Save as query"))
                assertEquals(listOf("h2"), harness.service.deletedIds)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun rerunInvokesCallbackForThatEntry() {
        val harness = HistoryScreenHarness()
        var rerun: HistoryEntry? = null
        try {
            withHistoryScreen(harness.viewModel, onRerun = { rerun = it }) { scene ->
                scene.clickActionNear("Production Replica", "Rerun")
                scene.render(50_000_000L)
                assertEquals("h1", rerun?.id)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun saveAsQueryOpensPromptAndPersists() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.click(scene.requireExact("Save as query").boundsInRoot.center)
                scene.render(50_000_000L)
                assertNotNull(scene.findExact("Save query"))
                assertNotNull(scene.findExact("Choose a name for this query."))
                scene.click(scene.requireExact("Save").boundsInRoot.center)
                scene.render(50_000_000L)

                assertNull(scene.findExact("Save query"))
                assertEquals(
                    listOf("Production Replica query"),
                    harness.service.saved.map { it.name },
                )
                assertEquals("c1", harness.service.saved.single().connectionId)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun historyScreenShowsQueryCompletedDuringThisSession() {
        val harness = HistoryScreenHarness(entries = mutableListOf())
        try {
            assertTrue(harness.viewModel.history.entries.value.isEmpty())
            harness.viewModel.query.addTable(
                TableInfo(
                    schema = "public",
                    name = "customers",
                    columns =
                        listOf(
                            ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                            ColumnInfo("email", "text", nullable = true),
                        ),
                    indexes = emptyList(),
                )
            )
            harness.viewModel.query.run("c1")
            withHistoryScreen(harness.viewModel) { scene ->
                assertNotNull(scene.findExact("Local MySQL"))
                assertNotNull(scene.findContaining("3 rows"))
                assertNull(scene.findExact("No query history yet"))
                assertEquals(1, harness.viewModel.history.entries.value.size)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun confirmingClearHistoryRemovesAllEntries() {
        val harness = HistoryScreenHarness()
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.click(scene.requireExact("Clear History").boundsInRoot.center)
                scene.render(50_000_000L)
                assertNotNull(scene.findExact("Clear history?"))
                scene.click(scene.requireExact("Clear").boundsInRoot.center)
                scene.render(50_000_000L)

                assertNotNull(scene.findExact("No query history yet"))
                assertNull(scene.findExact("Clear History"))
                assertTrue(harness.viewModel.history.entries.value.isEmpty())
                assertTrue(harness.service.historyCleared)
            }
        } finally {
            harness.viewModel.close()
        }
    }
}

private class HistoryScreenHarness(entries: MutableList<HistoryEntry> = defaultHistoryEntries()) {
    val service = HistoryScreenService(entries)
    val viewModel = AppViewModel(service, Dispatchers.Unconfined)
}

private class HistoryScreenService(private val entries: MutableList<HistoryEntry>) :
    FakeSafeDbServiceSupport() {
    val deletedIds = mutableListOf<String>()
    val saved = mutableListOf<SavedQuery>()
    var historyCleared = false

    override suspend fun listHistory() = if (historyCleared) emptyList() else entries.toList()

    override suspend fun deleteHistoryEntry(id: String) {
        deletedIds += id
        entries.removeAll { it.id == id }
    }

    override suspend fun clearHistory() {
        historyCleared = true
        entries.clear()
    }

    override suspend fun saveSavedQuery(query: SavedQuery) {
        saved += query
    }

    override suspend fun listSavedQueries() = saved.toList()

    override suspend fun runQuery(request: QueryRunRequest) =
        queryRunResult(
                QueryResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    rowCount = 3,
                    truncated = false,
                    warnings = emptyList(),
                )
            )
            .also {
                entries.add(
                    0,
                    HistoryEntry(
                        id = "run-${entries.size + 1}",
                        connectionId = request.connectionId,
                        connectionName = "Local MySQL",
                        spec = request.spec,
                        rowCount = 3,
                        warnings = emptyList(),
                        timestamp = "1",
                    ),
                )
            }
}

private fun withHistoryScreen(
    viewModel: AppViewModel,
    onRerun: (HistoryEntry) -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
    body: (ImageComposeScene) -> Unit,
) {
    ImageComposeScene(width = 1000, height = 900, density = Density(1f)) {
            SafeDbTheme(isDark = false) {
                HistoryScreen(
                    viewModel = viewModel,
                    onRerun = onRerun,
                    onNavigate = onNavigate,
                )
            }
        }
        .use { scene ->
            scene.render(0L)
            body(scene)
        }
}

private fun ImageComposeScene.clickActionNear(anchorText: String, actionLabel: String) {
    val anchor = requireContaining(anchorText)
    val action =
        nodesLabeled(actionLabel).minBy {
            abs(it.boundsInRoot.center.y - anchor.boundsInRoot.center.y)
        }
    click(action.boundsInRoot.center)
}

private fun ImageComposeScene.findContaining(text: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label().contains(text) }

private fun ImageComposeScene.requireContaining(text: String): SemanticsNode =
    findContaining(text)
        ?: error(
            "No node containing '$text' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

private fun ImageComposeScene.findExact(label: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label() == label }

private fun ImageComposeScene.requireExact(label: String): SemanticsNode =
    findExact(label)
        ?: error(
            "No node labeled '$label' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

private fun ImageComposeScene.nodesLabeled(label: String): List<SemanticsNode> =
    allSemanticsNodes().filter { it.label() == label }

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

private fun defaultHistoryEntries() =
    mutableListOf(
        HistoryEntry(
            id = "h1",
            connectionId = "c1",
            connectionName = "Production Replica",
            spec = historySpec(),
            rowCount = 87,
            warnings = emptyList(),
            timestamp = "1",
        ),
        HistoryEntry(
            id = "h2",
            connectionId = "c2",
            connectionName = "Local MySQL",
            spec = historySpec(),
            rowCount = 0,
            warnings = emptyList(),
            error = "Join on non-indexed column rejected",
            timestamp = "2",
        ),
    )

private fun historySpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )
