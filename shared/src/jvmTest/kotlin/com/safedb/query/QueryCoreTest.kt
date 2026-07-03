package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
import com.safedb.model.IndexInfo
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryCoreTest {
    @Test
    fun unavailableExplainTriggersCostGuardWhenNotForced() = runBlocking {
        val runner = StubRunner(explainResult = ExplainResult.Unavailable("planner disabled"))
        val outcome = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default(),
            force = false,
        )
        assertTrue(outcome is QueryCoreOutcome.Failure)
        assertTrue(outcome.error.message.startsWith(COST_GUARD_PREFIX))
        assertTrue(outcome.error.warnings.any { it.contains("planner disabled") })
    }

    @Test
    fun unavailableExplainRunsWhenForced() = runBlocking {
        val runner = StubRunner(
            explainResult = ExplainResult.Unavailable("planner disabled"),
            execute = Outcome.ok(sampleResult()),
        )
        val outcome = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default(),
            force = true,
        )
        assertTrue(outcome is QueryCoreOutcome.Success)
        assertEquals(1, outcome.result.rowCount)
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
            force = true,
        )
        assertTrue(outcome is QueryCoreOutcome.Failure)
        assertTrue(outcome.error.message.contains("timeout"))
    }

    @Test
    fun overCostExplainBlocksUntilForced() = runBlocking {
        val runner = StubRunner(explainResult = ExplainResult.Estimated(500_000.0))
        val blocked = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default().copy(explainCostThreshold = 100_000.0),
            force = false,
        )
        assertTrue(blocked is QueryCoreOutcome.Failure)
        assertTrue(blocked.error.message.startsWith(COST_GUARD_PREFIX))

        val forced = runQueryCore(
            runner,
            sampleConnection(),
            sampleSpec(),
            sampleSchema(),
            Settings.default().copy(explainCostThreshold = 100_000.0),
            force = true,
        )
        assertTrue(forced is QueryCoreOutcome.Success)
    }

    private class StubRunner(
        private val explainResult: ExplainResult = ExplainResult.Estimated(1.0),
        private val execute: Outcome<QueryResult> = Outcome.ok(sampleResult()),
    ) : QueryRunner {
        var executeCalls = 0

        override suspend fun explain(compiled: CompiledQuery): ExplainResult = explainResult

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> {
            executeCalls++
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

private fun sampleSchema() = Schema(
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
            ),
            indexes = listOf(
                IndexInfo(
                    name = "users_pkey",
                    columns = listOf("id"),
                    supportsEquality = true,
                    isUnique = true,
                    isPrimary = true,
                ),
            ),
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
