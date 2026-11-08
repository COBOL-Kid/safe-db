package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.SavedQuery
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
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
class HomeScreenSavedQueriesTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savedQueriesSectionShowsNamesAndDeleteControls() {
        val harness = HomeScreenHarness()
        try {
            withHomeScreen(harness.viewModel) { scene ->
                assertNotNull(scene.findExact("Saved Queries"))
                assertNotNull(scene.findContaining("Pending orders"))
                assertNotNull(scene.findContaining("Revenue last 30 days"))
                assertNotNull(scene.findContaining("Local MySQL · 1 table"))
                assertEquals(2, scene.nodesLabeled("Delete saved query").size)
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun savedQueriesSectionIsHiddenWhenEmpty() {
        val harness = HomeScreenHarness(queries = mutableListOf())
        try {
            withHomeScreen(harness.viewModel) { scene ->
                assertNull(scene.findExact("Saved Queries"))
                assertNull(scene.findExact("Delete saved query"))
                assertNotNull(scene.findExact("Welcome to Safe-DB"))
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun confirmingDeleteRemovesOnlyThatSavedQuery() {
        val harness = HomeScreenHarness()
        try {
            withHomeScreen(harness.viewModel) { scene ->
                scene.clickActionInTag("saved-query-q1", "Delete saved query")
                scene.settle()
                assertNotNull(scene.findExact("Delete saved query?"))
                assertNotNull(scene.findExact("This saved query will be permanently removed."))
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.settle()

                assertNull(scene.findContaining("Pending orders"))
                assertNotNull(scene.findContaining("Revenue last 30 days"))
                assertEquals(listOf("q1"), harness.service.deletedIds)
                assertEquals(
                    listOf("q2"),
                    harness.viewModel.savedQueries.queries.value.map { it.id },
                )
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun cancelingDeleteLeavesSavedQueriesUnchanged() {
        val harness = HomeScreenHarness()
        try {
            withHomeScreen(harness.viewModel) { scene ->
                scene.clickActionInTag("saved-query-q1", "Delete saved query")
                scene.settle()
                scene.click(scene.requireExact("Cancel").boundsInRoot.center)
                scene.settle()

                assertNull(scene.findExact("Delete saved query?"))
                assertNotNull(scene.findContaining("Pending orders"))
                assertNotNull(scene.findContaining("Revenue last 30 days"))
                assertTrue(harness.service.deletedIds.isEmpty())
            }
        } finally {
            harness.viewModel.close()
        }
    }

    @Test
    fun clickingSavedQueryNameOpensIt() {
        val harness = HomeScreenHarness()
        var opened: SavedQuery? = null
        try {
            withHomeScreen(harness.viewModel, onOpenSavedQuery = { opened = it }) { scene ->
                scene.click(scene.requireContaining("Pending orders").boundsInRoot.center)
                scene.settle()
                assertEquals("q1", opened?.id)
                assertTrue(harness.service.deletedIds.isEmpty())
            }
        } finally {
            harness.viewModel.close()
        }
    }
}

private class HomeScreenHarness(
    queries: MutableList<SavedQuery> =
        mutableListOf(
            SavedQuery("q1", "Pending orders", "c1", sampleQuerySpec(), "1"),
            SavedQuery("q2", "Revenue last 30 days", "c1", sampleQuerySpec(), "2"),
        )
) {
    val service = HomeScreenService(queries)
    val viewModel = AppViewModel(service, Dispatchers.Unconfined)
}

private class HomeScreenService(private val queries: MutableList<SavedQuery>) :
    FakeSafeDbServiceSupport() {
    val deletedIds = mutableListOf<String>()

    override suspend fun listConnections() = listOf(homeConnection)

    override suspend fun listSavedQueries() = queries.toList()

    override suspend fun deleteSavedQuery(id: String) {
        deletedIds += id
        queries.removeAll { it.id == id }
    }
}

private fun withHomeScreen(
    viewModel: AppViewModel,
    onOpenSavedQuery: (SavedQuery) -> Unit = {},
    body: (ImageComposeScene) -> Unit,
) {
    withComposeScene(
        width = 1000,
        height = 900,
        content = {
            SafeDbTheme(isDark = false) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigate = {},
                    onNewConnection = {},
                    onOpenSavedQuery = onOpenSavedQuery,
                )
            }
        },
        body = body,
    )
}

private val homeConnection =
    ConnectionDef(
        id = "c1",
        name = "Local MySQL",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "root",
    )
