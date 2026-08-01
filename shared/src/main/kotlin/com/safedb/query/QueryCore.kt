package com.safedb.query

import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.Settings

const val DEFAULT_TIMEOUT_MS = 10_000

data class QueryCoreError(
    val error: QueryError,
    val warnings: List<String> = emptyList(),
) {
    constructor(
        message: String,
        warnings: List<String> = emptyList(),
        historySpec: QuerySpec? = null,
    ) : this(QueryError.Execution(message, historySpec), warnings)

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

    data class Compilation(
        override val message: String,
        override val historySpec: QuerySpec,
    ) : QueryError()

    data class CostGuard(
        val reason: String,
        override val historySpec: QuerySpec,
    ) : QueryError() {
        override val message: String = "$reason. Confirm to run this query anyway."
    }

    data class RiskGate(
        val decision: QueryRiskDecision,
        val assessment: QueryRiskAssessment,
        override val historySpec: QuerySpec,
    ) : QueryError() {
        override val message: String = decision.reasons
            .joinToString(separator = " ") { it.message }
            .ifEmpty { "The query risk gate blocks this query." }
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
        val riskAssessment: QueryRiskAssessment?,
        val riskDecision: QueryRiskDecision,
    ) : QueryCoreOutcome()
    data class Failure(val error: QueryCoreError) : QueryCoreOutcome()
}

suspend fun runQueryCore(
    runner: QueryRunner,
    def: ConnectionDef,
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    force: Boolean,
): QueryCoreOutcome {
    val (validated, outcome) = when (
        val validation = validateQuery(spec, schema, settings.blockedSchemas, def.dialect)
    ) {
        is Outcome.Ok -> validation.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(
            QueryCoreError(QueryError.Validation(validation.message)),
        )
    }
    val normalizedSpec = validated.spec()

    val assessment = if (settings.queryRiskGate == com.safedb.model.QueryRiskGate.Disabled) {
        null
    } else {
        assessStaticQueryRisk(validated, schema, def.dialect)
    }
    val decision = applyRiskGate(
        assessment = assessment,
        userSetting = settings.queryRiskGate,
        resources = normalizedSpec.tables.mapTo(mutableSetOf()) { "${it.schema}.${it.name}" },
    )
    if (decision.state != RiskGateState.Allowed) {
        return QueryCoreOutcome.Failure(
            QueryCoreError(
                error = QueryError.RiskGate(decision, requireNotNull(assessment), normalizedSpec),
                warnings = outcome.warnings,
            ),
        )
    }

    val compiled = when (val result = compileValidated(validated, def.dialect)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(
            QueryCoreError(
                error = QueryError.Compilation(result.message, normalizedSpec),
                warnings = outcome.warnings,
            ),
        )
    }

    return executeCompiled(runner, compiled, outcome, normalizedSpec, assessment, decision)
}

private suspend fun executeCompiled(
    runner: QueryRunner,
    compiled: CompiledQuery,
    outcome: ValidationOutcome,
    normalizedSpec: QuerySpec,
    assessment: QueryRiskAssessment?,
    decision: QueryRiskDecision,
): QueryCoreOutcome {
    val warnings = outcome.warnings.toMutableList()

    val result = when (val execute = runner.executeQuery(compiled, DEFAULT_TIMEOUT_MS)) {
        is Outcome.Ok -> execute.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(
            QueryCoreError(
                error = QueryError.Execution(execute.message, normalizedSpec),
                warnings = warnings.toList(),
            ),
        )
    }

    val limitTruncated = result.rows.size > outcome.limit
    val rows = if (limitTruncated) {
        result.rows.take(outcome.limit)
    } else {
        result.rows
    }

    val mergedWarnings = warnings.toMutableList()
    mergedWarnings.addAll(result.warnings)

    return QueryCoreOutcome.Success(
        result = result.copy(
            rows = rows,
            rowCount = rows.size,
            truncated = result.truncated || limitTruncated,
            warnings = mergedWarnings,
        ),
        historySpec = normalizedSpec,
        riskAssessment = assessment,
        riskDecision = decision,
    )
}
