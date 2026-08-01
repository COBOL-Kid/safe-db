package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanRelationAccess
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryRiskGate
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupSpec
import com.safedb.model.IndexInfo
import com.safedb.model.LiteralKind
import com.safedb.model.MetadataCoverage
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryCoreTest {
    @Test
    fun unavailableExplainUsesStaticRiskAndExecutesWhenAllowed() = runBlocking {
        val runner = StubRunner(explainResult = ExplainResult.Unavailable(PlanUnavailableReason.PermissionDenied, "planner disabled"))
        val outcome = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default(),
        )
        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(QueryPlanStatus.Unavailable, outcome.riskEvaluation.planStatus)
        assertEquals(1, runner.explainCalls)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun disabledModeSkipsExplainAndExecutes() = runBlocking {
        val runner = StubRunner()
        val outcome = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default().copy(queryRiskGate = QueryRiskGate.Disabled),
        )
        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(QueryPlanStatus.Disabled, outcome.riskEvaluation.planStatus)
        assertEquals(0, runner.explainCalls)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun executionFailureReturnsExecutionError() = runBlocking {
        val runner = StubRunner(
            execute = Outcome.err("timeout"),
        )
        val outcome = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default(),
        )
        assertTrue(outcome is QueryCoreOutcome.Failure)
        assertTrue(outcome.error.message.contains("timeout"))
    }

    @Test
    fun planRefinementBlocksBeforeExecution() = runBlocking {
        val runner = StubRunner(
            explainResult = ExplainResult.Available(
                NormalizedQueryPlan(
                    relations = listOf(PlanRelationAccess(table = "users", alias = "t0", method = PlanAccessMethod.TableScan, estimatedRows = 150_000)),
                ),
            ),
        )
        val blocked = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(TableSizeClass.Large),
            Settings.default(),
        )
        assertTrue(blocked is QueryCoreOutcome.Failure)
        assertTrue(blocked.error.error is QueryError.RiskGate)
        assertEquals(1, runner.explainCalls)
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun enabledRunExplainsExactlyOnceBeforeExecution() = runBlocking {
        val runner = StubRunner()

        val outcome = runQueryCore(runner, sampleConnection(), sampleSpec(), sampleSchema(), Settings.default())

        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(listOf("explain", "execute"), runner.calls)
    }

    @Test
    fun thrownPlannerFailureFallsBackToStaticAssessment() = runBlocking {
        val runner = object : QueryRunner {
            var executeCalls = 0
            override suspend fun explain(compiled: CompiledQuery): ExplainResult = error("planner offline")
            override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> {
                executeCalls++
                return Outcome.ok(sampleResult())
            }
        }

        val outcome = runQueryCore(runner, sampleConnection(), sampleSpec(), sampleSchema(), Settings.default())

        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(QueryPlanStatus.Unavailable, outcome.riskEvaluation.planStatus)
        assertEquals(PlanUnavailableReason.ExecutionFailure, outcome.riskEvaluation.planUnavailableReason)
        assertEquals(1, runner.executeCalls)
    }

    private class StubRunner(
        private val explainResult: ExplainResult = ExplainResult.Available(
            NormalizedQueryPlan(
                relations = listOf(PlanRelationAccess(table = "users", alias = "t0", method = PlanAccessMethod.BoundedLookup, estimatedRows = 1)),
            ),
        ),
        private val execute: Outcome<QueryResult> = Outcome.ok(sampleResult()),
    ) : QueryRunner {
        var executeCalls = 0
        var explainCalls = 0
        val calls = mutableListOf<String>()

        override suspend fun explain(compiled: CompiledQuery): ExplainResult {
            explainCalls++
            calls += "explain"
            return explainResult
        }

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> {
            executeCalls++
            calls += "execute"
            return execute
        }
    }
}

private fun sampleConnection() = ConnectionDef(
    id = "c1",
    name = "Test",
    dialect = Dialect.Postgres,
    host = "localhost",
    port = 5432,
    database = "demo",
    username = "readonly",
    transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
)

private fun sampleSpec() = QuerySpec(
    tables = listOf(TableRef(schema = "public", name = "users", alias = "t0")),
    columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
    joins = emptyList(),
    filters = FilterGroup.empty(),
    limit = 100,
    schemaVersion = CURRENT_SCHEMA_VERSION,
)

private fun riskySpec() = sampleSpec().copy(
    columns = listOf(ColumnSel(tableAlias = "t0", column = "notes")),
    filters = FilterGroup(
        id = "root",
        children = listOf(
            FilterNode.Leaf(
                FilterSpec(
                    id = "notes-filter",
                    tableAlias = "t0",
                    column = "notes",
                    op = FilterOp.Contains,
                    value = FilterValue.Single(FilterLiteral(LiteralKind.Text, "needle")),
                ),
            ),
        ),
    ),
    groups = listOf(GroupSpec("t0", "notes")),
    limit = 5_000,
)

private fun sampleSchema(sizeClass: TableSizeClass = TableSizeClass.Medium) = Schema(
    tables = listOf(
        TableInfo(
            schema = "public",
            name = "users",
            columns = listOf(
                ColumnInfo(
                    name = "id",
                    dataType = "int",
                    nullable = false,
                    isIndexed = true,
                    joinEligible = true,
                    category = ColumnCategory.Integer,
                ),
                ColumnInfo(
                    name = "notes",
                    dataType = "text",
                    nullable = true,
                    category = ColumnCategory.Text,
                ),
            ),
            indexes = emptyList(),
            indexMetadata = MetadataCoverage.complete(),
            foreignKeyMetadata = MetadataCoverage.complete(),
            tableSize = TableSizeEstimate(sizeClass, MetadataCoverage.complete(), com.safedb.model.EvidenceConfidence.High),
        ),
    ),
)

private fun sampleResult() = QueryResult(
    columns = listOf(ResultColumn("id", "int")),
    rows = listOf(listOf(ResultCell.integer(1))),
    rowCount = 1,
    truncated = false,
    warnings = emptyList(),
)
