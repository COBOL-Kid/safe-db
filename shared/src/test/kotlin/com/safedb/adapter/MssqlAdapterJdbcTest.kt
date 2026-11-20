package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.ExplainResult
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.ResultCell
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MssqlAdapterJdbcTest {
    @Test
    fun mssqlExecuteQueryUsesJdbcTransactionAndRestoresLockTimeout() {
        val jdbc =
            script(
                onQuery = { sql ->
                    assertTrue("FROM [dbo].[orders]" in sql)
                    rows(listOf("id"), listOf(listOf(42L)), listOf("BIGINT"))
                },
            )

        val result =
            MssqlAdapter.executeQuery(
                jdbc.dataSource(),
                CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                2_500,
            )

        assertEquals(42L, assertIs<ResultCell.IntegerCell>(result.rows.single().single()).value)
        assertTrue(jdbc.sql.none { it.contains("BEGIN TRANSACTION", ignoreCase = true) })
        assertTrue(jdbc.sql.none { it.contains("COMMIT TRANSACTION", ignoreCase = true) })
        assertTrue(jdbc.sql.none { it.contains("ROLLBACK TRANSACTION", ignoreCase = true) })
        assertTrue(jdbc.sql.none { it.contains("SET TRANSACTION ISOLATION", ignoreCase = true) })
        assertEquals(
            listOf(
                "networkTimeout=2500",
                "autoCommit=false",
                "isolation=${Connection.TRANSACTION_READ_UNCOMMITTED}",
                "exec:SET LOCK_TIMEOUT 2500",
                "query:SELECT [id] FROM [dbo].[orders]",
                "commit",
                "exec:SET LOCK_TIMEOUT -1",
                "networkTimeout=0",
                "close",
            ),
            jdbc.events,
        )
    }

    @Test
    fun mssqlExecuteQueryRollsBackAndRestoresLockTimeoutOnFailure() {
        val jdbc = script(onQuery = { throw SQLException("boom", "S0001", 50000) })

        val error =
            assertFailsWith<SQLException> {
                MssqlAdapter.executeQuery(
                    jdbc.dataSource(),
                    CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                    3_000,
                )
            }

        assertEquals("boom", error.message)
        assertEquals(
            listOf(
                "networkTimeout=3000",
                "autoCommit=false",
                "isolation=${Connection.TRANSACTION_READ_UNCOMMITTED}",
                "exec:SET LOCK_TIMEOUT 3000",
                "query:SELECT [id] FROM [dbo].[orders]",
                "rollback",
                "exec:SET LOCK_TIMEOUT -1",
                "networkTimeout=0",
                "close",
            ),
            jdbc.events,
        )
    }

    @Test
    fun mssqlExecuteQueryMapsLockTimeoutErrorCode1222ToQueryTimedOutException() {
        val failure = SQLException("Lock request time out period exceeded.", "HY000", 1222)
        val jdbc = script(onQuery = { throw failure })

        val error =
            assertFailsWith<QueryTimedOutException> {
                MssqlAdapter.executeQuery(
                    jdbc.dataSource(),
                    CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                    1_250,
                )
            }

        assertEquals(1_250, error.timeoutMs)
        assertSame(failure, error.cause)
        assertFalse(error.recyclePool)
        assertTrue("rollback" in jdbc.events)
        assertTrue("exec:SET LOCK_TIMEOUT -1" in jdbc.events)
    }

    @Test
    fun mssqlExecuteQueryMapsSqlAndSocketTimeoutsAndTimedOutMessagesAcrossCauseChain() {
        val timeoutThrowables =
            listOf(
                SQLTimeoutException("driver limit"),
                SQLException("wrapped", SocketTimeoutException("read failed")),
                SQLException("outer", SQLException("The operation Timed Out on server")),
            )

        for (failure in timeoutThrowables) {
            val jdbc = script(onQuery = { throw failure })
            val error =
                assertFailsWith<QueryTimedOutException> {
                    MssqlAdapter.executeQuery(
                        jdbc.dataSource(),
                        CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                        1_500,
                    )
                }
            assertEquals(1_500, error.timeoutMs)
            assertSame(failure, error.cause)
            val deadConnection =
                failure is SQLTimeoutException ||
                    generateSequence<Throwable>(failure) { it.cause }
                        .any { it is SocketTimeoutException }
            assertEquals(deadConnection, error.recyclePool)
        }
    }

    @Test
    fun mssqlExecuteQueryEvictsConnectionWhenLockTimeoutRestoreFails() {
        val jdbc =
            script(
                onQuery = { rows(listOf("id"), listOf(listOf(42L)), listOf("BIGINT")) },
                onExecute = { sql ->
                    if (sql == "SET LOCK_TIMEOUT -1") throw SQLException("cannot restore")
                    null
                },
            )
        val ds = jdbc.dataSource()

        val result =
            MssqlAdapter.executeQuery(
                ds,
                CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                2_500,
            )

        assertEquals(42L, assertIs<ResultCell.IntegerCell>(result.rows.single().single()).value)
        assertEquals(1, ds.evicted.size)
        assertTrue("exec:SET LOCK_TIMEOUT -1" in jdbc.events)
    }

    @Test
    fun mssqlExplainRestoresShowplanXmlAndRetriesTransientCleanupFailure() {
        var offAttempts = 0
        val jdbc =
            script(
                onExecute = { sql ->
                    when {
                        sql == "SET SHOWPLAN_XML OFF" -> {
                            offAttempts += 1
                            if (offAttempts == 1) throw SQLException("transient")
                            null
                        }
                        sql.contains("SELECT [id] FROM [dbo].[orders]") -> SAMPLE_SHOWPLAN_ROWS
                        else -> null
                    }
                },
            )
        val ds = jdbc.dataSource()

        val result =
            MssqlAdapter.explain(
                ds,
                CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                4_000,
            )

        val plan = assertIs<ExplainResult.Available>(result).plan
        assertEquals(PlanAccessMethod.TableScan, plan.relations.single().method)
        assertEquals(2, offAttempts)
        assertEquals(0, ds.evicted.size)
    }

    @Test
    fun mssqlExplainEvictsConnectionWhenShowplanOffFailsAfterRetries() {
        val jdbc =
            script(
                onExecute = { sql ->
                    when {
                        sql == "SET SHOWPLAN_XML OFF" -> throw SQLException("cannot restore")
                        sql.contains("SELECT [id] FROM [dbo].[orders]") -> SAMPLE_SHOWPLAN_ROWS
                        else -> null
                    }
                },
            )
        val ds = jdbc.dataSource()

        val result =
            MssqlAdapter.explain(
                ds,
                CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                4_000,
            )

        val unavailable = assertIs<ExplainResult.Unavailable>(result)
        assertEquals(PlanUnavailableReason.CleanupFailure, unavailable.reasonCode)
        assertEquals(1, ds.evicted.size)
        assertEquals(3, jdbc.events.count { it == "exec:SET SHOWPLAN_XML OFF" })
    }

    @Test
    fun mssqlExplainEvictsConnectionOnShowplanOffFailureEvenWhenPlanQueryTimesOut() {
        val jdbc =
            script(
                onExecute = { sql ->
                    when {
                        sql == "SET SHOWPLAN_XML OFF" -> throw SQLException("closed")
                        sql.contains("SELECT [id] FROM [dbo].[orders]") ->
                            throw SocketTimeoutException("Read timed out")
                        else -> null
                    }
                },
            )
        val ds = jdbc.dataSource()

        val error =
            assertFailsWith<QueryTimedOutException> {
                MssqlAdapter.explain(
                    ds,
                    CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                    2_000,
                )
            }

        assertEquals(2_000, error.timeoutMs)
        assertEquals(1, ds.evicted.size)
        assertEquals(3, jdbc.events.count { it == "exec:SET SHOWPLAN_XML OFF" })
    }

    @Test
    fun mssqlExplainEvictsConnectionWhenShowplanOnFailsAndOffCannotRestore() {
        val jdbc =
            script(
                onExecute = { sql ->
                    when (sql) {
                        "SET SHOWPLAN_XML ON" -> throw SQLException("cannot enable showplan")
                        "SET SHOWPLAN_XML OFF" -> throw SQLException("cannot restore")
                        else -> error("unexpected execute: $sql")
                    }
                },
            )
        val ds = jdbc.dataSource()

        val error =
            assertFailsWith<SQLException> {
                MssqlAdapter.explain(
                    ds,
                    CompiledQuery("SELECT [id] FROM [dbo].[orders]", emptyList()),
                    4_000,
                )
            }

        assertFalse(error is QueryTimedOutException)
        assertEquals("cannot enable showplan", error.message)
        assertEquals(1, ds.evicted.size)
        assertEquals(3, jdbc.events.count { it == "exec:SET SHOWPLAN_XML OFF" })
        assertTrue(jdbc.events.none { it.contains("SELECT [id] FROM [dbo].[orders]") })
    }
}

private const val SAMPLE_SHOWPLAN_XML =
    """<ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan"><BatchSequence><Batch><Statements><StmtSimple StatementType="SELECT" StatementSubTreeCost="1.5"><QueryPlan><RelOp PhysicalOp="Table Scan" LogicalOp="Table Scan" EstimateRows="10"><TableScan><Object Schema="[dbo]" Table="[orders]" Alias="[t0]"/></TableScan></RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>"""

private val SAMPLE_SHOWPLAN_ROWS =
    MssqlRows(listOf("Microsoft SQL Server 2005 XML Showplan"), listOf(listOf(SAMPLE_SHOWPLAN_XML)))

private class MssqlRows(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val typeNames: List<String> = emptyList(),
)

private fun rows(
    columns: List<String>,
    rows: List<List<Any?>>,
    typeNames: List<String> = emptyList(),
): MssqlRows = MssqlRows(columns, rows, typeNames)

private fun script(
    onQuery: (String) -> MssqlRows = { error("unexpected query: $it") },
    onExecute: (String) -> MssqlRows? = { null },
): ScriptedMssql = ScriptedMssql(onQuery, onExecute)

private class ScriptedMssql(
    private val onQuery: (String) -> MssqlRows,
    private val onExecute: (String) -> MssqlRows?,
) {
    val sql = mutableListOf<String>()
    val events = mutableListOf<String>()
    private var networkTimeout = 0
    private var closed = false

    fun dataSource(): RecordingHikariDataSource = RecordingHikariDataSource(connection())

    private fun connection(): Connection =
        proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "getNetworkTimeout" -> networkTimeout
                "setNetworkTimeout" -> {
                    networkTimeout = args!![1] as Int
                    events += "networkTimeout=$networkTimeout"
                    null
                }
                "isClosed" -> closed
                "setAutoCommit" -> record("autoCommit=${args!!.first()}")
                "setTransactionIsolation" -> record("isolation=${args!!.first()}")
                "createStatement" -> statement()
                "prepareStatement" -> prepared(args!!.first() as String)
                "commit" -> record("commit")
                "rollback" -> record("rollback")
                "close" -> {
                    closed = true
                    record("close")
                }
                "toString" -> "ScriptedMssqlConnection"
                else -> error("unexpected Connection.${method.name}")
            }
        }

    private fun statement(): Statement {
        var currentRows: MssqlRows? = null
        return proxy(Statement::class.java) { method, args ->
            when (method.name) {
                "execute" -> {
                    val command = args!!.first() as String
                    sql += command
                    events += "exec:$command"
                    currentRows = onExecute(command)
                    currentRows != null
                }
                "getResultSet" -> resultSet(currentRows!!)
                "getUpdateCount" -> -1
                "getMoreResults" -> {
                    currentRows = null
                    false
                }
                "close" -> null
                "toString" -> "ScriptedMssqlStatement"
                else -> error("unexpected Statement.${method.name}")
            }
        }
    }

    private fun prepared(rawSql: String): PreparedStatement =
        proxy(PreparedStatement::class.java) { method, _ ->
            when (method.name) {
                "setFetchSize" -> null
                "executeQuery" -> {
                    sql += rawSql
                    events += "query:$rawSql"
                    resultSet(onQuery(rawSql))
                }
                "close" -> null
                "toString" -> "ScriptedMssqlPreparedStatement"
                else -> error("unexpected PreparedStatement.${method.name}")
            }
        }

    private fun record(event: String): Any? {
        events += event
        return null
    }

    private fun resultSet(data: MssqlRows): ResultSet {
        var index = -1
        var sqlNull = false
        return proxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> {
                    index += 1
                    index < data.rows.size
                }
                "getMetaData" -> metaData(data)
                "getLong" -> {
                    val v = cell(data, index, args!!.first())
                    sqlNull = v == null
                    (v as? Number)?.toLong() ?: 0L
                }
                "getString" -> cell(data, index, args!!.first())?.toString()
                "wasNull" -> sqlNull
                "close" -> null
                "toString" -> "ScriptedMssqlResultSet"
                else -> error("unexpected ResultSet.${method.name}")
            }
        }
    }

    private fun metaData(data: MssqlRows): ResultSetMetaData =
        proxy(ResultSetMetaData::class.java) { method, args ->
            when (method.name) {
                "getColumnCount" -> data.columns.size
                "getColumnLabel" -> data.columns[(args!!.first() as Int) - 1]
                "getColumnTypeName" ->
                    data.typeNames.getOrElse((args!!.first() as Int) - 1) { "NVARCHAR" }
                "toString" -> "ScriptedMssqlMeta"
                else -> error("unexpected ResultSetMetaData.${method.name}")
            }
        }

    private fun cell(data: MssqlRows, rowIndex: Int, key: Any): Any? {
        val column =
            when (key) {
                is Int -> key - 1
                is String -> data.columns.indexOf(key)
                else -> error("unsupported key $key")
            }
        return data.rows[rowIndex][column]
    }

    private fun <T> proxy(
        type: Class<T>,
        handler: (java.lang.reflect.Method, Array<out Any>?) -> Any?,
    ): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                handler(method, args)
            },
        )
}

private class RecordingHikariDataSource(private val connection: Connection) : HikariDataSource() {
    val evicted = mutableListOf<Connection>()

    override fun getConnection(): Connection = connection

    override fun evictConnection(connection: Connection) {
        evicted += connection
    }
}
