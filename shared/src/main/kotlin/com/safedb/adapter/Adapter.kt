package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class MssqlState(var dataSource: HikariDataSource?, val def: ConnectionDef, val password: String)

sealed class Adapter {
    abstract suspend fun test(): String

    abstract suspend fun introspect(): Schema

    abstract suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult

    abstract suspend fun explain(compiled: CompiledQuery): ExplainResult

    data class Postgres(val dataSource: HikariDataSource) : Adapter() {
        override suspend fun test(): String =
            withContext(Dispatchers.IO) { PgAdapter.test(dataSource) }

        override suspend fun introspect(): Schema =
            withContext(Dispatchers.IO) { PgAdapter.introspect(dataSource) }

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
            withContext(Dispatchers.IO) { PgAdapter.executeQuery(dataSource, compiled, timeoutMs) }

        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            withContext(Dispatchers.IO) { PgAdapter.explain(dataSource, compiled) }
    }

    data class MySql(val dataSource: HikariDataSource) : Adapter() {
        override suspend fun test(): String =
            withContext(Dispatchers.IO) { MySqlAdapter.test(dataSource) }

        override suspend fun introspect(): Schema =
            withContext(Dispatchers.IO) { MySqlAdapter.introspect(dataSource) }

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
            withContext(Dispatchers.IO) {
                MySqlAdapter.executeQuery(dataSource, compiled, timeoutMs)
            }

        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            withContext(Dispatchers.IO) { MySqlAdapter.explain(dataSource, compiled) }
    }

    data class Mssql(val state: MssqlState) : Adapter() {
        private val lock = ReentrantLock()

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
                        state.dataSource?.let(::closeDataSource)
                        state.dataSource = null
                        runCatching { createDataSource(state.def, state.password) }
                            .onSuccess { state.dataSource = it }
                        throw e
                    }
                }
            }

        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            withContext(Dispatchers.IO) {
                val explainDs = createDataSource(state.def, state.password)
                try {
                    MssqlAdapter.explain(explainDs, compiled)
                } finally {
                    closeDataSource(explainDs)
                }
            }

        private fun ensureDataSource(): HikariDataSource {
            if (state.dataSource == null) {
                state.dataSource = createDataSource(state.def, state.password)
            }
            return state.dataSource!!
        }
    }

    data class Oracle(val dataSource: HikariDataSource) : Adapter() {
        override suspend fun test(): String =
            withContext(Dispatchers.IO) { OracleAdapter.test(dataSource) }

        override suspend fun introspect(): Schema =
            withContext(Dispatchers.IO) { OracleAdapter.introspect(dataSource) }

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
            withContext(Dispatchers.IO) {
                OracleAdapter.executeQuery(dataSource, compiled, timeoutMs)
            }

        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            withContext(Dispatchers.IO) { OracleAdapter.explain(dataSource, compiled) }
    }

    companion object {
        suspend fun connect(def: ConnectionDef, password: String): Adapter {
            def.validate().getOrElse { throw IllegalArgumentException(it.message) }
            return withTimeout(CONNECT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    when (def.dialect) {
                        Dialect.Postgres -> Postgres(createDataSource(def, password))
                        Dialect.MySql -> MySql(createDataSource(def, password))
                        Dialect.Mssql ->
                            Mssql(MssqlState(createDataSource(def, password), def, password))
                        Dialect.Oracle -> Oracle(createDataSource(def, password))
                    }
                }
            }
        }

        suspend fun introspectWithTimeout(adapter: Adapter): Schema =
            withTimeout(INTROSPECTION_TIMEOUT_MS) { adapter.introspect() }

        suspend fun explainWithTimeout(adapter: Adapter, compiled: CompiledQuery): ExplainResult =
            try {
                withTimeoutOrNull(DEFAULT_TIMEOUT_MS.toLong()) { adapter.explain(compiled) }
                    ?: ExplainResult.Unavailable(
                        com.safedb.model.PlanUnavailableReason.TimedOut,
                        "Query plan assessment timed out",
                    )
            } catch (error: SQLException) {
                val permissionDenied =
                    error.message?.contains("permission", ignoreCase = true) == true ||
                        error.message?.contains("denied", ignoreCase = true) == true
                ExplainResult.Unavailable(
                    if (permissionDenied) com.safedb.model.PlanUnavailableReason.PermissionDenied
                    else com.safedb.model.PlanUnavailableReason.ExecutionFailure,
                    error.message ?: "Query plan assessment failed",
                )
            }
    }

    fun close() {
        when (this) {
            is Postgres -> closeDataSource(dataSource)
            is MySql -> closeDataSource(dataSource)
            is Mssql -> state.dataSource?.let(::closeDataSource)
            is Oracle -> closeDataSource(dataSource)
        }
    }
}

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
        Dialect.Oracle -> identifier.trim('"')
        Dialect.MySql -> identifier.trim('`')
        Dialect.Mssql -> identifier.removePrefix("[").removeSuffix("]")
    }
