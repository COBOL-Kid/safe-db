package com.safedb.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelDiscardedHistoryTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun discardedInFlightSqlRunDoesNotAppearInHistory() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(viewModel.sqlEditor.running)

                viewModel.sqlEditor.onTextChanged(TextFieldValue("SELECT id FROM users LIMIT 5"))
                assertNotNull(viewModel.sqlEditor.discardedRunNotice)

                gate.complete(Unit)
                advanceUntilIdle()

                assertFalse(viewModel.sqlEditor.running)
                assertTrue(viewModel.history.entries.value.isEmpty())
                assertTrue(service.recordedHistory.isEmpty())

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                assertEquals(listOf("h1"), viewModel.history.entries.value.map { it.id })
                assertNull(viewModel.history.entries.value.single().error)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun discardedInFlightBuilderRunDoesNotAppearInHistory() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.query.addTable(sampleTable())

                viewModel.query.run("c1")
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(viewModel.query.running)

                viewModel.query.setLimit(50)
                assertNotNull(viewModel.query.discardedRunNotice)

                gate.complete(Unit)
                advanceUntilIdle()

                assertFalse(viewModel.query.running)
                assertTrue(viewModel.history.entries.value.isEmpty())
                assertTrue(service.recordedHistory.isEmpty())
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun discardedInFlightFailureDoesNotAppearInHistory() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service =
                FakeSafeDbService(
                    queryGate = gate,
                    queryStarted = started,
                    executionError = "An I/O error occurred while sending to the backend.",
                )
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                runCurrent()
                assertTrue(viewModel.sqlEditor.running)

                viewModel.sqlEditor.onTextChanged(TextFieldValue("SELECT 1"))
                gate.complete(Unit)
                advanceUntilIdle()

                assertFalse(viewModel.sqlEditor.running)
                assertNull(viewModel.sqlEditor.error)
                assertTrue(viewModel.history.entries.value.isEmpty())
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun connectionSwitchDiscardsInFlightRunFromHistory() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.query.addTable(sampleTable())

                viewModel.query.run("c1")
                runCurrent()
                assertTrue(viewModel.query.running)

                viewModel.query.onActiveConnectionChanged("c2")
                assertTrue(viewModel.query.running)

                gate.complete(Unit)
                advanceUntilIdle()

                assertFalse(viewModel.query.running)
                assertTrue(viewModel.history.entries.value.isEmpty())
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun genuineFailedRunStillAppearsInHistory() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(executionError = "Query timed out after 10000ms")
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                advanceUntilIdle()

                assertEquals(listOf("h1"), viewModel.history.entries.value.map { it.id })
                assertEquals(
                    "Query timed out after 10000ms",
                    viewModel.history.entries.value.single().error,
                )
            } finally {
                viewModel.close()
            }
        }
}
