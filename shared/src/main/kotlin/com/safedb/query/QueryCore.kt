package com.safedb.query

import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
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

suspend fun runQueryCore(
    runner: QueryRunner,
    def: ConnectionDef,
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    confirmation: QueryExecutionConfirmation? = null,
): QueryCoreOutcome {
    val (validated, normalizedSpec, warnings, staticAssessment) =
        when (val assessed = assessValidatedQueryRisk(spec, schema, settings, def.dialect)) {
            is Outcome.Ok -> assessed.value
            is Outcome.Err ->
                return QueryCoreOutcome.Failure(
                    QueryCoreError(QueryError.Validation(assessed.message))
                )
        }

    val compiled =
        when (val result = compileValidated(validated, def.dialect)) {
            is Outcome.Ok -> result.value
            is Outcome.Err ->
                return QueryCoreOutcome.Failure(
                    QueryCoreError(
                        error = QueryError.Compilation(result.message, normalizedSpec),
                        warnings = warnings,
                    )
                )
        }

    // Plan availability and a usable optimizer cost remain execution safeguards even when
    // descriptive query-risk scoring is disabled.
    val explain =
        try {
            runner.explain(compiled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ExplainResult.Unavailable(
                PlanUnavailableReason.ExecutionFailure,
                error.message ?: "Query plan assessment failed",
            )
        }
    val fingerprint = staticAssessment?.queryFingerprint ?: queryFingerprint(validated)
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
        return QueryCoreOutcome.Failure(
            QueryCoreError(
                error = QueryError.RiskGate(baseEvaluation, normalizedSpec),
                warnings = warnings,
                riskEvaluation = baseEvaluation,
            )
        )
    }

    val requirement = confirmationRequirement(def, fingerprint, explain)
    // Accept only an exact match across connection, credentials, query, and plan condition.
    val confirmationAccepted = requirement != null && confirmation == requirement.confirmation
    val evaluation =
        when {
            requirement == null -> baseEvaluation
            confirmationAccepted ->
                baseEvaluation.copy(
                    confirmationRequirement = requirement,
                    confirmationAccepted = true,
                )
            else ->
                baseEvaluation.copy(
                    decision =
                        baseEvaluation.decision.copy(
                            state = RiskGateState.ConfirmationRequired,
                            reasons = requirement.reasons,
                        ),
                    confirmationRequirement = requirement,
                )
        }
    if (requirement != null && !confirmationAccepted) {
        return QueryCoreOutcome.Failure(
            QueryCoreError(
                error = QueryError.ConfirmationRequired(evaluation, requirement, normalizedSpec),
                warnings = warnings,
                riskEvaluation = evaluation,
            )
        )
    }

    return executeCompiled(runner, compiled, warnings, normalizedSpec, evaluation)
}

private fun confirmationRequirement(
    def: ConnectionDef,
    queryFingerprint: String,
    explain: ExplainResult,
): QueryConfirmationRequirement? {
    val (condition, reason) =
        when (explain) {
            is ExplainResult.Unavailable ->
                QueryConfirmationCondition(
                    QueryConfirmationReasonCode.PlanUnavailable,
                    explain.reasonCode.name,
                ) to
                    RiskDecisionReason(
                        code = "plan_unavailable",
                        message =
                            "Query plan assessment is unavailable: ${explain.message}. Confirm to run with read-only, row-limit, and timeout safeguards.",
                    )
            is ExplainResult.Available -> {
                val cost =
                    explain.plan.rawOptimizerCost?.takeIf(Double::isFinite)?.takeIf { it >= 0.0 }
                when {
                    cost == null ->
                        QueryConfirmationCondition(
                            QueryConfirmationReasonCode.OptimizerCostUnavailable,
                            def.dialect.name,
                        ) to
                            RiskDecisionReason(
                                code = "optimizer_cost_unavailable",
                                message =
                                    "The query plan did not provide a valid optimizer cost. Confirm to run with read-only, row-limit, and timeout safeguards.",
                            )
                    else -> return null
                }
            }
        }
    return QueryConfirmationRequirement(
        confirmation =
            QueryExecutionConfirmation(
                connectionId = def.id,
                connectionFingerprint = def.credentialFingerprint(),
                queryFingerprint = queryFingerprint,
                conditions = setOf(condition),
            ),
        reasons = listOf(reason),
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
                        error = QueryError.Execution(execute.message, normalizedSpec),
                        warnings = warnings,
                        riskEvaluation = evaluation,
                    )
                )
        }

    val limitTruncated = result.rows.size > normalizedSpec.limit
    val rows =
        if (limitTruncated) {
            result.rows.take(normalizedSpec.limit)
        } else {
            result.rows
        }

    return QueryCoreOutcome.Success(
        result =
            result.copy(
                rows = rows,
                rowCount = rows.size,
                truncated = result.truncated || limitTruncated,
                warnings = warnings + result.warnings,
            ),
        historySpec = normalizedSpec,
        riskEvaluation = evaluation,
    )
}
