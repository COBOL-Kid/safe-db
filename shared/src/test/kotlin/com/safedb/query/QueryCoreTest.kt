package com.safedb.query

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupSpec
import com.safedb.model.LiteralKind
import com.safedb.model.MetadataCoverage
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.Outcome
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanRelationAccess
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class QueryCoreTest {
    @Test
    fun unavailableExplainRequiresFingerprintScopedConfirmationBeforeExecution() = runBlocking {
        val runner =
            StubRunner(
                explainResult =
                    ExplainResult.Unavailable(
                        PlanUnavailableReason.PermissionDenied,
                        "planner disabled",
                    )
            )
        val spec = sampleSpec()
        val first =
            runQueryCore(runner, sampleConnection(), spec, sampleSchema(), Settings.default())

        val failure = assertIs<QueryCoreOutcome.Failure>(first)
        val confirmationError = assertIs<QueryError.ConfirmationRequired>(failure.error.error)
        assertEquals(
            RiskGateState.ConfirmationRequired,
            confirmationError.evaluation.decision.state,
        )
        assertEquals(QueryPlanStatus.Unavailable, confirmationError.evaluation.planStatus)
        assertEquals(
            setOf(QueryConfirmationReasonCode.PlanUnavailable),
            confirmationError.requirement.confirmation.reasonCodes,
        )
        assertEquals(1, runner.explainCalls)
        assertEquals(0, runner.executeCalls)

        val confirmed =
            runQueryCore(
                runner,
                sampleConnection(),
                spec,
                sampleSchema(),
                Settings.default(),
                confirmationError.requirement.confirmation,
            )

        val success = assertIs<QueryCoreOutcome.Success>(confirmed)
        assertTrue(success.riskEvaluation.confirmationAccepted)
        assertEquals(RiskGateState.Allowed, success.riskEvaluation.decision.state)
        assertEquals(2, runner.explainCalls)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun disabledRiskModeStillAppliesPlanSafeguards() = runBlocking {
        val runner = StubRunner()
        val outcome =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default().copy(queryRiskGate = QueryRiskGate.Disabled),
            )
        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(QueryPlanStatus.Available, outcome.riskEvaluation.planStatus)
        assertNull(outcome.riskEvaluation.finalAssessment)
        assertEquals(1, runner.explainCalls)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun disabledRiskModeStillRequiresConfirmationWhenPlanIsUnavailable() = runBlocking {
        val runner =
            StubRunner(
                explainResult = ExplainResult.Unavailable(PlanUnavailableReason.TimedOut, "timeout")
            )

        val outcome =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default().copy(queryRiskGate = QueryRiskGate.Disabled),
            )

        val error =
            assertIs<QueryError.ConfirmationRequired>(
                assertIs<QueryCoreOutcome.Failure>(outcome).error.error
            )
        assertNull(error.evaluation.finalAssessment)
        assertEquals(
            setOf(QueryConfirmationReasonCode.PlanUnavailable),
            error.requirement.confirmation.reasonCodes,
        )
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun highOptimizerCostDoesNotRequireConfirmation() = runBlocking {
        val runner =
            StubRunner(
                explainResult =
                    ExplainResult.Available(
                        NormalizedQueryPlan(
                            relations =
                                listOf(
                                    PlanRelationAccess(
                                        table = "users",
                                        alias = "t0",
                                        method = PlanAccessMethod.BoundedLookup,
                                        estimatedRows = 1,
                                    )
                                ),
                            rawOptimizerCost = Double.MAX_VALUE,
                        )
                    )
            )

        val outcome =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default(),
            )

        val success = assertIs<QueryCoreOutcome.Success>(outcome)
        assertEquals(QueryPlanStatus.Available, success.riskEvaluation.planStatus)
        assertEquals(Double.MAX_VALUE, success.riskEvaluation.optimizerCost)
        assertTrue(success.riskEvaluation.finalAssessment != null)
        assertFalse(success.riskEvaluation.confirmationAccepted)
        assertNull(success.riskEvaluation.confirmationRequirement)
        assertEquals(1, runner.explainCalls)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun availablePlanWithoutOptimizerCostRequiresConfirmation() = runBlocking {
        val runner =
            StubRunner(
                explainResult =
                    ExplainResult.Available(
                        NormalizedQueryPlan(
                            relations =
                                listOf(
                                    PlanRelationAccess(
                                        table = "users",
                                        alias = "t0",
                                        method = PlanAccessMethod.BoundedLookup,
                                        estimatedRows = 1,
                                    )
                                )
                        )
                    )
            )

        val first =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default(),
            )

        val failure = assertIs<QueryCoreOutcome.Failure>(first)
        val confirmationError = assertIs<QueryError.ConfirmationRequired>(failure.error.error)
        assertEquals(QueryPlanStatus.Incomplete, confirmationError.evaluation.planStatus)
        assertEquals(
            setOf(QueryConfirmationReasonCode.OptimizerCostUnavailable),
            confirmationError.requirement.confirmation.reasonCodes,
        )
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun invalidOptimizerCostsCannotPassAsLowCostEvidence() = runBlocking {
        for (invalidCost in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0)) {
            val runner =
                StubRunner(
                    explainResult =
                        ExplainResult.Available(NormalizedQueryPlan(rawOptimizerCost = invalidCost))
                )

            val outcome =
                runQueryCore(
                    runner,
                    sampleConnection(),
                    sampleSpec(),
                    sampleSchema(),
                    Settings.default(),
                )

            val failure = assertIs<QueryCoreOutcome.Failure>(outcome)
            val confirmationError = assertIs<QueryError.ConfirmationRequired>(failure.error.error)
            assertEquals(QueryPlanStatus.Incomplete, confirmationError.evaluation.planStatus)
            assertEquals(
                setOf(QueryConfirmationReasonCode.OptimizerCostUnavailable),
                confirmationError.requirement.confirmation.reasonCodes,
            )
            assertEquals(0, runner.executeCalls)
        }
    }

    @Test
    fun confirmationForDifferentConnectionOrQueryCannotAuthorizeExecution() = runBlocking {
        val runner =
            StubRunner(
                explainResult = ExplainResult.Unavailable(PlanUnavailableReason.TimedOut, "timeout")
            )
        val spec = sampleSpec()
        val first =
            assertIs<QueryCoreOutcome.Failure>(
                runQueryCore(runner, sampleConnection(), spec, sampleSchema(), Settings.default())
            )
        val required =
            assertIs<QueryError.ConfirmationRequired>(first.error.error).requirement.confirmation

        val wrongConnection =
            runQueryCore(
                runner,
                sampleConnection(),
                spec,
                sampleSchema(),
                Settings.default(),
                required.copy(connectionId = "other"),
            )
        val wrongQuery =
            runQueryCore(
                runner,
                sampleConnection(),
                spec,
                sampleSchema(),
                Settings.default(),
                required.copy(queryFingerprint = "stale"),
            )
        val changedConnectionDefinition =
            runQueryCore(
                runner,
                sampleConnection().copy(host = "other.example.com"),
                spec,
                sampleSchema(),
                Settings.default(),
                required,
            )

        assertIs<QueryError.ConfirmationRequired>(
            assertIs<QueryCoreOutcome.Failure>(wrongConnection).error.error
        )
        assertIs<QueryError.ConfirmationRequired>(
            assertIs<QueryCoreOutcome.Failure>(wrongQuery).error.error
        )
        assertIs<QueryError.ConfirmationRequired>(
            assertIs<QueryCoreOutcome.Failure>(changedConnectionDefinition).error.error
        )

        val changedReasonRunner =
            StubRunner(
                explainResult =
                    ExplainResult.Unavailable(PlanUnavailableReason.PermissionDenied, "denied")
            )
        val changedReason =
            runQueryCore(
                changedReasonRunner,
                sampleConnection(),
                spec,
                sampleSchema(),
                Settings.default(),
                required,
            )
        assertIs<QueryError.ConfirmationRequired>(
            assertIs<QueryCoreOutcome.Failure>(changedReason).error.error
        )
        assertEquals(0, runner.executeCalls)
        assertEquals(0, changedReasonRunner.executeCalls)
    }

    @Test
    fun executionFailureReturnsExecutionError() = runBlocking {
        val runner = StubRunner(execute = Outcome.err("timeout"))
        val outcome =
            runQueryCore(
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
        val runner =
            StubRunner(
                explainResult =
                    ExplainResult.Available(
                        NormalizedQueryPlan(
                            relations =
                                listOf(
                                    PlanRelationAccess(
                                        table = "users",
                                        alias = "t0",
                                        method = PlanAccessMethod.TableScan,
                                        estimatedRows = 150_000,
                                    )
                                ),
                            rawOptimizerCost = 1.0,
                        )
                    )
            )
        val blocked =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(TableSizeClass.Large),
                Settings.default(),
                QueryExecutionConfirmation(
                    connectionId = "c1",
                    connectionFingerprint = sampleConnection().credentialFingerprint(),
                    queryFingerprint = "forged",
                    conditions =
                        setOf(
                            QueryConfirmationCondition(
                                QueryConfirmationReasonCode.PlanUnavailable,
                                "TimedOut",
                            )
                        ),
                ),
            )
        assertTrue(blocked is QueryCoreOutcome.Failure)
        assertTrue(blocked.error.error is QueryError.RiskGate)
        assertEquals(1, runner.explainCalls)
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun previouslyIssuedConfirmationCannotBypassBlockedSchemaValidation() = runBlocking {
        val connection = sampleConnection()
        val runner =
            StubRunner(
                explainResult = ExplainResult.Unavailable(PlanUnavailableReason.TimedOut, "timeout")
            )
        val initialFailure =
            assertIs<QueryCoreOutcome.Failure>(
                runQueryCore(runner, connection, sampleSpec(), sampleSchema(), Settings.default())
            )
        val exactConfirmation =
            assertIs<QueryError.ConfirmationRequired>(initialFailure.error.error)
                .requirement
                .confirmation

        val outcome =
            runQueryCore(
                runner,
                connection,
                sampleSpec(),
                sampleSchema(),
                Settings.default().copy(blockedSchemas = listOf("public")),
                exactConfirmation,
            )

        assertIs<QueryError.Validation>(assertIs<QueryCoreOutcome.Failure>(outcome).error.error)
        assertEquals(1, runner.explainCalls)
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun enabledRunExplainsExactlyOnceBeforeExecution() = runBlocking {
        val runner = StubRunner()

        val outcome =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default(),
            )

        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(listOf("explain", "execute"), runner.calls)
    }

    @Test
    fun thrownPlannerFailureRequiresConfirmationWithoutExecuting() = runBlocking {
        val runner =
            object : QueryRunner {
                var executeCalls = 0

                override suspend fun explain(compiled: CompiledQuery): ExplainResult =
                    error("planner offline")

                override suspend fun executeQuery(
                    compiled: CompiledQuery,
                    timeoutMs: Int,
                ): Outcome<QueryResult> {
                    executeCalls++
                    return Outcome.ok(sampleResult())
                }
            }

        val outcome =
            runQueryCore(
                runner,
                sampleConnection(),
                sampleSpec(),
                sampleSchema(),
                Settings.default(),
            )

        val failure = assertIs<QueryCoreOutcome.Failure>(outcome)
        val confirmationError = assertIs<QueryError.ConfirmationRequired>(failure.error.error)
        assertEquals(QueryPlanStatus.Unavailable, confirmationError.evaluation.planStatus)
        assertEquals(
            PlanUnavailableReason.ExecutionFailure,
            confirmationError.evaluation.planUnavailableReason,
        )
        assertEquals(0, runner.executeCalls)
    }

    private class StubRunner(
        private val explainResult: ExplainResult =
            ExplainResult.Available(
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                table = "users",
                                alias = "t0",
                                method = PlanAccessMethod.BoundedLookup,
                                estimatedRows = 1,
                            )
                        ),
                    rawOptimizerCost = 1.0,
                )
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

        override suspend fun executeQuery(
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): Outcome<QueryResult> {
            executeCalls++
            calls += "execute"
            return execute
        }
    }
}

private fun sampleConnection() =
    ConnectionDef(
        id = "c1",
        name = "Test",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "demo",
        username = "readonly",
        transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
    )

private fun sampleSpec() =
    QuerySpec(
        tables = listOf(TableRef(schema = "public", name = "users", alias = "t0")),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun riskySpec() =
    sampleSpec()
        .copy(
            columns = listOf(ColumnSel(tableAlias = "t0", column = "notes")),
            filters =
                FilterGroup(
                    id = "root",
                    children =
                        listOf(
                            FilterNode.Leaf(
                                FilterSpec(
                                    id = "notes-filter",
                                    tableAlias = "t0",
                                    column = "notes",
                                    op = FilterOp.Contains,
                                    value =
                                        FilterValue.Single(
                                            FilterLiteral(LiteralKind.Text, "needle")
                                        ),
                                )
                            )
                        ),
                ),
            groups = listOf(GroupSpec("t0", "notes")),
            limit = 5_000,
        )

private fun sampleSchema(sizeClass: TableSizeClass = TableSizeClass.Medium) =
    Schema(
        tables =
            listOf(
                TableInfo(
                    schema = "public",
                    name = "users",
                    columns =
                        listOf(
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
                    tableSize =
                        TableSizeEstimate(
                            sizeClass,
                            MetadataCoverage.complete(),
                            com.safedb.model.EvidenceConfidence.High,
                        ),
                )
            )
    )

private fun sampleResult() =
    QueryResult(
        columns = listOf(ResultColumn("id", "int")),
        rows = listOf(listOf(ResultCell.integer(1))),
        rowCount = 1,
        truncated = false,
        warnings = emptyList(),
    )
