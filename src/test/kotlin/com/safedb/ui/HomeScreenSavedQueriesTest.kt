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
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.TableRef
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
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
                scene.clickDeleteFor("Pending orders")
                scene.render(50_000_000L)
                assertNotNull(scene.findExact("Delete saved query?"))
                assertNotNull(scene.findExact("This saved query will be permanently removed."))
                scene.click(scene.requireExact("Delete").boundsInRoot.center)
                scene.render(50_000_000L)

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
                scene.clickDeleteFor("Pending orders")
                scene.render(50_000_000L)
                scene.click(scene.requireExact("Cancel").boundsInRoot.center)
                scene.render(50_000_000L)

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
                scene.render(50_000_000L)
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
            SavedQuery("q1", "Pending orders", "c1", sampleSavedSpec(), "1"),
            SavedQuery("q2", "Revenue last 30 days", "c1", sampleSavedSpec(), "2"),
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
    onNavigate: (AppRoute) -> Unit = {},
    body: (ImageComposeScene) -> Unit,
) {
    ImageComposeScene(width = 1000, height = 900, density = Density(1f)) {
            SafeDbTheme(isDark = false) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigate = onNavigate,
                    onNewConnection = {},
                    onOpenSavedQuery = onOpenSavedQuery,
                )
            }
        }
        .use { scene ->
            scene.render(0L)
            body(scene)
        }
}

private fun ImageComposeScene.clickDeleteFor(queryName: String) {
    val name = requireContaining(queryName)
    val delete =
        nodesLabeled("Delete saved query").minBy {
            abs(it.boundsInRoot.center.y - name.boundsInRoot.center.y)
        }
    click(delete.boundsInRoot.center)
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

private fun sampleSavedSpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )

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
