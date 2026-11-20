package com.safedb.viewmodel

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelReachabilityTest {
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
    fun refreshDatabaseReachabilityClearsPreviousSuccessAndSkipsNullConnection() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)

                viewModel.refreshDatabaseReachability(null)
                advanceTimeBy(ReachabilityPollInterval)
                runCurrent()

                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )
                assertEquals(1, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityBecomesConnectedAfterSuccessfulProbe() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()

                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)
                assertNull(service.testConnectionPassword)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityBecomesDisconnectedAfterFailedProbe() =
        runTest(dispatcher) {
            val service =
                FakeSafeDbService(
                    testConnectionOutcome = Result.failure(IllegalStateException("denied")),
                )
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()

                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )
                assertEquals(1, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityEntersCheckingOnConnectionSwitchBeforeResolving() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)

                val gate = CompletableDeferred<Unit>()
                service.testConnectionGate = gate
                service.testConnectionOutcome = Result.failure(IllegalStateException("unreachable"))

                viewModel.refreshDatabaseReachability(testConnection().copy(id = "c2"))
                assertEquals(DatabaseReachability.Checking, viewModel.databaseReachability.value)

                runCurrent()
                assertEquals(DatabaseReachability.Checking, viewModel.databaseReachability.value)
                assertEquals(2, service.testConnectionCalls)

                gate.complete(Unit)
                runCurrent()
                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityIgnoresResultFromReplacedConnection() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service =
                FakeSafeDbService(
                    testConnectionGate = gate,
                    testConnectionStarted = started,
                    testConnectionOutcome = Result.success("ok"),
                )
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertTrue(started.isCompleted)
                assertEquals(DatabaseReachability.Checking, viewModel.databaseReachability.value)

                viewModel.refreshDatabaseReachability(null)
                gate.complete(Unit)
                advanceTimeBy(ReachabilityPollInterval)
                runCurrent()

                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )
                assertEquals(1, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityPollsTheSameConnectionAgainWithoutFlashing() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)

                val gate = CompletableDeferred<Unit>()
                service.testConnectionGate = gate
                advanceTimeBy(ReachabilityPollInterval)
                runCurrent()

                assertEquals(2, service.testConnectionCalls)
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertNull(service.testConnectionPassword)

                gate.complete(Unit)
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshDatabaseReachabilityStaysConnectedUntilALaterFailedPollReturns() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)

                val gate = CompletableDeferred<Unit>()
                service.testConnectionGate = gate
                service.testConnectionOutcome = Result.failure(IllegalStateException("down"))

                advanceTimeBy(ReachabilityPollInterval)
                runCurrent()

                assertEquals(2, service.testConnectionCalls)
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)

                gate.complete(Unit)
                runCurrent()

                assertEquals(
                    DatabaseReachability.Disconnected,
                    viewModel.databaseReachability.value,
                )
                assertEquals(2, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun inactiveWindowSkipsReachabilityProbesAndResumesOnReactivation() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)

                viewModel.setWindowActive(false)
                viewModel.setWindowActive(false)
                advanceTimeBy(ReachabilityPollInterval)
                runCurrent()

                assertEquals(1, service.testConnectionCalls)
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)

                viewModel.setWindowActive(true)
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                runCurrent()

                assertEquals(2, service.testConnectionCalls)
                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)

                // Redundant activation does not trigger an extra probe.
                viewModel.setWindowActive(true)
                runCurrent()
                assertEquals(2, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun connectionSwitchWhileWindowInactiveStaysCheckingUntilWindowActivates() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(testConnectionOutcome = Result.success("ok"))
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.setWindowActive(false)
                viewModel.refreshDatabaseReachability(testConnection())
                runCurrent()

                assertEquals(DatabaseReachability.Checking, viewModel.databaseReachability.value)
                assertEquals(0, service.testConnectionCalls)

                viewModel.setWindowActive(true)
                runCurrent()

                assertEquals(DatabaseReachability.Connected, viewModel.databaseReachability.value)
                assertEquals(1, service.testConnectionCalls)
            } finally {
                viewModel.close()
            }
        }
}
