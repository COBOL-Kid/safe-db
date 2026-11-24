package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.zaxxer.hikari.HikariDataSource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

const val EXPLAIN_OUTER_TIMEOUT_MARGIN_MS = 1_000

class MssqlState(var dataSource: HikariDataSource?, val def: ConnectionDef, val password: String)

sealed class Adapter {
    abstract suspend fun test(): String

    abstract suspend fun introspect(): Schema

    abstract suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult

    abstract suspend fun explain(
        compiled: CompiledQuery,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ExplainResult

    abstract fun close()

    // Nested so Adapter stays sealed; cannot be private because public dialect types extend it.
    abstract class PooledAdapter(private val dataSource: HikariDataSource) : Adapter() {
        protected abstract fun test(dataSource: HikariDataSource): String

        protected abstract fun introspect(dataSource: HikariDataSource): Schema

        protected abstract fun executeQuery(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult

        protected abstract fun explain(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult

        final override suspend fun test(): String = withContext(Dispatchers.IO) { test(dataSource) }

        final override suspend fun introspect(): Schema =
            withContext(Dispatchers.IO) { introspect(dataSource) }

        final override suspend fun executeQuery(
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult =
            withContext(Dispatchers.IO) { executeQuery(dataSource, compiled, timeoutMs) }

        final override suspend fun explain(
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult = withContext(Dispatchers.IO) { explain(dataSource, compiled, timeoutMs) }

        final override fun close() {
            closeDataSource(dataSource)
        }
    }

    data class Postgres(val dataSource: HikariDataSource) : PooledAdapter(dataSource) {
        override fun test(dataSource: HikariDataSource): String = PgAdapter.test(dataSource)

        override fun introspect(dataSource: HikariDataSource): Schema =
            PgAdapter.introspect(dataSource)

        override fun executeQuery(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult = PgAdapter.executeQuery(dataSource, compiled, timeoutMs)

        override fun explain(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult = PgAdapter.explain(dataSource, compiled, timeoutMs)
    }

    data class MySql(val dataSource: HikariDataSource) : PooledAdapter(dataSource) {
        override fun test(dataSource: HikariDataSource): String = MySqlAdapter.test(dataSource)

        override fun introspect(dataSource: HikariDataSource): Schema =
            MySqlAdapter.introspect(dataSource)

        override fun executeQuery(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult = MySqlAdapter.executeQuery(dataSource, compiled, timeoutMs)

        override fun explain(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult = MySqlAdapter.explain(dataSource, compiled, timeoutMs)
    }

    data class Mssql(val state: MssqlState) : Adapter() {
        private val lock = ReentrantLock()
        private var closed = false

        override suspend fun test(): String =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val ds = ensureDataSource()
                    MssqlAdapter.test(ds)
                }
            }

        override suspend fun introspect(): Schema =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val ds = ensureDataSource()
                    MssqlAdapter.introspect(ds)
                }
            }

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val ds = ensureDataSource()
                    try {
                        MssqlAdapter.executeQuery(ds, compiled, timeoutMs)
                    } catch (e: QueryTimedOutException) {
                        if (e.recyclePool) recycleDataSource()
                        throw e
                    }
                }
            }

        override suspend fun explain(compiled: CompiledQuery, timeoutMs: Int): ExplainResult =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val ds = ensureDataSource()
                    try {
                        MssqlAdapter.explain(ds, compiled, timeoutMs)
                    } catch (e: QueryTimedOutException) {
                        if (e.recyclePool) recycleDataSource()
                        throw e
                    }
                }
            }

        override fun close() {
            closeLocked()
        }

        internal fun closeLocked() {
            lock.withLock {
                closed = true
                recycleDataSource()
            }
        }

        private fun recycleDataSource() {
            state.dataSource?.let(::closeDataSource)
            state.dataSource = null
        }

        private fun ensureDataSource(): HikariDataSource {
            if (closed) throw IllegalStateException("SQL Server adapter is closed")
            if (state.dataSource == null) {
                state.dataSource = createDataSource(state.def, state.password)
            }
            return state.dataSource!!
        }
    }

    data class Oracle(val dataSource: HikariDataSource) : PooledAdapter(dataSource) {
        override fun test(dataSource: HikariDataSource): String = OracleAdapter.test(dataSource)

        override fun introspect(dataSource: HikariDataSource): Schema =
            OracleAdapter.introspect(dataSource)

        override fun executeQuery(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult = OracleAdapter.executeQuery(dataSource, compiled, timeoutMs)

        override fun explain(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult = OracleAdapter.explain(dataSource, compiled, timeoutMs)
    }

    data class Db2(val dataSource: HikariDataSource) : PooledAdapter(dataSource) {
        override fun test(dataSource: HikariDataSource): String = Db2Adapter.test(dataSource)

        override fun introspect(dataSource: HikariDataSource): Schema =
            Db2Adapter.introspect(dataSource)

        override fun executeQuery(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): QueryResult = Db2Adapter.executeQuery(dataSource, compiled, timeoutMs)

        override fun explain(
            dataSource: HikariDataSource,
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): ExplainResult = Db2Adapter.explain(dataSource, compiled, timeoutMs)
    }

    companion object {
        suspend fun connect(
            def: ConnectionDef,
            password: String,
            connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        ): Adapter {
            def.validate().getOrElse { throw IllegalArgumentException(it.message) }
            return try {
                // Driver connect/login timeouts enforce the handshake deadline inside JDBC.
                // runInterruptible is a second hard stop: withTimeout alone cannot unblock
                // a thread stuck in native poll (pgjdbc doAuthentication).
                withTimeout(connectTimeoutMs.milliseconds) {
                    runInterruptible(Dispatchers.IO) {
                        when (def.dialect) {
                            Dialect.Postgres ->
                                Postgres(createDataSource(def, password, connectTimeoutMs))
                            Dialect.MySql ->
                                MySql(createDataSource(def, password, connectTimeoutMs))
                            Dialect.Mssql ->
                                Mssql(
                                    MssqlState(
                                        createDataSource(def, password, connectTimeoutMs),
                                        def,
                                        password,
                                    ),
                                )
                            Dialect.Oracle ->
                                Oracle(createDataSource(def, password, connectTimeoutMs))
                            Dialect.Db2 -> Db2(createDataSource(def, password, connectTimeoutMs))
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                throw ConnectionFailedException(formatConnectionFailure(error, def), error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ConnectionFailedException(formatConnectionFailure(error, def), error)
            }
        }

        suspend fun introspectWithTimeout(adapter: Adapter): Schema =
            withTimeout(INTROSPECTION_TIMEOUT_MS.milliseconds) { adapter.introspect() }

        suspend fun explainWithTimeout(
            adapter: Adapter,
            compiled: CompiledQuery,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        ): ExplainResult {
            val outerTimeoutMs =
                (timeoutMs.toLong() + EXPLAIN_OUTER_TIMEOUT_MARGIN_MS)
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
            return awaitExplain(outerTimeoutMs) { adapter.explain(compiled, timeoutMs) }
        }
    }
}

internal suspend fun awaitExplain(
    timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    block: suspend () -> ExplainResult,
): ExplainResult =
    try {
        withTimeoutOrNull(timeoutMs.milliseconds) { block() }
            ?: ExplainResult.Unavailable(
                PlanUnavailableReason.TimedOut,
                "Query plan assessment timed out",
            )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        unavailablePlanFromError(error)
    }

fun unavailablePlanFromError(error: Throwable): ExplainResult.Unavailable {
    val chain = generateSequence(error) { it.cause }.toList()
    val texts = chain.mapNotNull { it.message }.joinToString("\n")
    val reason =
        when {
            isPlanPermissionDenied(texts) -> PlanUnavailableReason.PermissionDenied
            isServerPlanTimeout(chain, texts) -> PlanUnavailableReason.TimedOut
            isPlanConnectivityFailure(chain, texts) -> PlanUnavailableReason.ConnectionFailure
            else -> PlanUnavailableReason.ExecutionFailure
        }
    return ExplainResult.Unavailable(
        reason,
        conciseErrorMessage(
            error.message?.takeIf { it.isNotBlank() }
                ?: texts.ifBlank { "Query plan assessment failed" },
            "Query plan assessment failed",
        ),
    )
}

private fun isPlanPermissionDenied(texts: String): Boolean {
    val lower = texts.lowercase()
    return "permission" in lower || "denied" in lower
}

private fun isPlanConnectivityFailure(chain: List<Throwable>, texts: String): Boolean =
    chain.any { error ->
        error is QueryTimedOutException ||
            error is SQLTimeoutException ||
            error is SQLTransientConnectionException ||
            error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is NoRouteToHostException ||
            error is PortUnreachableException
    } || isTimeoutOrConnectionFailure(texts)

fun columnsFromCompiledSql(sql: String, dialect: Dialect): List<String> {
    val upper = sql.uppercase()
    val fromIdx = upper.indexOf("\nFROM ").takeIf { it >= 0 } ?: upper.indexOf(" FROM ")
    val selectIdx = upper.indexOf("SELECT")
    if (fromIdx < 0 || selectIdx < 0) return emptyList()
    var selectList = sql.substring(selectIdx + "SELECT".length, fromIdx).trim()
    if (dialect == Dialect.Mssql && selectList.uppercase().startsWith("TOP ")) {
        val parts = selectList.trimStart().removePrefix("TOP ").trimStart().split(' ', limit = 2)
        if (parts.size == 2) selectList = parts[1].trimStart()
    }
    if (selectList == "*") return emptyList()
    return selectList.split(',').map { part ->
        val trimmed = part.trim()
        val upperPart = trimmed.uppercase()
        when {
            " AS " in upperPart -> {
                val asIdx = upperPart.lastIndexOf(" AS ")
                unquoteIdentifier(trimmed.substring(asIdx + 4).trim(), dialect)
            }
            '.' in trimmed -> {
                val dot = trimmed.lastIndexOf('.')
                unquoteIdentifier(trimmed.substring(dot + 1).trim(), dialect)
            }
            else -> unquoteIdentifier(trimmed, dialect)
        }
    }
}

private fun unquoteIdentifier(identifier: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres,
        Dialect.Oracle,
        Dialect.Db2 -> identifier.trim('"')
        Dialect.MySql -> identifier.trim('`')
        Dialect.Mssql -> identifier.removePrefix("[").removeSuffix("]")
    }
