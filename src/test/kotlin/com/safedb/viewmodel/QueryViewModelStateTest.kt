package com.safedb.viewmodel

import androidx.compose.ui.geometry.Offset
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.query.QUERY_RISK_SCORE_VERSION
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.QueryRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class QueryViewModelStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun nestedFilterEditingPreservesIdsAndPrunesConnectorOverrides() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addFilter(filter("", "t0", "id"))
        viewModel.addGroupToGroup(emptyList(), GroupConnector.And)
        viewModel.addFilterToGroup(listOf(1), filter("", "t1", "customer_id"))

        val rootLeaf = (viewModel.filters.children[0] as FilterNode.Leaf).spec
        val group = (viewModel.filters.children[1] as FilterNode.Group).group
        val nestedLeaf = (group.children.single() as FilterNode.Leaf).spec
        assertTrue(rootLeaf.id.isNotEmpty())
        assertTrue(group.id.isNotEmpty())
        assertTrue(nestedLeaf.id.isNotEmpty())

        viewModel.updateFilter(listOf(0), rootLeaf.copy(column = "renamed"))
        assertEquals(rootLeaf.id, (viewModel.filters.children[0] as FilterNode.Leaf).spec.id)
        viewModel.setChildConnector(listOf(1), GroupConnector.Or)
        assertEquals(GroupConnector.Or, viewModel.connectorOverrides[group.id])

        viewModel.removeFilterNode(listOf(0))

        assertTrue(viewModel.connectorOverrides.isEmpty())
        assertEquals(group.id, (viewModel.filters.children.single() as FilterNode.Group).group.id)
    }

    @Test
    fun removingTableCascadesColumnsJoinsFiltersAndOverrides() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleColumn("t1", "customer_id")
        viewModel.addJoin(JoinSpec("t0", "id", "t1", "customer_id"))
        viewModel.addFilter(filter("first", "t1", "customer_id"))
        viewModel.addFilter(filter("second", "t0", "id"))
        viewModel.cycleSort("t0", "id")
        viewModel.cycleSort("t1", "customer_id")
        viewModel.toggleGroup("t0", "id")
        viewModel.setChildConnector(listOf(1), GroupConnector.Or)

        viewModel.removeTable("t0")

        assertEquals(listOf("t1"), viewModel.canvasTables.map { it.alias })
        assertEquals(listOf("t1\u0000customer_id"), viewModel.selectedColumns)
        assertTrue(viewModel.joins.isEmpty())
        assertEquals(
            listOf("first"),
            viewModel.filters.children.map { (it as FilterNode.Leaf).spec.id },
        )
        assertTrue(viewModel.connectorOverrides.isEmpty())
        assertEquals(listOf("t1"), viewModel.sorts.map { it.tableAlias })
        assertEquals(listOf("t1"), viewModel.groups.map { it.tableAlias })
    }

    @Test
    fun columnActionsTargetTheExactColumnAndCycleSortInStablePriorityOrder() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))

        viewModel.addFilterForColumn("t1", "customer_id", "int")
        val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
        assertEquals("t1", filter.tableAlias)
        assertEquals("customer_id", filter.column)
        assertEquals(filter.id, viewModel.requestedFilterFocusId)

        viewModel.addFilterForColumn("t0", "flag", "boolean")
        assertEquals(null, viewModel.requestedFilterFocusId)
        viewModel.addFilterForColumn("t0", "deleted_at", "timestamp", FilterOp.IsNull)
        assertEquals(null, viewModel.requestedFilterFocusId)

        viewModel.cycleSort("t1", "customer_id")
        viewModel.cycleSort("t0", "id")
        assertEquals(listOf("t1", "t0"), viewModel.sorts.map { it.tableAlias })
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t1", "customer_id")?.direction)

        viewModel.cycleSort("t1", "customer_id")
        assertEquals(SortDirection.Desc, viewModel.sortForColumn("t1", "customer_id")?.direction)
        viewModel.clearSort("t1", "customer_id")
        assertEquals(null, viewModel.sortForColumn("t1", "customer_id"))
        assertEquals(listOf("t0"), viewModel.sorts.map { it.tableAlias })
    }

    @Test
    fun tableColumnToggleSelectsPartialAndClearsOnlyTheTargetTable() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id", "name"))
        viewModel.addTable(table("orders", "id", "customer_id"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleColumn("t1", "customer_id")

        viewModel.toggleAllColumns("t0")

        assertEquals(
            listOf("t0\u0000id", "t1\u0000customer_id", "t0\u0000name"),
            viewModel.selectedColumns,
        )

        viewModel.toggleAllColumns("t0")

        assertEquals(listOf("t1\u0000customer_id"), viewModel.selectedColumns)
    }

    @Test
    fun tableColumnToggleUsesColumnRulesWhileGroupingIsActive() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t0", "id")

        viewModel.toggleAllColumns("t0")

        assertEquals(listOf("t0\u0000id", "t0\u0000status"), viewModel.selectedColumns)
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleAllColumns("t0")

        assertTrue(viewModel.selectedColumns.isEmpty())
        assertTrue(viewModel.groups.isEmpty())
    }

    @Test
    fun groupAndSortRowsCanBeReorderedIndependently() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status", "created_at"))
        viewModel.setGroups(
            listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status"), GroupSpec("t0", "created_at")),
        )
        viewModel.setSorts(
            listOf(
                SortSpec("t0", "id"),
                SortSpec("t0", "status", SortDirection.Desc),
                SortSpec("t0", "created_at"),
            ),
        )

        viewModel.moveGroup(2, 0)
        viewModel.moveSort(0, 2)

        assertEquals(listOf("created_at", "id", "status"), viewModel.spec.groups.map { it.column })
        assertEquals(listOf("status", "created_at", "id"), viewModel.spec.sorts.map { it.column })
        assertEquals(SortDirection.Desc, viewModel.spec.sorts.first().direction)
    }

    @Test
    fun distinctIsIncludedRestoredAndResetWithBuilderState() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id")
        viewModel.addTable(orders)
        viewModel.setDistinct(true)

        assertTrue(viewModel.distinct)
        assertTrue(viewModel.spec.distinct)

        viewModel.clear()

        assertFalse(viewModel.distinct)
        viewModel.restoreFromSpec(
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                filters = FilterGroup.empty(),
                limit = 100,
                distinct = true,
            ),
            listOf(orders),
        )
        assertTrue(viewModel.distinct)
        assertTrue(viewModel.spec.distinct)
    }

    @Test
    fun distinctSortConflictsDisableRunAndOfferExplicitRepairs() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status", "created_at"))
        viewModel.toggleColumn("t0", "id")
        viewModel.setSort("t0", "status", SortDirection.Asc)
        viewModel.setSort("t0", "created_at", SortDirection.Desc)
        viewModel.setDistinct(true)

        assertEquals(
            listOf("status", "created_at"),
            viewModel.distinctSortConflicts.map { it.column },
        )
        assertFalse(viewModel.canRun)

        viewModel.selectDistinctSortColumns()

        assertTrue(viewModel.isColumnSelected("t0", "status"))
        assertTrue(viewModel.isColumnSelected("t0", "created_at"))
        assertTrue(viewModel.distinctSortConflicts.isEmpty())
        assertTrue(viewModel.canRun)

        viewModel.toggleColumn("t0", "status")
        assertEquals(listOf("status"), viewModel.distinctSortConflicts.map { it.column })
        assertFalse(viewModel.canRun)

        viewModel.removeDistinctSortConflicts()

        assertEquals(listOf("created_at"), viewModel.sorts.map { it.column })
        assertTrue(viewModel.distinctSortConflicts.isEmpty())
        assertTrue(viewModel.canRun)
    }

    @Test
    fun groupActionsAppendByPriorityAutoSelectAndKeepGroupedBuilderStateValid() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))

        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t1", "customer_id")
        assertEquals(
            listOf(GroupSpec("t1", "customer_id"), GroupSpec("t0", "id")),
            viewModel.groups,
        )
        assertTrue(viewModel.isColumnSelected("t1", "customer_id"))

        viewModel.clearGroup("t1", "customer_id")
        viewModel.toggleGroup("t1", "customer_id")
        assertEquals(listOf("t0", "t1"), viewModel.groups.map { it.tableAlias })

        viewModel.cycleSort("t0", "id")
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t0", "id")?.direction)
    }

    @Test
    fun togglingGroupOffClearsAllGroupsAndKeepsSelectionsAndSorts() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status", "created_at"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleColumn("t0", "status")
        viewModel.cycleSort("t0", "id")

        viewModel.toggleGroup("t0", "created_at")

        assertEquals(
            listOf(
                GroupSpec("t0", "created_at"),
                GroupSpec("t0", "id"),
                GroupSpec("t0", "status"),
            ),
            viewModel.groups,
        )
        assertTrue(viewModel.isColumnSelected("t0", "created_at"))
        assertTrue(viewModel.isColumnSelected("t0", "id"))
        assertTrue(viewModel.isColumnSelected("t0", "status"))
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t0", "id")?.direction)

        viewModel.toggleGroup("t0", "id")

        assertTrue(viewModel.groups.isEmpty())
        assertEquals(
            listOf("t0\u0000id", "t0\u0000status", "t0\u0000created_at"),
            viewModel.selectedColumns,
        )
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t0", "id")?.direction)
    }

    @Test
    fun uncheckingAutoGroupedColumnRemovesItFromSelectionAndGrouping() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t0", "id")

        viewModel.toggleColumn("t0", "status")

        assertTrue(viewModel.isColumnSelected("t0", "status"))
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleColumn("t0", "status")

        assertFalse(viewModel.isColumnSelected("t0", "status"))
        assertEquals(listOf(GroupSpec("t0", "id")), viewModel.groups)
    }

    @Test
    fun restoringGroupOnlyColumnPreservesExplicitOutputsAndAllowsSelectingItLater() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id", "status")
        val savedSpec =
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                columns = listOf(ColumnSel("saved_orders", "id")),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
                groups =
                    listOf(GroupSpec("saved_orders", "id"), GroupSpec("saved_orders", "status")),
            )

        viewModel.restoreFromSpec(savedSpec, listOf(orders))

        assertEquals(listOf(ColumnSel("t0", "id")), viewModel.spec.columns)
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleColumn("t0", "status")

        assertTrue(ColumnSel("t0", "status") in viewModel.spec.columns)
    }

    @Test
    fun removingOnlySelectedRestoredGroupPromotesRemainingGroupToOutput() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id", "status")
        val savedSpec =
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                columns = listOf(ColumnSel("saved_orders", "id")),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
                groups =
                    listOf(GroupSpec("saved_orders", "id"), GroupSpec("saved_orders", "status")),
            )
        viewModel.restoreFromSpec(savedSpec, listOf(orders))

        viewModel.clearGroup("t0", "id")

        assertEquals(listOf(GroupSpec("t0", "status")), viewModel.groups)
        assertEquals(listOf(ColumnSel("t0", "status")), viewModel.spec.columns)
    }

    @Test
    fun limitsAndClearRestoreStableDefaults() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        assertEquals(500, viewModel.limit)
        assertFalse(viewModel.uncapped)

        viewModel.addTable(table("customers", "id"))
        viewModel.setLimit(0)
        assertEquals(500, viewModel.limit)
        assertFalse(viewModel.uncapped)
        viewModel.setLimit(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, viewModel.limit)
        assertFalse(viewModel.uncapped)
        assertFalse(viewModel.spec.uncapped)
        viewModel.setUncapped(true)
        assertEquals(Int.MAX_VALUE, viewModel.limit)
        assertTrue(viewModel.uncapped)
        assertTrue(viewModel.spec.uncapped)
        viewModel.setLimit(20_000)
        assertEquals(20_000, viewModel.limit)
        assertFalse(viewModel.uncapped)
        viewModel.setUncapped(true)
        assertEquals(20_000, viewModel.limit)
        assertTrue(viewModel.spec.uncapped)

        viewModel.clear()

        assertEquals(500, viewModel.limit)
        assertFalse(viewModel.uncapped)
        assertEquals(0, viewModel.tableCount)
        assertTrue(viewModel.filters.id.isNotEmpty())
        assertNotEquals("", viewModel.filters.id)

        viewModel.restoreFromSpec(
            QuerySpec(
                tables = listOf(TableRef("app", "customers", "saved_customers")),
                filters = FilterGroup.empty(),
                limit = 100,
            ),
            listOf(table("customers", "id")),
        )

        assertEquals(100, viewModel.limit)
        assertFalse(viewModel.uncapped)

        viewModel.restoreFromSpec(
            QuerySpec(
                tables = listOf(TableRef("app", "customers", "saved_customers")),
                filters = FilterGroup.empty(),
                limit = 100,
                uncapped = true,
            ),
            listOf(table("customers", "id")),
        )

        assertEquals(100, viewModel.limit)
        assertTrue(viewModel.uncapped)
        assertTrue(viewModel.spec.uncapped)
    }

    @Test
    fun newTablesUseCanvasLocalCoordinates() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))

        viewModel.addTable(table("orders", "id"))
        viewModel.addTable(table("customers", "id"))

        assertEquals(0f, viewModel.canvasTables[0].y)
        assertEquals(30f, viewModel.canvasTables[1].y)
    }

    @Test
    fun clearingTheDraftAlsoResetsItsSessionViewport() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id"))
        viewModel.canvasViewport.setZoom(1.5f, Offset(400f, 300f))
        viewModel.canvasViewport.updatePan(Offset(-200f, -120f))

        viewModel.clear()

        assertEquals(1f, viewModel.canvasViewport.zoom)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
        assertTrue(viewModel.canvasTables.isEmpty())
    }

    @Test
    fun removingTheLastTableResetsTheViewportForTheNextDraft() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id"))
        val alias = viewModel.canvasTables.single().alias
        viewModel.canvasViewport.setZoom(1.5f, Offset(400f, 300f))
        viewModel.canvasViewport.updatePan(Offset(-1_200f, -900f))

        viewModel.removeTable(alias)

        assertTrue(viewModel.canvasTables.isEmpty())
        assertEquals(1f, viewModel.canvasViewport.zoom)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)

        viewModel.addTable(table("customers", "id"))

        assertEquals(40f, viewModel.canvasTables.single().x)
        assertEquals(0f, viewModel.canvasTables.single().y)
        assertEquals(1f, viewModel.canvasViewport.zoom)
        assertEquals(Offset.Zero, viewModel.canvasViewport.pan)
    }

    @Test
    fun clearReusesTheFirstTableAliasSoReaddingDoesNotLeaveAHiddenDuplicate() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("big_events", "id"))
        viewModel.toggleColumn("t0", "id")
        assertEquals(listOf("t0"), viewModel.canvasTables.map { it.alias })

        viewModel.clear()
        viewModel.addTable(table("big_events", "id"))

        assertEquals(listOf("t0"), viewModel.canvasTables.map { it.alias })
        assertTrue(viewModel.selectedColumns.isEmpty())
        assertEquals(listOf("big_events"), viewModel.spec.tables.map { it.name })
    }

    @Test
    fun selectedColumnsKeepClickOrderInTheQuerySpec() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id", "email", "name"))
        viewModel.toggleColumn("t0", "email")
        viewModel.toggleColumn("t0", "name")
        viewModel.toggleColumn("t0", "id")

        assertEquals(listOf("email", "name", "id"), viewModel.spec.columns.map { it.column })
    }

    @Test
    fun changingOnlyTheRowLimitKeepsResultsMarkedStale() {
        val scope = TestScope(dispatcher)
        val viewModel = QueryViewModel(NoOpService(), scope)
        viewModel.addTable(table("customers", "id"))
        viewModel.run("c1")
        scope.advanceUntilIdle()

        val fresh = viewModel.currentSample("c1")
        assertNotNull(fresh)
        assertFalse(fresh.stale)

        viewModel.setLimit(1_000)
        val staleLimit = viewModel.currentSample("c1")
        assertNotNull(staleLimit)
        assertTrue(staleLimit.stale)

        viewModel.setUncapped(true)
        val staleAll = viewModel.currentSample("c1")
        assertNotNull(staleAll)
        assertTrue(staleAll.stale)

        viewModel.toggleColumn("t0", "id")
        assertNull(viewModel.currentSample("c1"))
    }

    @Test
    fun querySampleIsBoundToItsExecutedConnectionAndSpec() =
        runTest(dispatcher) {
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(NoOpService(), scope)
            query.addTable(sampleTable())

            query.run("c1")
            scope.advanceUntilIdle()

            assertEquals("c1", query.currentSample("c1")?.connectionId)
            assertNull(query.currentSample("c2"))

            query.toggleColumn("t0", "name")

            assertNull(query.currentSample("c1"))
        }

    @Test
    fun queryViewModelKeepsCanvasResizeOutOfQuerySpec() =
        runTest(dispatcher) {
            val query = QueryViewModel(NoOpService(), TestScope(dispatcher))
            query.addTable(sampleTable())

            query.resizeTable("t0", width = 420f, height = 360f)

            assertEquals(420f, query.canvasTables.single().width)
            assertEquals(360f, query.canvasTables.single().height)
            assertEquals(
                listOf(TableRef(schema = "public", name = "users", alias = "t0")),
                query.spec.tables,
            )
        }

    @Test
    fun queryViewModelRejectsDuplicateRunsBeforeCoroutineStarts() =
        runTest(dispatcher) {
            val service = RecordingQueryService()
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())

            query.run("c1")
            query.run("c1")
            scope.advanceUntilIdle()

            assertEquals(1, service.queryAttempts)
            assertFalse(query.running)
        }

    @Test
    fun queryViewModelIgnoresCompletionAfterClear() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = RecordingQueryService(queryGate = gate, queryStarted = started)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())

            query.run("c1")
            assertTrue(query.running)
            scope.runCurrent()
            assertTrue(started.isCompleted)

            query.clear()
            assertTrue(query.running)
            query.addTable(sampleTable())
            query.run("c1")
            scope.runCurrent()

            assertEquals(1, service.queryAttempts)
            assertFalse(query.canRun)
            query.clear()
            assertTrue(query.running)

            gate.complete(Unit)
            scope.advanceUntilIdle()

            assertFalse(query.running)
            assertNull(query.results)
            assertNull(query.error)
            assertEquals(0, query.tableCount)
        }

    @Test
    fun queryViewModelDiscardsAssessmentAndResultAfterDraftFingerprintChanges() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = RecordingQueryService(queryGate = gate, queryStarted = started)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.runCurrent()
            assertTrue(started.isCompleted)

            query.setLimit(50)
            assertTrue(query.running)
            assertFalse(query.canRun)
            query.run("c1")
            scope.runCurrent()

            assertEquals(1, service.queryAttempts)
            assertNull(settled)

            gate.complete(Unit)
            scope.advanceUntilIdle()

            assertFalse(query.running)
            assertEquals(false, settled)
            assertNull(query.results)
            assertNull(query.riskEvaluation)
            assertEquals(50, query.limit)
        }

    @Test
    fun queryViewModelKeepsRunOwnedUntilItSettlesButDropsItAfterConnectionSwitch() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = RecordingQueryService(queryGate = gate, queryStarted = started)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.runCurrent()
            assertTrue(started.isCompleted)

            query.onActiveConnectionChanged("c2")

            assertTrue(query.running)
            assertFalse(query.canRun)
            assertNull(query.currentSample("c1"))
            assertNull(query.currentSample("c2"))
            assertNull(query.riskEvaluationFor("c2"))

            gate.complete(Unit)
            scope.advanceUntilIdle()

            assertFalse(query.running)
            assertEquals(false, settled)
            assertNull(query.results)
            assertNull(query.riskEvaluation)
            assertEquals(1, service.queryAttempts)
        }

    @Test
    fun queryViewModelClearsSettledSampleAndAssessmentWhenConnectionChanges() =
        runTest(dispatcher) {
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(NoOpService(), scope)
            query.addTable(sampleTable())

            query.run("c1")
            scope.advanceUntilIdle()
            assertNotNull(query.currentSample("c1"))
            assertNotNull(query.riskEvaluationFor("c1"))

            query.onActiveConnectionChanged("c2")

            assertNull(query.results)
            assertNull(query.currentSample("c1"))
            assertNull(query.currentSample("c2"))
            assertNull(query.riskEvaluationFor("c1"))
            assertNull(query.riskEvaluationFor("c2"))
        }

    @Test
    fun queryViewModelRetriesOnlyWithThePendingScopedConfirmation() =
        runTest(dispatcher) {
            val service = RecordingQueryService(confirmationRequired = true)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.onQueryRiskGateChanged(QueryRiskGate.Standard)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.advanceUntilIdle()

            assertEquals(service.requiredConfirmation, query.pendingConfirmation?.confirmation)
            assertEquals(
                listOf("Query plan assessment is unavailable."),
                query.pendingConfirmationReasons,
            )
            assertEquals("Query plan assessment is unavailable.", query.pendingConfirmationReason)
            assertNull(query.error)
            assertFalse(query.canRun)
            assertNull(settled)

            query.onQueryRiskGateChanged(QueryRiskGate.Flexible)
            assertEquals(service.requiredConfirmation, query.pendingConfirmation?.confirmation)
            assertFalse(query.canRun)
            query.onQueryRiskGateChanged(QueryRiskGate.Standard)
            assertEquals(service.requiredConfirmation, query.pendingConfirmation?.confirmation)

            query.confirmPendingExecution("c1")
            assertTrue(query.running)
            scope.advanceUntilIdle()

            assertEquals(2, service.queryAttempts)
            assertEquals(service.requiredConfirmation, service.queryRequests.last().confirmation)
            assertNull(query.pendingConfirmation)
            assertNotNull(query.results)
            assertEquals(true, settled)
        }

    @Test
    fun queryViewModelDraftEditInvalidatesPendingConfirmation() =
        runTest(dispatcher) {
            val service = RecordingQueryService(confirmationRequired = true)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.advanceUntilIdle()
            assertNotNull(query.pendingConfirmation)

            query.setLimit(50)
            query.confirmPendingExecution("c1")
            scope.advanceUntilIdle()

            assertNull(query.pendingConfirmation)
            assertNull(query.error)
            assertEquals(false, settled)
            assertEquals(1, service.queryAttempts)
            assertTrue(query.canRun)
        }

    @Test
    fun queryViewModelCannotConfirmForAHiddenConnection() =
        runTest(dispatcher) {
            val service = RecordingQueryService(confirmationRequired = true)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.advanceUntilIdle()
            assertNotNull(query.pendingConfirmation)

            query.confirmPendingExecution("c2")
            scope.advanceUntilIdle()

            assertNull(query.pendingConfirmation)
            // The abandoned request's evaluation goes too, or the header keeps demanding a
            // confirmation whose dialog no longer exists (same rule as the SQL editor).
            assertNull(query.riskEvaluation)
            assertEquals(false, settled)
            assertEquals(1, service.queryAttempts)
            assertTrue(query.canRun)
        }

    @Test
    fun queryViewModelClearsSettledRiskGateFailureWhenExecutableQueryChanges() =
        runTest(dispatcher) {
            val service = RecordingQueryService(riskGateFirstRun = true)
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())

            query.run("c1")
            scope.advanceUntilIdle()

            assertTrue(query.pendingRiskGate)
            assertNotNull(query.error)
            assertFalse(query.canRun)

            query.run("c1")
            scope.advanceUntilIdle()
            assertEquals(1, service.queryAttempts)

            query.resizeTable("t0", width = 420f, height = 360f)
            assertTrue(query.pendingRiskGate)
            assertNotNull(query.error)

            query.moveTable("t0", x = 80f, y = 40f)
            assertTrue(query.pendingRiskGate)
            assertNotNull(query.error)

            query.setLimit(50)

            assertFalse(query.pendingRiskGate)
            assertNull(query.error)
            assertTrue(query.canRun)
        }

    @Test
    fun queryViewModelClearsASettledRiskBlockOnlyWhenTheGateChanges() =
        runTest(dispatcher) {
            val service =
                RecordingQueryService(
                    riskGateFirstRun = true,
                    successfulRiskGate = QueryRiskGate.Flexible,
                )
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.addTable(sampleTable())

            query.run("c1")
            scope.advanceUntilIdle()

            assertTrue(query.pendingRiskGate)
            assertFalse(query.canRun)

            query.onQueryRiskGateChanged(QueryRiskGate.Standard)
            assertTrue(query.pendingRiskGate)
            assertFalse(query.canRun)

            query.onQueryRiskGateChanged(QueryRiskGate.Flexible)

            assertFalse(query.pendingRiskGate)
            assertNull(query.error)
            assertNull(query.riskEvaluation)
            assertTrue(query.canRun)

            query.run("c1")
            scope.advanceUntilIdle()

            assertEquals(2, service.queryAttempts)
            assertNotNull(query.results)
            assertEquals(QueryRiskGate.Flexible, query.riskEvaluation?.decision?.effectiveGate)
            assertNotNull(query.currentSample("c1"))

            query.onQueryRiskGateChanged(QueryRiskGate.Cautious)

            assertNull(query.riskEvaluation)
            assertNotNull(query.currentSample("c1"))
            assertTrue(query.canRun)
        }

    @Test
    fun queryViewModelDiscardsAnInFlightRiskDecisionAfterTheGateChanges() =
        runTest(dispatcher) {
            val runGate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service =
                RecordingQueryService(
                    riskGateFirstRun = true,
                    queryGate = runGate,
                    queryStarted = started,
                )
            val scope = TestScope(dispatcher)
            val query = QueryViewModel(service, scope)
            query.onQueryRiskGateChanged(QueryRiskGate.Standard)
            query.addTable(sampleTable())
            var settled: Boolean? = null

            query.run("c1") { settled = it }
            scope.runCurrent()
            assertTrue(started.isCompleted)

            query.onQueryRiskGateChanged(QueryRiskGate.Flexible)

            assertTrue(query.running)
            assertFalse(query.canRun)

            runGate.complete(Unit)
            scope.advanceUntilIdle()

            assertFalse(query.running)
            assertFalse(query.pendingRiskGate)
            assertNull(query.error)
            assertNull(query.riskEvaluation)
            assertNull(query.results)
            assertTrue(query.canRun)
            assertEquals(1, service.queryAttempts)
            assertEquals(false, settled)
        }

    private fun sampleTable() =
        TableInfo(
            schema = "public",
            name = "users",
            columns =
                listOf(
                    ColumnInfo("id", "int", nullable = false, isIndexed = true),
                    ColumnInfo("name", "varchar", nullable = false, isIndexed = false),
                ),
            indexes = emptyList(),
        )

    private fun filter(id: String, alias: String, column: String) =
        FilterSpec(
            id = id,
            tableAlias = alias,
            column = column,
            op = FilterOp.Eq,
            value = FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
        )

    private fun table(name: String, vararg columns: String) =
        TableInfo(
            schema = "app",
            name = name,
            columns =
                columns.map { column ->
                    ColumnInfo(column, "int", nullable = false, isIndexed = true)
                },
            indexes = emptyList(),
        )
}

private class NoOpService : FakeSafeDbServiceSupport() {
    override suspend fun runQuery(request: QueryRunRequest) =
        queryRunResult(QueryResult(emptyList(), emptyList(), 0, false, emptyList()))
}

private class RecordingQueryService(
    private val riskGateFirstRun: Boolean = false,
    private val confirmationRequired: Boolean = false,
    private val queryGate: CompletableDeferred<Unit>? = null,
    private val queryStarted: CompletableDeferred<Unit>? = null,
    private val successfulRiskGate: QueryRiskGate = QueryRiskGate.Standard,
) : FakeSafeDbServiceSupport() {
    var queryAttempts = 0
        private set

    val queryRequests = mutableListOf<QueryRunRequest>()
    val requiredConfirmation =
        QueryExecutionConfirmation(
            connectionId = "c1",
            connectionFingerprint = "connection-fingerprint",
            queryFingerprint = "confirmable",
            conditions =
                setOf(
                    QueryConfirmationCondition(
                        QueryConfirmationReasonCode.PlanUnavailable,
                        "TimedOut",
                    ),
                ),
        )

    override suspend fun runQuery(request: QueryRunRequest): QueryRunResult {
        queryAttempts += 1
        queryRequests += request
        queryStarted?.complete(Unit)
        queryGate?.await()
        if (riskGateFirstRun && queryAttempts == 1) {
            val assessment =
                QueryRiskAssessment(
                    scoreVersion = QUERY_RISK_SCORE_VERSION,
                    queryFingerprint = "blocked",
                    score = 6,
                    severity = QueryRiskSeverity.High,
                    categoryScores = emptyMap(),
                    signals = emptyList(),
                    uncertainties = emptyList(),
                )
            val decision =
                QueryRiskDecision(
                    queryFingerprint = "blocked",
                    state = RiskGateState.Blocked,
                    effectiveGate = QueryRiskGate.Standard,
                    blockingBand = QueryRiskSeverity.High,
                    reasons =
                        listOf(
                            RiskDecisionReason(
                                "risk_gate",
                                "The query risk gate blocks this query.",
                            ),
                        ),
                )
            throw QueryFailureException(
                QueryError.RiskGate(
                    evaluation =
                        QueryRiskEvaluation(
                            staticAssessment = assessment,
                            finalAssessment = assessment,
                            planStatus = QueryPlanStatus.Available,
                            decision = decision,
                        ),
                    historySpec = request.spec,
                ),
            )
        }
        if (confirmationRequired && request.confirmation != requiredConfirmation) {
            val assessment =
                QueryRiskAssessment(
                    scoreVersion = QUERY_RISK_SCORE_VERSION,
                    queryFingerprint = "confirmable",
                    score = 0,
                    severity = QueryRiskSeverity.Minimal,
                    categoryScores = emptyMap(),
                    signals = emptyList(),
                    uncertainties = emptyList(),
                )
            val reason =
                RiskDecisionReason(
                    code = "plan_unavailable",
                    message = "Query plan assessment is unavailable.",
                )
            val requirement = QueryConfirmationRequirement(requiredConfirmation, listOf(reason))
            val decision =
                QueryRiskDecision(
                    queryFingerprint = "confirmable",
                    state = RiskGateState.ConfirmationRequired,
                    effectiveGate = QueryRiskGate.Standard,
                    blockingBand = QueryRiskSeverity.High,
                    reasons = listOf(reason),
                )
            throw QueryFailureException(
                QueryError.ConfirmationRequired(
                    evaluation =
                        QueryRiskEvaluation(
                            staticAssessment = assessment,
                            finalAssessment = assessment,
                            planStatus = QueryPlanStatus.Unavailable,
                            decision = decision,
                            confirmationRequirement = requirement,
                        ),
                    requirement = requirement,
                    historySpec = request.spec,
                ),
            )
        }
        val result =
            QueryResult(
                columns = listOf(ResultColumn("name", "varchar")),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            )
        val assessment =
            QueryRiskAssessment(
                2,
                "allowed",
                0,
                QueryRiskSeverity.Minimal,
                emptyMap(),
                emptyList(),
                emptyList(),
            )
        return QueryRunResult(
            result,
            QueryRiskEvaluation(
                staticAssessment = assessment,
                finalAssessment = assessment,
                planStatus = QueryPlanStatus.Available,
                decision =
                    QueryRiskDecision(
                        "allowed",
                        RiskGateState.Allowed,
                        successfulRiskGate,
                        QueryRiskSeverity.High,
                        emptyList(),
                    ),
            ),
        )
    }
}
