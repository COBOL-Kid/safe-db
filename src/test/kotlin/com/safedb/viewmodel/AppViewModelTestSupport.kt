package com.safedb.viewmodel

import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.query.QUERY_RISK_SCORE_VERSION
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import kotlinx.coroutines.CompletableDeferred

internal class FakeSafeDbService(
    private val riskGateFirstRun: Boolean = false,
    private val confirmationRequired: Boolean = false,
    private val queryGate: CompletableDeferred<Unit>? = null,
    private val queryStarted: CompletableDeferred<Unit>? = null,
    private val settingsLoadGate: CompletableDeferred<Unit>? = null,
    private val connectionsLoadGate: CompletableDeferred<Unit>? = null,
    private val successfulRiskGate: QueryRiskGate = QueryRiskGate.Standard,
    private val schemaTables: List<TableInfo> = listOf(sampleTable()),
    private val executionError: String? = null,
    var testConnectionGate: CompletableDeferred<Unit>? = null,
    private val testConnectionStarted: CompletableDeferred<Unit>? = null,
    var testConnectionOutcome: Result<String> =
        Result.failure(UnsupportedOperationException("testConnection")),
) : FakeSafeDbServiceSupport() {
    var locked = false
    var testConnectionCalls = 0
        private set

    var testConnectionPassword: String? = null
        private set

    var queryAttempts = 0
        private set

    var settingsSaveCount = 0
        private set

    var schemaLoadCount = 0
        private set

    val recordedHistory = mutableListOf<HistoryEntry>()
    val recordedSavedQueries = mutableListOf<SavedQuery>()
    private var historySeq = 0
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

    override suspend fun listConnections(): List<ConnectionDef> {
        connectionsLoadGate?.await()
        return emptyList()
    }

    override suspend fun lockCredentials() {
        locked = true
    }

    override suspend fun testConnection(def: ConnectionDef, password: String?): String {
        testConnectionCalls += 1
        testConnectionPassword = password
        testConnectionStarted?.complete(Unit)
        testConnectionGate?.await()
        return testConnectionOutcome.getOrThrow()
    }

    override suspend fun getSchema(connectionId: String): Schema {
        schemaLoadCount += 1
        return Schema(schemaTables)
    }

    override suspend fun runQuery(request: QueryRunRequest): com.safedb.service.QueryRunResult {
        queryAttempts += 1
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
            val error =
                QueryError.RiskGate(
                    evaluation =
                        com.safedb.query.QueryRiskEvaluation(
                            staticAssessment = assessment,
                            finalAssessment = assessment,
                            planStatus = com.safedb.query.QueryPlanStatus.Available,
                            decision = decision,
                        ),
                    historySpec = request.spec,
                )
            rememberHistory(request, error = error.message)
            throw QueryFailureException(error)
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
            val error =
                QueryError.ConfirmationRequired(
                    evaluation =
                        com.safedb.query.QueryRiskEvaluation(
                            staticAssessment = assessment,
                            finalAssessment = assessment,
                            planStatus = com.safedb.query.QueryPlanStatus.Unavailable,
                            decision = decision,
                            confirmationRequirement = requirement,
                        ),
                    requirement = requirement,
                    historySpec = request.spec,
                )
            rememberHistory(request, error = error.message)
            throw QueryFailureException(error)
        }
        if (executionError != null) {
            rememberHistory(request, error = executionError)
            throw QueryFailureException(QueryError.Execution(executionError, request.spec))
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
        rememberHistory(request, rowCount = result.rowCount)
        return com.safedb.service.QueryRunResult(
            result,
            com.safedb.query.QueryRiskEvaluation(
                staticAssessment = assessment,
                finalAssessment = assessment,
                planStatus = com.safedb.query.QueryPlanStatus.Available,
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

    override suspend fun listSavedQueries(): List<SavedQuery> = recordedSavedQueries.toList()

    override suspend fun saveSavedQuery(query: SavedQuery) {
        recordedSavedQueries.removeAll { it.id == query.id }
        recordedSavedQueries += query
    }

    override suspend fun deleteSavedQuery(id: String) {
        recordedSavedQueries.removeAll { it.id == id }
    }

    override suspend fun listHistory(): List<HistoryEntry> = recordedHistory.toList()

    override suspend fun clearHistory() {
        recordedHistory.clear()
    }

    override suspend fun deleteHistoryEntry(id: String) {
        recordedHistory.removeAll { it.id == id }
    }

    private fun rememberHistory(
        request: QueryRunRequest,
        error: String? = null,
        rowCount: Int = 0,
    ) {
        if (!request.historyRecording.shouldRecord()) return
        historySeq += 1
        recordedHistory.add(
            0,
            HistoryEntry(
                id = "h$historySeq",
                connectionId = request.connectionId,
                connectionName = "Local",
                spec = request.spec,
                rowCount = rowCount,
                warnings = emptyList(),
                error = error,
                timestamp = historySeq.toString(),
            ),
        )
    }

    override suspend fun getSettings(): Settings {
        settingsLoadGate?.await()
        return Settings.default()
    }

    override suspend fun saveSettings(settings: Settings) {
        settingsSaveCount += 1
    }
}

internal fun testConnection() =
    ConnectionDef(
        id = "c1",
        name = "Local",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "test",
        username = "reader",
    )

internal fun sampleTable() =
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

internal fun sampleSpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "users", "saved_t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 25,
    )
