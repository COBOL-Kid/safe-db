package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.safedb.AppRoute
import com.safedb.model.ColumnInfo
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.SavedQuery
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryRunRequest
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.util.formatTime
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.queryRunResult
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
                assertEquals(2, scene.nodesContaining("query from").size)
                assertNotNull(scene.findExact(deleteLabel("Production Replica", "1")))
                assertNotNull(scene.findExact(deleteLabel("Local MySQL", "2")))
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
                assertNull(scene.findContaining("query from"))
                scene.click(scene.requireExact("Build a Query").boundsInRoot.center)
                scene.settle()
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
                assertNotNull(scene.findExact("Production Replica"))
                scene.clickActionInTag("history-entry-h1", deleteLabel("Production Replica", "1"))
                scene.settle()
                assertNotNull(scene.findExact("Delete history entry?"))
                assertNotNull(
                    scene.findExact(
                        "This query will be removed from history. This cannot be undone."
                    )
                )
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.settle()

                assertNull(scene.findExact("Delete history entry?"))
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
    fun failedDeleteClosesConfirmationAndKeepsEntryVisible() {
        val harness = HistoryScreenHarness()
        harness.service.failDelete = true
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.clickActionInTag("history-entry-h1", deleteLabel("Production Replica", "1"))
                scene.settle()
                assertNotNull(scene.findExact("Delete history entry?"))

                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.settle()

                assertNull(scene.findExact("Delete history entry?"))
                assertNotNull(scene.findExact("Production Replica"))
                assertNotNull(scene.findContaining("history delete failed"))
                assertTrue(harness.service.deletedIds.isEmpty())
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun deleteKeepsConfirmationOpenUntilServiceSettles() {
        val harness = HistoryScreenHarness()
        val gate = CompletableDeferred<Unit>()
        harness.service.deleteGate = gate
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                scene.clickActionInTag("history-entry-h1", deleteLabel("Production Replica", "1"))
                scene.settle()
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.settle()

                assertNotNull(scene.findExact("Delete history entry?"))
                assertNotNull(scene.findExact("Production Replica"))
                assertTrue(harness.service.deletedIds.isEmpty())

                gate.complete(Unit)
                scene.settle()

                assertNull(scene.findExact("Delete history entry?"))
                assertNull(scene.findExact("Production Replica"))
                assertEquals(listOf("h1"), harness.service.deletedIds)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun deleteLabelsDistinguishRepeatedRunsOnOneConnection() {
        val earlier = Instant.parse("2020-06-15T12:00:00Z").epochSecond.toString()
        val later = Instant.parse("2022-06-15T12:00:00Z").epochSecond.toString()
        val entries =
            mutableListOf(
                historyEntry(id = "a", connectionName = "Local MySQL", timestamp = earlier),
                historyEntry(id = "b", connectionName = "Local MySQL", timestamp = later),
            )
        val harness = HistoryScreenHarness(entries = entries)
        try {
            withHistoryScreen(harness.viewModel) { scene ->
                val earlierLabel = deleteLabel("Local MySQL", earlier)
                val laterLabel = deleteLabel("Local MySQL", later)
                assertNotEquals(earlierLabel, laterLabel)
                assertEquals(2, scene.nodesContaining("Delete Local MySQL query from").size)
                assertEquals(1, scene.nodesLabeled(earlierLabel).size)
                assertEquals(1, scene.nodesLabeled(laterLabel).size)
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
                scene.clickActionInTag("history-entry-h1", deleteLabel("Production Replica", "1"))
                scene.settle()
                scene.click(scene.requireExact("Cancel").boundsInRoot.center)
                scene.settle()

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
                assertNotNull(scene.findExact("Local MySQL"))
                scene.clickActionInTag("history-entry-h2", deleteLabel("Local MySQL", "2"))
                scene.settle()
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.settle()

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
                scene.clickActionInTag("history-entry-h1", "Rerun")
                scene.settle()
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
                scene.settle()
                assertNotNull(scene.findExact("Save query"))
                assertNotNull(scene.findExact("Choose a name for this query."))
                scene.click(scene.requireExact("Save").boundsInRoot.center)
                scene.settle()

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
                scene.settle()
                assertNotNull(scene.findExact("Clear history?"))
                scene.click(scene.requireExact("Clear").boundsInRoot.center)
                scene.settle()

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
    var failDelete = false
    var deleteGate: CompletableDeferred<Unit>? = null

    override suspend fun listHistory() = if (historyCleared) emptyList() else entries.toList()

    override suspend fun deleteHistoryEntry(id: String) {
        deleteGate?.await()
        if (failDelete) error("history delete failed")
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
    withComposeScene(
        width = 1000,
        height = 900,
        content = {
            SafeDbTheme(isDark = false) {
                HistoryScreen(
                    viewModel = viewModel,
                    onRerun = onRerun,
                    onNavigate = onNavigate,
                )
            }
        },
        body = body,
    )
}

private fun deleteLabel(connectionName: String, timestamp: String) =
    "Delete $connectionName query from ${formatTime(timestamp)}"

private fun historyEntry(id: String, connectionName: String, timestamp: String) =
    HistoryEntry(
        id = id,
        connectionId = "c1",
        connectionName = connectionName,
        spec = sampleQuerySpec(),
        rowCount = 0,
        warnings = emptyList(),
        timestamp = timestamp,
    )

private fun defaultHistoryEntries() =
    mutableListOf(
        HistoryEntry(
            id = "h1",
            connectionId = "c1",
            connectionName = "Production Replica",
            spec = sampleQuerySpec(),
            rowCount = 87,
            warnings = emptyList(),
            timestamp = "1",
        ),
        HistoryEntry(
            id = "h2",
            connectionId = "c2",
            connectionName = "Local MySQL",
            spec = sampleQuerySpec(),
            rowCount = 0,
            warnings = emptyList(),
            error = "Join on non-indexed column rejected",
            timestamp = "2",
        ),
    )
