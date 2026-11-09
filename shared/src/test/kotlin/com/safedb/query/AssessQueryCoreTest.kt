package com.safedb.query

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
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
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.service.QueryAssessmentResult
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.QuerySession
import com.safedb.service.QuerySessionFactory
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AssessQueryCoreTest {
    @Test
    fun allowedAssessmentReportsColumnsAndCompiledSqlWithoutExecuting() = runBlocking {
        val runner = StubRunner()

        val outcome = assessQueryCore(runner, connection(), spec(), schema(), Settings.default())

        val assessment = assertIs<QueryAssessmentOutcome.Success>(outcome).assessment
        assertEquals(spec(), assessment.normalizedSpec)
        assertEquals(listOf(ResultColumn("t0__id", "int")), assessment.columns)
        assertTrue(assessment.compiled.sql.startsWith("SELECT "))
        assertTrue(assessment.compiled.params.isEmpty())
        assertEquals(QueryPlanStatus.Available, assessment.riskEvaluation.planStatus)
        assertEquals(RiskGateState.Allowed, assessment.riskEvaluation.decision.state)
        assertEquals(1, runner.explainCalls)
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun blockedRiskGateIsReportedAsSuccessNotRun() = runBlocking {
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
        val settings = Settings.default().copy(queryRiskGate = QueryRiskGate.Cautious)

        val outcome =
            assessQueryCore(
                runner,
                connection(),
                spec(),
                schema(TableSizeClass.Large),
                settings,
            )

        val assessment = assertIs<QueryAssessmentOutcome.Success>(outcome).assessment
        assertEquals(RiskGateState.Blocked, assessment.riskEvaluation.decision.state)
        assertTrue(assessment.riskEvaluation.decision.reasons.isNotEmpty())
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun missingOptimizerCostRequiresConfirmation() = runBlocking {
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

        val outcome = assessQueryCore(runner, connection(), spec(), schema(), Settings.default())

        val assessment = assertIs<QueryAssessmentOutcome.Success>(outcome).assessment
        assertEquals(QueryPlanStatus.Incomplete, assessment.riskEvaluation.planStatus)
        assertEquals(
            RiskGateState.ConfirmationRequired,
            assessment.riskEvaluation.decision.state,
        )
        assertEquals(
            setOf(QueryConfirmationReasonCode.OptimizerCostUnavailable),
            assertNotNull(assessment.riskEvaluation.confirmationRequirement)
                .confirmation
                .reasonCodes,
        )
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun disabledGateSkipsPlanAssessment() = runBlocking {
        val runner =
            StubRunner(
                explainResult = ExplainResult.Unavailable(PlanUnavailableReason.TimedOut, "timeout")
            )

        val outcome =
            assessQueryCore(
                runner,
                connection(),
                spec(),
                schema(),
                Settings.default().copy(queryRiskGate = QueryRiskGate.Disabled),
            )

        val assessment = assertIs<QueryAssessmentOutcome.Success>(outcome).assessment
        assertEquals(QueryPlanStatus.Disabled, assessment.riskEvaluation.planStatus)
        assertNull(assessment.riskEvaluation.finalAssessment)
        assertEquals(RiskGateState.Allowed, assessment.riskEvaluation.decision.state)
        assertEquals(0, runner.explainCalls)
        assertEquals(0, runner.executeCalls)
    }

    @Test
    fun validationFailureReturnsCoreError() = runBlocking {
        val runner = StubRunner()
        val invalid = spec().copy(tables = listOf(TableRef("public", "missing", "t0")))

        val outcome = assessQueryCore(runner, connection(), invalid, schema(), Settings.default())

        val failure = assertIs<QueryAssessmentOutcome.Failure>(outcome)
        assertIs<QueryError.Validation>(failure.error.error)
        assertEquals(0, runner.explainCalls)
    }

    @Test
    fun planTimeoutIsAHardFailure() = runBlocking {
        val runner =
            StubRunner(
                explainResult = ExplainResult.Unavailable(PlanUnavailableReason.TimedOut, "timeout")
            )

        val outcome = assessQueryCore(runner, connection(), spec(), schema(), Settings.default())

        val failure = assertIs<QueryAssessmentOutcome.Failure>(outcome)
        val error = assertIs<QueryError.Execution>(failure.error.error)
        assertEquals("Query plan assessment timed out", error.message)
        assertEquals(
            QueryPlanStatus.Unavailable,
            assertNotNull(failure.error.riskEvaluation).planStatus,
        )
    }

    @Test
    fun serviceAssessQueryReportsAssessmentWithoutRecordingHistory() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-assess-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        configStore.save(serviceConnection())
        SecretsManager.savePasswordForDefinition(serviceConnection(), "secret").getOrThrow()
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory =
                    QuerySessionFactory { _, _ -> QuerySession(serviceSchema(), StubRunner()) },
            )

        val result: QueryAssessmentResult =
            service.assessQuery(QueryRunRequest("c1", serviceSpec()))

        assertEquals(serviceSpec(), result.normalizedSpec)
        assertEquals(listOf(ResultColumn("t0__id", "int")), result.columns)
        assertEquals(0, result.parameterCount)
        assertTrue(result.compiledSql.startsWith("SELECT "))
        assertEquals(RiskGateState.Allowed, result.riskEvaluation.decision.state)
        assertTrue(queryStore.listHistory().isEmpty())
    }

    @Test
    fun serviceAssessQueryThrowsOnValidationFailureWithoutHistory() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-assess-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        configStore.save(serviceConnection())
        SecretsManager.savePasswordForDefinition(serviceConnection(), "secret").getOrThrow()
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory =
                    QuerySessionFactory { _, _ -> QuerySession(serviceSchema(), StubRunner()) },
            )

        val failure =
            assertFailsWith<QueryFailureException> {
                service.assessQuery(
                    QueryRunRequest(
                        "c1",
                        serviceSpec()
                            .copy(tables = listOf(TableRef("safedb_test", "missing", "t0"))),
                    )
                )
            }

        assertIs<QueryError.Validation>(failure.queryError)
        assertTrue(queryStore.listHistory().isEmpty())
    }
}

private class StubRunner(
    private val explainResult: ExplainResult = availablePlan(),
    private val execute: Outcome<QueryResult> = Outcome.ok(sampleResult()),
) : QueryRunner {
    var explainCalls = 0
    var executeCalls = 0

    override suspend fun explain(compiled: CompiledQuery): ExplainResult {
        explainCalls += 1
        return explainResult
    }

    override suspend fun executeQuery(
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): Outcome<QueryResult> {
        executeCalls += 1
        return execute
    }
}

private fun availablePlan(): ExplainResult =
    ExplainResult.Available(NormalizedQueryPlan(rawOptimizerCost = 1.0))

private fun connection() =
    ConnectionDef(
        id = "c1",
        name = "Test",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "demo",
        username = "readonly",
        transportSecurity = TransportSecurity(),
    )

private fun spec() =
    QuerySpec(
        tables = listOf(TableRef(schema = "public", name = "users", alias = "t0")),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
        filters = FilterGroup(id = "root"),
        limit = 100,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun schema(sizeClass: TableSizeClass = TableSizeClass.Medium) =
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
                            EvidenceConfidence.High,
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

private fun serviceConnection() =
    ConnectionDef(
        id = "c1",
        name = "Assess",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "testuser",
        transportSecurity = TransportSecurity(),
    )

private fun serviceSpec() =
    QuerySpec(
        tables = listOf(TableRef(schema = "safedb_test", name = "customers", alias = "t0")),
        columns = listOf(ColumnSel("t0", "id")),
        filters = FilterGroup(id = "root"),
        limit = 10,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun serviceSchema() =
    Schema(
        tables =
            listOf(
                TableInfo(
                    schema = "safedb_test",
                    name = "customers",
                    columns =
                        listOf(
                            ColumnInfo(
                                name = "id",
                                dataType = "int",
                                nullable = false,
                                isIndexed = true,
                                joinEligible = true,
                                category = ColumnCategory.Integer,
                            )
                        ),
                    indexes = emptyList(),
                    indexMetadata = MetadataCoverage.complete(),
                    foreignKeyMetadata = MetadataCoverage.complete(),
                    tableSize =
                        TableSizeEstimate(
                            TableSizeClass.Unknown,
                            MetadataCoverage.complete(),
                            EvidenceConfidence.High,
                        ),
                )
            )
    )
