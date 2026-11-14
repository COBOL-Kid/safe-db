package com.safedb.query

import com.safedb.adapter.conciseErrorMessage
import com.safedb.adapter.unavailablePlanFromError
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import kotlinx.coroutines.CancellationException

const val DEFAULT_TIMEOUT_MS = 10_000

data class QueryCoreError(
    val error: QueryError,
    val warnings: List<String> = emptyList(),
    val riskEvaluation: QueryRiskEvaluation? = null,
) {
    val message: String
        get() = error.message

    val historySpec: QuerySpec?
        get() = error.historySpec
}

sealed class QueryError {
    abstract val message: String
    abstract val historySpec: QuerySpec?

    data class Validation(override val message: String) : QueryError() {
        override val historySpec: QuerySpec? = null
    }

    data class Compilation(override val message: String, override val historySpec: QuerySpec) :
        QueryError()

    data class RiskGate(val evaluation: QueryRiskEvaluation, override val historySpec: QuerySpec) :
        QueryError() {
        override val message: String =
            evaluation.decision.reasons
                .joinToString(separator = " ") { it.message }
                .ifEmpty { "The query risk gate blocks this query." }
    }

    data class ConfirmationRequired(
        val evaluation: QueryRiskEvaluation,
        val requirement: QueryConfirmationRequirement,
        override val historySpec: QuerySpec,
    ) : QueryError() {
        override val message: String =
            requirement.reasons
                .joinToString(separator = " ") { it.message }
                .ifEmpty { "Confirm to run this query with the remaining safeguards." }
    }

    data class Execution(
        override val message: String,
        override val historySpec: QuerySpec? = null,
    ) : QueryError()
}

interface QueryRunner {
    suspend fun explain(compiled: CompiledQuery): ExplainResult

    suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult>
}

sealed class QueryCoreOutcome {
    data class Success(
        val result: QueryResult,
        val historySpec: QuerySpec,
        val riskEvaluation: QueryRiskEvaluation,
    ) : QueryCoreOutcome()

    data class Failure(val error: QueryCoreError) : QueryCoreOutcome()
}

data class QueryAssessment(
    val normalizedSpec: QuerySpec,
    val columns: List<ResultColumn>,
    val compiled: CompiledQuery,
    val warnings: List<String>,
    val riskEvaluation: QueryRiskEvaluation,
)

sealed class QueryAssessmentOutcome {
    data class Success(val assessment: QueryAssessment) : QueryAssessmentOutcome()

    data class Failure(val error: QueryCoreError) : QueryAssessmentOutcome()
}

suspend fun assessQueryCore(
    runner: QueryRunner,
    def: ConnectionDef,
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
): QueryAssessmentOutcome {
    val (validated, normalizedSpec, warnings, staticAssessment) =
        when (val assessed = assessValidatedQueryRisk(spec, schema, settings, def.dialect)) {
            is Outcome.Ok -> assessed.value
            is Outcome.Err ->
                return QueryAssessmentOutcome.Failure(
                    QueryCoreError(QueryError.Validation(assessed.message)),
                )
        }

    val compiled =
        when (val result = compileValidated(validated, def.dialect)) {
            is Outcome.Ok -> result.value
            is Outcome.Err ->
                return QueryAssessmentOutcome.Failure(
                    QueryCoreError(
                        error = QueryError.Compilation(result.message, normalizedSpec),
                        warnings = warnings,
                    ),
                )
        }

    val columns =
        validated.columns().map { column ->
            val dataType =
                findTableByAlias(schema, normalizedSpec, column.tableAlias)
                    ?.columns
                    ?.find { it.name == column.column }
                    ?.dataType ?: "unknown"
            ResultColumn(name = column.resultAlias, dataType = dataType)
        }

    val fingerprint = staticAssessment?.queryFingerprint ?: queryFingerprint(validated)
    if (settings.queryRiskGate == QueryRiskGate.Disabled) {
        val evaluation =
            QueryRiskEvaluation(
                staticAssessment = null,
                finalAssessment = null,
                planStatus = QueryPlanStatus.Disabled,
                decision =
                    applyRiskGate(null, QueryRiskGate.Disabled)
                        .copy(queryFingerprint = fingerprint),
            )
        return QueryAssessmentOutcome.Success(
            QueryAssessment(normalizedSpec, columns, compiled, warnings, evaluation),
        )
    }

    val explain =
        try {
            runner.explain(compiled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            unavailablePlanFromError(error)
        }
    val baseEvaluation =
        when (explain) {
            is ExplainResult.Available -> {
                val optimizerCost = explain.plan.rawOptimizerCost
                val finalAssessment = staticAssessment?.let { baseline ->
                    refineRiskWithPlan(baseline, explain.plan, normalizedSpec, schema)
                }
                QueryRiskEvaluation(
                    staticAssessment = staticAssessment,
                    finalAssessment = finalAssessment,
                    planStatus =
                        if (optimizerCost.isUsableOptimizerCost()) {
                            QueryPlanStatus.Available
                        } else {
                            QueryPlanStatus.Incomplete
                        },
                    decision =
                        applyRiskGate(finalAssessment, settings.queryRiskGate)
                            .copy(queryFingerprint = fingerprint),
                    optimizerCost = optimizerCost?.takeIf(Double::isFinite)?.takeIf { it >= 0.0 },
                )
            }
            is ExplainResult.Unavailable -> {
                val finalAssessment = staticAssessment?.let { baseline ->
                    preserveStaticRiskForUnavailablePlan(baseline, explain.reasonCode)
                }
                QueryRiskEvaluation(
                    staticAssessment = staticAssessment,
                    finalAssessment = finalAssessment,
                    planStatus = QueryPlanStatus.Unavailable,
                    planUnavailableReason = explain.reasonCode,
                    decision =
                        applyRiskGate(finalAssessment, settings.queryRiskGate)
                            .copy(queryFingerprint = fingerprint),
                )
            }
        }
    if (baseEvaluation.decision.state != RiskGateState.Allowed) {
        return QueryAssessmentOutcome.Success(
            QueryAssessment(normalizedSpec, columns, compiled, warnings, baseEvaluation),
        )
    }

    val requirement =
        when (explain) {
            is ExplainResult.Unavailable ->
                when (val disposition = unavailablePlanDisposition(def, fingerprint, explain)) {
                    is UnavailablePlanDisposition.HardFail ->
                        return QueryAssessmentOutcome.Failure(
                            QueryCoreError(
                                error = QueryError.Execution(disposition.message, normalizedSpec),
                                warnings = warnings,
                                riskEvaluation = baseEvaluation,
                            ),
                        )
                    is UnavailablePlanDisposition.Confirm -> disposition.requirement
                    UnavailablePlanDisposition.Proceed -> null
                }
            is ExplainResult.Available -> optimizerCostConfirmation(def, fingerprint, explain)
        }
    val evaluation =
        if (requirement != null) {
            baseEvaluation.copy(
                decision =
                    baseEvaluation.decision.copy(
                        state = RiskGateState.ConfirmationRequired,
                        reasons = requirement.reasons,
                    ),
                confirmationRequirement = requirement,
            )
        } else {
            baseEvaluation
        }

    return QueryAssessmentOutcome.Success(
        QueryAssessment(normalizedSpec, columns, compiled, warnings, evaluation),
    )
}

suspend fun runQueryCore(
    runner: QueryRunner,
    def: ConnectionDef,
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    confirmation: QueryExecutionConfirmation? = null,
): QueryCoreOutcome {
    val assessment =
        when (val outcome = assessQueryCore(runner, def, spec, schema, settings)) {
            is QueryAssessmentOutcome.Success -> outcome.assessment
            is QueryAssessmentOutcome.Failure -> return QueryCoreOutcome.Failure(outcome.error)
        }
    val baseEvaluation = assessment.riskEvaluation
    val normalizedSpec = assessment.normalizedSpec
    val warnings = assessment.warnings
    val evaluation =
        when (baseEvaluation.decision.state) {
            RiskGateState.Blocked,
            RiskGateState.AssessmentPending ->
                return QueryCoreOutcome.Failure(
                    QueryCoreError(
                        error = QueryError.RiskGate(baseEvaluation, normalizedSpec),
                        warnings = warnings,
                        riskEvaluation = baseEvaluation,
                    ),
                )
            RiskGateState.ConfirmationRequired -> {
                val requirement =
                    checkNotNull(baseEvaluation.confirmationRequirement) {
                        "ConfirmationRequired state must carry a confirmationRequirement"
                    }
                if (confirmation == requirement.confirmation) {
                    baseEvaluation.copy(
                        decision =
                            baseEvaluation.decision.copy(
                                state = RiskGateState.Allowed,
                                reasons = emptyList(),
                            ),
                        confirmationAccepted = true,
                    )
                } else {
                    return QueryCoreOutcome.Failure(
                        QueryCoreError(
                            error =
                                QueryError.ConfirmationRequired(
                                    baseEvaluation,
                                    requirement,
                                    normalizedSpec,
                                ),
                            warnings = warnings,
                            riskEvaluation = baseEvaluation,
                        ),
                    )
                }
            }
            RiskGateState.Allowed -> baseEvaluation
        }

    return executeCompiled(
        runner,
        assessment.compiled,
        warnings,
        normalizedSpec,
        evaluation,
    )
}

private sealed class UnavailablePlanDisposition {
    data class HardFail(val message: String) : UnavailablePlanDisposition()

    data class Confirm(val requirement: QueryConfirmationRequirement) : UnavailablePlanDisposition()

    data object Proceed : UnavailablePlanDisposition()
}

private fun unavailablePlanDisposition(
    def: ConnectionDef,
    queryFingerprint: String,
    explain: ExplainResult.Unavailable,
): UnavailablePlanDisposition =
    when (explain.reasonCode) {
        PlanUnavailableReason.TimedOut ->
            UnavailablePlanDisposition.HardFail("Query plan assessment timed out")
        PlanUnavailableReason.ConnectionFailure ->
            UnavailablePlanDisposition.HardFail(
                "Query plan assessment failed: ${explain.message.ifBlank { "connection error" }}",
            )
        PlanUnavailableReason.ParseFailure -> UnavailablePlanDisposition.Proceed
        PlanUnavailableReason.PermissionDenied,
        PlanUnavailableReason.UnsupportedShape,
        PlanUnavailableReason.CleanupFailure,
        PlanUnavailableReason.ExecutionFailure ->
            UnavailablePlanDisposition.Confirm(
                planUnavailableConfirmation(def, queryFingerprint, explain),
            )
    }

private fun planUnavailableConfirmation(
    def: ConnectionDef,
    queryFingerprint: String,
    explain: ExplainResult.Unavailable,
): QueryConfirmationRequirement =
    QueryConfirmationRequirement(
        confirmation =
            QueryExecutionConfirmation(
                connectionId = def.id,
                connectionFingerprint = def.credentialFingerprint(),
                queryFingerprint = queryFingerprint,
                conditions =
                    setOf(
                        QueryConfirmationCondition(
                            QueryConfirmationReasonCode.PlanUnavailable,
                            explain.reasonCode.name,
                        ),
                    ),
            ),
        reasons =
            listOf(
                RiskDecisionReason(
                    code = "plan_unavailable",
                    message =
                        "Query plan assessment is unavailable: ${explain.message.ifBlank { "plan check failed" }}. Confirm to run with read-only, row-limit, and timeout safeguards.",
                ),
            ),
    )

private fun optimizerCostConfirmation(
    def: ConnectionDef,
    queryFingerprint: String,
    explain: ExplainResult.Available,
): QueryConfirmationRequirement? {
    val cost = explain.plan.rawOptimizerCost?.takeIf(Double::isFinite)?.takeIf { it >= 0.0 }
    if (cost != null) return null
    return QueryConfirmationRequirement(
        confirmation =
            QueryExecutionConfirmation(
                connectionId = def.id,
                connectionFingerprint = def.credentialFingerprint(),
                queryFingerprint = queryFingerprint,
                conditions =
                    setOf(
                        QueryConfirmationCondition(
                            QueryConfirmationReasonCode.OptimizerCostUnavailable,
                            def.dialect.name,
                        ),
                    ),
            ),
        reasons =
            listOf(
                RiskDecisionReason(
                    code = "optimizer_cost_unavailable",
                    message =
                        "The query plan did not provide a valid optimizer cost. Confirm to run with read-only, row-limit, and timeout safeguards.",
                ),
            ),
    )
}

private fun Double?.isUsableOptimizerCost(): Boolean = this != null && isFinite() && this >= 0.0

private suspend fun executeCompiled(
    runner: QueryRunner,
    compiled: CompiledQuery,
    warnings: List<String>,
    normalizedSpec: QuerySpec,
    evaluation: QueryRiskEvaluation,
): QueryCoreOutcome {
    val result =
        when (val execute = runner.executeQuery(compiled, DEFAULT_TIMEOUT_MS)) {
            is Outcome.Ok -> execute.value
            is Outcome.Err ->
                return QueryCoreOutcome.Failure(
                    QueryCoreError(
                        error =
                            QueryError.Execution(
                                conciseErrorMessage(execute.message, "Query failed"),
                                normalizedSpec,
                            ),
                        warnings = warnings,
                        riskEvaluation = evaluation,
                    ),
                )
        }

    val limitTruncated = !normalizedSpec.uncapped && result.rows.size > normalizedSpec.limit
    val rows =
        if (limitTruncated) {
            result.rows.take(normalizedSpec.limit)
        } else {
            result.rows
        }
    val combinedWarnings =
        (warnings + result.warnings).let { all ->
            if (rows.isEmpty()) all.filterNot { it == LARGE_LIMIT_WARNING } else all
        }
    val alignedEvaluation = if (rows.isEmpty()) alignEmptyResultRisk(evaluation) else evaluation

    return QueryCoreOutcome.Success(
        result =
            result.copy(
                rows = rows,
                rowCount = rows.size,
                truncated = result.truncated || limitTruncated,
                warnings = combinedWarnings,
            ),
        historySpec = normalizedSpec,
        riskEvaluation = alignedEvaluation,
    )
}
