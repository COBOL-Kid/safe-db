package com.safedb.query

import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.Settings

const val COST_GUARD_PREFIX = "COST_GUARD_BLOCKED:"
const val DEFAULT_TIMEOUT_MS = 10_000

data class QueryCoreError(
    val message: String,
    val warnings: List<String> = emptyList(),
)

interface QueryRunner {
    suspend fun explain(compiled: CompiledQuery): ExplainResult
    suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult>
}

sealed class QueryCoreOutcome {
    data class Success(val result: QueryResult) : QueryCoreOutcome()
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
    val (validated, outcome) = when (val validation = validateQuery(spec, schema, settings.blockedSchemas)) {
        is Outcome.Ok -> validation.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(QueryCoreError(message = validation.message))
    }

    val compiled = when (val result = compileValidated(validated, def.dialect)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(
            QueryCoreError(message = result.message, warnings = outcome.warnings),
        )
    }

    return executeCompiled(runner, compiled, outcome, def, settings, force)
}

private suspend fun executeCompiled(
    runner: QueryRunner,
    compiled: CompiledQuery,
    outcome: ValidationOutcome,
    def: ConnectionDef,
    settings: Settings,
    force: Boolean,
): QueryCoreOutcome {
    val warnings = outcome.warnings.toMutableList()

    val explainResult = try {
        runner.explain(compiled)
    } catch (error: Exception) {
        ExplainResult.Unavailable("EXPLAIN failed: $error")
    }

    val (explainFailed, overCost) = when (explainResult) {
        is ExplainResult.Estimated -> {
            val threshold = settings.costThreshold(def.dialect)
            val over = explainResult.cost > threshold
            if (over) {
                warnings.add(
                    "Estimated query cost (${"%.0f".format(explainResult.cost)}) exceeds threshold (${"%.0f".format(threshold)}) — this may be slow",
                )
            }
            false to over
        }
        is ExplainResult.Unavailable -> {
            warnings.add(explainResult.reason)
            true to false
        }
    }

    if ((explainFailed || overCost) && !force) {
        val reason = when {
            explainFailed && overCost -> "EXPLAIN failed and estimated cost exceeds threshold"
            explainFailed -> "EXPLAIN failed"
            else -> "Estimated query cost exceeds threshold"
        }
        return QueryCoreOutcome.Failure(
            QueryCoreError(
                message = "$COST_GUARD_PREFIX$reason. Confirm to run this query anyway.",
                warnings = warnings.toList(),
            ),
        )
    }

    val result = when (val execute = runner.executeQuery(compiled, DEFAULT_TIMEOUT_MS)) {
        is Outcome.Ok -> execute.value
        is Outcome.Err -> return QueryCoreOutcome.Failure(
            QueryCoreError(message = execute.message, warnings = warnings.toList()),
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
        result.copy(
            rows = rows,
            rowCount = rows.size,
            truncated = result.truncated || limitTruncated,
            warnings = mergedWarnings,
        ),
    )
}
