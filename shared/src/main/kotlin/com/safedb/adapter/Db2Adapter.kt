package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException

object Db2Adapter {
    fun test(dataSource: HikariDataSource): String =
        probeVersion(dataSource, "SELECT 1 FROM SYSIBM.SYSDUMMY1")

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            return if (conn.syscatAvailable()) introspectSyscat(conn) else introspectSysibm(conn)
        }
    }

    // SYSCAT exists on LUW. Only an undefined object (-204 / 42704) means z/OS. Authorization
    // errors and timeouts must surface instead of running the other catalog.
    private fun Connection.syscatAvailable(): Boolean {
        try {
            metadataRows("SELECT TABNAME FROM SYSCAT.TABLES FETCH FIRST 1 ROW ONLY") {
                readString(it, 1)
            }
            return true
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) return false
            throw e
        }
    }

    fun executeQuery(
        dataSource: HikariDataSource,
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): QueryResult =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.isReadOnly = true
            // Cursor stability locks the row under the cursor. Uncommitted read matches the
            // other dialects and does not block an updater of a row this query is fetching.
            conn.transactionIsolation = Connection.TRANSACTION_READ_UNCOMMITTED
            try {
                val result =
                    prepareStatement(conn, compiled, Dialect.Db2).use { ps ->
                        ps.queryTimeout = jdbcQueryTimeoutSeconds(timeoutMs)
                        ps.executeQuery().use { rs ->
                            decodeQueryResult(rs, compiled.sql, Dialect.Db2)
                        }
                    }
                // Close the cursor before commit so a budget stop does not drain unread rows.
                conn.commit()
                result
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            }
        }

    fun explain(
        dataSource: HikariDataSource,
        compiled: CompiledQuery,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ExplainResult =
        dataSource.connection.use { conn ->
            val timed = Db2TimedJdbc(conn, jdbcQueryTimeoutSeconds(timeoutMs))
            conn.isReadOnly = true
            // SYSCAT exists on LUW; z/OS exposes only SYSIBM, so the introspection probe
            // doubles as the EXPLAIN platform selector.
            if (conn.syscatAvailable()) {
                explainLuw(timed, compiled)
            } else {
                explainZos(timed, compiled)
            }
        }

    internal enum class ExplainTablePresence {
        Present,
        Absent,
        Failed,
    }

    internal sealed interface LuwExplainPrefix {
        data class Found(val prefix: String) : LuwExplainPrefix

        data object Missing : LuwExplainPrefix

        data object Unreadable : LuwExplainPrefix
    }

    internal data class CountedForeignKey(val key: MetadataForeignKey, val columnCount: Int)

    // Delimited identifier. Control characters and over-long names are rejected so a
    // CURRENT SQLID or session authorization id cannot break out of the qualifier.
    internal fun quoteDb2Ident(name: String): String? {
        if (name.length > 128 || name.any { it.isISOControl() }) return null
        return "\"${name.replace("\"", "\"\"")}\""
    }

    internal fun Throwable.isUndefinedObject(): Boolean {
        val sqlException = this as? SQLException ?: return false
        return sqlException.sqlState == "42704" ||
            sqlException.errorCode == -204 ||
            sqlException.message?.contains("-204", ignoreCase = true) == true ||
            sqlException.message?.contains("undefined name", ignoreCase = true) == true
    }
}
