package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.ResultCell
import com.safedb.model.TableSizeClass
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Db2AdapterJdbcTest {
    @Test
    fun db2TestReadsTheVersionProbe() {
        val jdbc = script(probe("DB2 v12.1.0"))

        assertEquals("DB2 v12.1.0", Db2Adapter.test(jdbc.dataSource()))
        assertEquals(listOf("SELECT 1 FROM SYSIBM.SYSDUMMY1"), jdbc.sql)
    }

    @Test
    fun db2IntrospectsTheLuwCatalog() {
        val schema = Db2Adapter.introspect(script(luwCatalog()).dataSource())

        val table = schema.tables.single()
        assertEquals("APP", table.schema)
        assertEquals("ORDERS", table.name)
        assertEquals(listOf("ID", "STATUS"), table.columns.map { it.name })
        assertEquals(false, table.columns.first().nullable)
        assertEquals(true, table.columns.last().nullable)
        val primary = table.indexes.single { it.name == "ORDERS_PK" }
        assertEquals(listOf("ID"), primary.columns)
        assertEquals(listOf("NOTES"), primary.includedColumns)
        assertEquals(true, primary.isPrimary)
        val foreignKey = table.foreignKeys.single()
        assertEquals("ORDERS_CUSTOMER_FK", foreignKey.name)
        assertEquals(listOf("CUSTOMER_ID"), foreignKey.columns)
        assertEquals("CUSTOMERS", foreignKey.referencedTable)
        assertEquals(TableSizeClass.Small, table.tableSize.sizeClass)
        assertEquals(EvidenceConfidence.Low, table.tableSize.confidence)
    }

    @Test
    fun db2IntrospectsTheZosCatalogWhenSyscatIsMissing() {
        val schema = Db2Adapter.introspect(script(zosCatalog()).dataSource())

        val table = schema.tables.single()
        assertEquals("APP", table.schema)
        assertEquals("ORDERS", table.name)
        assertEquals("ID", table.columns.single().name)
        assertEquals(listOf("ID"), table.indexes.single().columns)
        assertEquals(true, table.indexes.single().isUnique)
        assertEquals("LINES_ORDER_FK", table.foreignKeys.single().name)
        assertEquals(TableSizeClass.Small, table.tableSize.sizeClass)
    }

    @Test
    fun db2IntrospectRethrowsCatalogAuthorizationErrors() {
        val jdbc =
            script({ sql ->
                if (sql.compact().contains("FROM SYSCAT.TABLES FETCH FIRST")) denied() else null
            })

        val error = assertFailsWith<SQLException> { Db2Adapter.introspect(jdbc.dataSource()) }

        assertEquals("42501", error.sqlState)
    }

    @Test
    fun db2IntrospectKeepsTheSchemaWhenSizeStatisticsFail() {
        val jdbc =
            script(
                { sql ->
                    if (sql.compact().contains("CARD FROM SYSCAT.TABLES")) failure("stats")
                    else null
                },
                luwCatalog(),
            )

        val table = Db2Adapter.introspect(jdbc.dataSource()).tables.single()

        assertEquals("ORDERS", table.name)
        assertEquals(TableSizeClass.Unknown, table.tableSize.sizeClass)
    }

    @Test
    fun db2ExecuteQueryDecodesTheRowAndCommits() {
        val jdbc =
            script({ sql ->
                if (sql.compact().contains("FROM \"APP\".\"ORDERS\"")) {
                    query(
                        listOf("ID"),
                        listOf(listOf(7L)),
                        typeNames = listOf("INTEGER"),
                    )
                } else {
                    null
                }
            })

        val result =
            Db2Adapter.executeQuery(
                jdbc.dataSource(),
                CompiledQuery("SELECT \"ID\" FROM \"APP\".\"ORDERS\"", emptyList()),
                2_500,
            )

        assertEquals("ID", result.columns.single().name)
        assertEquals("INTEGER", result.columns.single().dataType)
        assertEquals(7L, assertIs<ResultCell.IntegerCell>(result.rows.single().single()).value)
        assertEquals(
            listOf(
                "autoCommit=false",
                "readOnly=true",
                "isolation=${Connection.TRANSACTION_READ_UNCOMMITTED}",
                "commit",
                "close",
            ),
            jdbc.events,
        )
        assertEquals(listOf(2), jdbc.timeouts)
    }

    @Test
    fun db2ExecuteQueryRollsBackAndRethrows() {
        val jdbc =
            script({ sql ->
                if (sql.compact().contains("FROM \"APP\".\"ORDERS\"")) failure("boom") else null
            })

        val error =
            assertFailsWith<SQLException> {
                Db2Adapter.executeQuery(
                    jdbc.dataSource(),
                    CompiledQuery("SELECT \"ID\" FROM \"APP\".\"ORDERS\"", emptyList()),
                    2_500,
                )
            }

        assertEquals("boom", error.message)
        assertEquals(
            listOf(
                "autoCommit=false",
                "readOnly=true",
                "isolation=${Connection.TRANSACTION_READ_UNCOMMITTED}",
                "rollback",
                "close",
            ),
            jdbc.events,
        )
    }

    @Test
    fun db2ExplainLuwReturnsTheNormalizedPlan() {
        val jdbc = script(luwExplain())

        val plan = assertIs<ExplainResult.Available>(explain(jdbc)).plan

        assertEquals("ORDERS", plan.relations.single().table)
        assertEquals("APP", plan.relations.single().schema)
        assertEquals(PlanAccessMethod.TableScan, plan.relations.single().method)
        assertEquals(10L, plan.relations.single().estimatedRows)
        assertEquals(40.0, plan.rawOptimizerCost)
        assertTrue(
            jdbc.sql.any {
                it.contains("DELETE FROM \"APP\".EXPLAIN_INSTANCE WHERE EXPLAIN_REQUESTER = ?")
            }
        )
    }

    @Test
    fun db2ExplainLuwReportsMissingExplainTables() {
        val result = explain(script(luwExplain(explainPlan = undefined())))

        assertUnavailable(result, PlanUnavailableReason.UnsupportedShape, "SYSINSTALLOBJECTS")
    }

    @Test
    fun db2ExplainLuwRethrowsUnexpectedExplainPlanErrors() {
        val error =
            assertFailsWith<SQLException> {
                explain(script(luwExplain(explainPlan = failure("nope"))))
            }

        assertEquals("nope", error.message)
    }

    @Test
    fun db2ExplainLuwReportsUnreadableExplainTablesWithoutDeleting() {
        val jdbc = script(luwExplain(sessionUser = failure("denied")))

        assertUnavailable(
            explain(jdbc),
            PlanUnavailableReason.ExecutionFailure,
            "could not be read",
        )
        assertTrue(jdbc.sql.none { it.startsWith("DELETE ") })
    }

    @Test
    fun db2ExplainLuwReportsAMissingExplainPrefix() {
        val jdbc =
            script(
                luwExplain(
                    authTables = undefined(),
                    systoolsTables = undefined(),
                )
            )

        assertUnavailable(
            explain(jdbc),
            PlanUnavailableReason.UnsupportedShape,
            "SYSINSTALLOBJECTS",
        )
        assertTrue(jdbc.sql.none { it.startsWith("DELETE ") })
        assertTrue(jdbc.sql.any { it.contains("FROM SYSTOOLS.EXPLAIN_INSTANCE") })
    }

    @Test
    fun db2ExplainLuwDiscardsThePlanWhenCleanupFails() {
        val jdbc = script(luwExplain(deletePlan = failure("cleanup")))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.CleanupFailure, "cleanup failed")
    }

    @Test
    fun db2ExplainLuwKeepsThePlanWhenCleanupObjectIsUndefined() {
        val jdbc = script(luwExplain(deletePlan = undefined()))

        val plan = assertIs<ExplainResult.Available>(explain(jdbc)).plan

        assertEquals("ORDERS", plan.relations.single().table)
    }

    @Test
    fun db2ExplainLuwReportsNoRowsWhenTheKeyIsMissing() {
        val jdbc = script(luwExplain(explainKey = query(KEY_COLUMNS, emptyList())))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "produced no rows")
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".EXPLAIN_INSTANCE WHERE (") })
    }

    @Test
    fun db2ExplainLuwDiscardsThePlanWhenTheKeyLookupFails() {
        val jdbc = script(luwExplain(explainKey = failure("key")))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.CleanupFailure, "cleanup failed")
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".EXPLAIN_INSTANCE WHERE (") })
    }

    @Test
    fun db2ExplainLuwReportsNoRowsWhenOperatorsAreEmpty() {
        val jdbc = script(luwExplain(operators = query(listOf("OPERATOR_ID"), emptyList())))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "produced no rows")
        assertTrue(jdbc.sql.none { it.contains("EXPLAIN_OBJECT") })
    }

    @Test
    fun db2ExplainLuwReportsAnUnparseablePlan() {
        val jdbc =
            script(
                luwExplain(
                    operators =
                        query(
                            listOf("OPERATOR_ID", "OPERATOR_TYPE", "TOTAL_COST"),
                            listOf(listOf(1, "RETURN", 12.5)),
                        )
                )
            )

        assertUnavailable(explain(jdbc), PlanUnavailableReason.ParseFailure, "Could not normalize")
    }

    @Test
    fun db2ExplainLuwUsesOperatorCostWhenStatementCostIsUnavailable() {
        val jdbc = script(luwExplain(statementCost = failure("no EXPLAIN_STATEMENT cost")))

        val plan = assertIs<ExplainResult.Available>(explain(jdbc)).plan

        assertEquals(12.5, plan.rawOptimizerCost)
    }

    @Test
    fun db2ExplainZosReturnsTheNormalizedPlan() {
        val jdbc = script(zosExplain())

        val plan = assertIs<ExplainResult.Available>(explain(jdbc)).plan

        assertEquals("ORDERS", plan.relations.single().table)
        assertEquals("APP", plan.relations.single().schema)
        assertEquals(PlanAccessMethod.BoundedRange, plan.relations.single().method)
        assertEquals(80.0, plan.rawOptimizerCost)
        assertEquals(
            setOf(PlanOperationKind.Distinct, PlanOperationKind.Sort),
            plan.blockingOperations.map { it.kind }.toSet(),
        )
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".PLAN_TABLE") })
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".DSN_STATEMNT_TABLE") })
    }

    @Test
    fun db2ExplainZosReportsAnUnreadableSqlId() {
        val jdbc = script(zosExplain(sqlId = query(listOf("SQLID"), listOf(listOf("  ")))))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.ExecutionFailure, "CURRENT SQLID")
    }

    @Test
    fun db2ExplainZosReportsSqlIdReadFailure() {
        val jdbc = script(zosExplain(sqlId = failure("sqlid")))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.ExecutionFailure, "CURRENT SQLID")
    }

    @Test
    fun db2ExplainZosRejectsAnUnsafePlanTableQualifier() {
        // trim() removes a leading or trailing control character, so the breakout has to sit inside
        // the id.
        val jdbc = script(zosExplain(sqlId = query(listOf("SQLID"), listOf(listOf("APP\nID")))))

        assertUnavailable(
            explain(jdbc),
            PlanUnavailableReason.UnsupportedShape,
            "not a usable identifier",
        )
    }

    @Test
    fun db2ExplainZosReportsAMissingPlanTable() {
        val jdbc = script(zosExplain(planTable = undefined()))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "DSNTIJXA")
        assertTrue(jdbc.sql.none { it.contains("EXPLAIN PLAN") })
    }

    @Test
    fun db2ExplainZosReportsAnUnreadablePlanTable() {
        val jdbc = script(zosExplain(planTable = failure("plan")))

        assertUnavailable(
            explain(jdbc),
            PlanUnavailableReason.ExecutionFailure,
            "could not be read",
        )
    }

    @Test
    fun db2ExplainZosReportsAMissingPlanTableWhenExplainIsUndefined() {
        val jdbc = script(zosExplain(explainPlan = undefined()))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "DSNTIJXA")
    }

    @Test
    fun db2ExplainZosRethrowsUnexpectedExplainPlanErrors() {
        val error =
            assertFailsWith<SQLException> {
                explain(script(zosExplain(explainPlan = failure("nope"))))
            }

        assertEquals("nope", error.message)
    }

    @Test
    fun db2ExplainZosReportsNoPlanRows() {
        val jdbc = script(zosExplain(planRows = query(PLAN_COLUMNS, emptyList())))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "no plan rows")
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".PLAN_TABLE") })
    }

    @Test
    fun db2ExplainZosDiscardsThePlanWhenCleanupFails() {
        val jdbc = script(zosExplain(deletePlan = failure("cleanup")))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.CleanupFailure, "cleanup failed")
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".DSN_STATEMNT_TABLE") })
    }

    @Test
    fun db2ExplainZosReportsAMissingPlanTableWhenThePlanReadIsUndefined() {
        val jdbc = script(zosExplain(planRows = undefined()))

        assertUnavailable(explain(jdbc), PlanUnavailableReason.UnsupportedShape, "DSNTIJXA")
        assertTrue(jdbc.sql.any { it.contains("DELETE FROM \"APP\".PLAN_TABLE") })
    }

    @Test
    fun db2ExplainZosKeepsThePlanWhenSortAndCostReadsFail() {
        val jdbc = script(zosExplain(sorts = failure("sort"), statementCost = failure("cost")))

        val plan = assertIs<ExplainResult.Available>(explain(jdbc)).plan

        assertEquals("ORDERS", plan.relations.single().table)
        assertEquals(emptyList(), plan.blockingOperations)
        assertEquals(null, plan.rawOptimizerCost)
    }

    private fun explain(jdbc: ScriptedDb2): ExplainResult =
        Db2Adapter.explain(
            jdbc.dataSource(),
            CompiledQuery("SELECT \"ID\" FROM \"APP\".\"ORDERS\"", emptyList()),
        )

    private fun assertUnavailable(
        result: ExplainResult,
        reason: PlanUnavailableReason,
        messagePart: String,
    ) {
        val unavailable = assertIs<ExplainResult.Unavailable>(result)
        assertEquals(reason, unavailable.reasonCode)
        assertTrue(messagePart in unavailable.message, unavailable.message)
    }
}

private class ScriptedDb2(private val handlers: List<(String) -> JdbcReply?>) {
    val sql = mutableListOf<String>()
    val events = mutableListOf<String>()
    val timeouts = mutableListOf<Int>()

    fun dataSource(): HikariDataSource = SingleConnectionDataSource(connection())

    private fun reply(rawSql: String): JdbcReply {
        val compact = rawSql.compact()
        sql += compact
        return handlers.firstNotNullOfOrNull { it(rawSql) } ?: error("unexpected SQL: $compact")
    }

    private fun connection(): Connection =
        proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "createStatement" -> statement()
                "prepareStatement" -> prepared(args!!.first() as String)
                "setAutoCommit" -> record("autoCommit=${args!!.first()}")
                "setReadOnly" -> record("readOnly=${args!!.first()}")
                "setTransactionIsolation" -> record("isolation=${args!!.first()}")
                "commit" -> record("commit")
                "rollback" -> record("rollback")
                "close" -> record("close")
                "toString" -> "ScriptedDb2"
                else -> unexpected(method)
            }
        }

    private fun record(event: String): Any? {
        events += event
        return null
    }

    private fun statement(): Statement =
        proxy(Statement::class.java) { method, args ->
            when (method.name) {
                "executeQuery" -> resultOf(reply(args!!.first() as String))
                "setQueryTimeout" -> timeout(args)
                "close" -> null
                "toString" -> "ScriptedStatement"
                else -> unexpected(method)
            }
        }

    private fun prepared(rawSql: String): PreparedStatement =
        proxy(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "executeQuery" -> resultOf(reply(rawSql))
                "execute" -> {
                    val reply = reply(rawSql)
                    if (reply is JdbcReply.Failure) throw reply.error
                    false
                }
                "setQueryTimeout" -> timeout(args)
                "setInt",
                "setLong",
                "setString",
                "setTimestamp",
                "setObject",
                "setNull",
                "setBigDecimal",
                "setDouble",
                "setBoolean" -> null
                "close" -> null
                "toString" -> "ScriptedPreparedStatement"
                else -> unexpected(method)
            }
        }

    private fun timeout(args: Array<out Any>?): Any? {
        timeouts += args!!.first() as Int
        return null
    }

    private fun resultOf(reply: JdbcReply): ResultSet =
        when (reply) {
            is JdbcReply.Failure -> throw reply.error
            is JdbcReply.Rows -> resultSet(reply)
            JdbcReply.Command -> error("executeQuery was used for a command")
        }
}

private class SingleConnectionDataSource(private val connection: Connection) : HikariDataSource() {
    override fun getConnection(): Connection = connection
}

private sealed interface JdbcReply {
    data class Rows(
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val typeNames: List<String>,
    ) : JdbcReply

    data object Command : JdbcReply

    data class Failure(val error: SQLException) : JdbcReply
}

private fun script(vararg handlers: (String) -> JdbcReply?) = ScriptedDb2(handlers.toList())

private fun query(
    columns: List<String>,
    rows: List<List<Any?>>,
    typeNames: List<String> = emptyList(),
): JdbcReply.Rows = JdbcReply.Rows(columns, rows, typeNames)

private fun one(vararg cells: Pair<String, Any?>): JdbcReply.Rows =
    query(cells.map { it.first }, listOf(cells.map { it.second }))

private fun failure(message: String): JdbcReply =
    JdbcReply.Failure(SQLException(message, "HY000", -1))

private fun undefined(): JdbcReply =
    JdbcReply.Failure(SQLException("undefined name", "42704", -204))

private fun denied(): JdbcReply = JdbcReply.Failure(SQLException("denied", "42501", -551))

private fun probe(version: String): (String) -> JdbcReply? = { sql ->
    if (sql.compact() == "SELECT 1 FROM SYSIBM.SYSDUMMY1") one("VERSION" to version) else null
}

private fun luwCatalog(): (String) -> JdbcReply? = { raw ->
    val sql = raw.compact()
    when {
        sql.contains("FROM SYSCAT.TABLES FETCH FIRST") -> one("TABNAME" to "ORDERS")
        sql.contains("FROM SYSCAT.TABLES WHERE TYPE") ->
            one("TABSCHEMA" to "APP", "TABNAME" to "ORDERS")
        sql.contains("FROM SYSCAT.COLUMNS") ->
            query(
                listOf("TABSCHEMA", "TABNAME", "COLNAME", "TYPENAME", "NULLS"),
                listOf(
                    listOf("APP", "ORDERS", "ID", "INTEGER", "N"),
                    listOf("APP", "ORDERS", "STATUS", "VARCHAR", "Y"),
                ),
            )
        sql.contains("FROM SYSCAT.INDEXES") ->
            query(
                listOf(
                    "TABSCHEMA",
                    "TABNAME",
                    "INDNAME",
                    "COLNAME",
                    "UNIQUERULE",
                    "COLORDER",
                    "INDEXTYPE",
                    "VIRTUAL",
                ),
                listOf(
                    listOf("APP", "ORDERS", "ORDERS_PK", "ID", "P", "A", "REG", "N"),
                    listOf("APP", "ORDERS", "ORDERS_PK", "NOTES", "P", "I", "REG", "N"),
                ),
            )
        sql.contains("FROM SYSCAT.REFERENCES") ->
            query(
                listOf(
                    "table_schema",
                    "table_name",
                    "constraint_name",
                    "column_name",
                    "referenced_schema",
                    "referenced_table",
                    "referenced_column",
                    "column_count",
                ),
                listOf(
                    listOf(
                        "APP",
                        "ORDERS",
                        "ORDERS_CUSTOMER_FK",
                        "CUSTOMER_ID",
                        "APP",
                        "CUSTOMERS",
                        "ID",
                        1,
                    ),
                    listOf(
                        "APP",
                        "ORDERS",
                        "ORDERS_PARTIAL_FK",
                        "ORDER_ID",
                        "APP",
                        "CUSTOMERS",
                        "ID",
                        2,
                    ),
                ),
            )
        sql.contains("CARD FROM SYSCAT.TABLES") ->
            one("TABSCHEMA" to "APP", "TABNAME" to "ORDERS", "CARD" to 100)
        else -> null
    }
}

private fun zosCatalog(): (String) -> JdbcReply? = { raw ->
    val sql = raw.compact()
    when {
        sql.contains("FROM SYSCAT.TABLES FETCH FIRST") -> undefined()
        sql.contains("FROM SYSIBM.SYSTABLES WHERE TYPE") ->
            one("CREATOR" to "APP", "NAME" to "ORDERS")
        sql.contains("FROM SYSIBM.SYSCOLUMNS") ->
            one(
                "TBCREATOR" to "APP",
                "TBNAME" to "ORDERS",
                "NAME" to "ID",
                "COLTYPE" to "INTEGER",
                "NULLS" to "N",
            )
        sql.contains("FROM SYSIBM.SYSINDEXES") ->
            one(
                "TBCREATOR" to "APP",
                "TBNAME" to "ORDERS",
                "NAME" to "ORDERS_PK",
                "COLNAME" to "ID",
                "UNIQUERULE" to "U",
                "ORDERING" to "A",
            )
        sql.contains("FROM SYSIBM.SYSRELS") ->
            one(
                "table_schema" to "APP",
                "table_name" to "ORDERS",
                "constraint_name" to "LINES_ORDER_FK",
                "column_name" to "ORDER_ID",
                "referenced_schema" to "APP",
                "referenced_table" to "LINES",
                "referenced_column" to "ID",
                "column_count" to 1,
            )
        sql.contains("CARDF FROM SYSIBM.SYSTABLES") ->
            one("CREATOR" to "APP", "NAME" to "ORDERS", "CARDF" to 50)
        else -> null
    }
}

private val KEY_COLUMNS =
    listOf(
        "EXPLAIN_REQUESTER",
        "EXPLAIN_TIME",
        "SOURCE_NAME",
        "SOURCE_SCHEMA",
        "SOURCE_VERSION",
        "EXPLAIN_LEVEL",
        "STMTNO",
        "SECTNO",
    )

private val PLAN_COLUMNS =
    listOf(
        "QBLOCKNO",
        "PLANNO",
        "MIXOPSEQ",
        "METHOD",
        "CREATOR",
        "TNAME",
        "ACCESSTYPE",
        "MATCHCOLS",
        "ACCESSCREATOR",
        "ACCESSNAME",
    )

private fun luwExplain(
    explainPlan: JdbcReply = JdbcReply.Command,
    sessionUser: JdbcReply = one("SESSION_USER" to "APP"),
    authTables: JdbcReply = query(listOf("OK"), emptyList()),
    systoolsTables: JdbcReply = query(listOf("OK"), emptyList()),
    explainKey: JdbcReply =
        query(
            KEY_COLUMNS,
            listOf(
                listOf("APP", Timestamp(1_700_000_000_000L), "SQLC2P31", "NULLID", "", "P", 1, 1)
            ),
        ),
    statementCost: JdbcReply = one("TOTAL_COST" to 40.0),
    operators: JdbcReply =
        query(
            listOf("OPERATOR_ID", "OPERATOR_TYPE", "TOTAL_COST"),
            listOf(listOf(1, "TBSCAN", 12.5)),
        ),
    deletePlan: JdbcReply = JdbcReply.Command,
): (String) -> JdbcReply? = { raw ->
    val sql = raw.compact()
    when {
        sql.contains("FROM SYSCAT.TABLES FETCH FIRST") -> one("TABNAME" to "ORDERS")
        sql.contains("EXPLAIN PLAN SET QUERYNO") -> explainPlan
        sql.contains("SELECT SESSION_USER") -> sessionUser
        sql.contains("DELETE FROM") && sql.contains("EXPLAIN_INSTANCE") -> deletePlan
        sql.contains("EXPLAIN_LEVEL = 'P'") -> explainKey
        sql.contains("MAX(TOTAL_COST)") && sql.contains("EXPLAIN_STATEMENT") -> statementCost
        sql.contains("EXPLAIN_OPERATOR") -> operators
        sql.contains("EXPLAIN_OBJECT") ->
            query(
                listOf("OPERATOR_ID", "OBJECT_SCHEMA", "OBJECT_NAME", "OBJECT_TYPE"),
                listOf(listOf(1, "APP", "ORDERS", "TA")),
            )
        sql.contains("EXPLAIN_STREAM") ->
            query(
                listOf("SOURCE_ID", "TARGET_ID", "STREAM_COUNT", "OBJECT_NAME"),
                listOf(listOf(1, 2, 10, null)),
            )
        sql.contains("EXPLAIN_PREDICATE") ->
            query(listOf("OPERATOR_ID", "PREDICATE_TEXT"), listOf(listOf(1, "STATUS = 'OPEN'")))
        sql.contains("FROM \"APP\".EXPLAIN_INSTANCE") -> authTables
        sql.contains("FROM SYSTOOLS.EXPLAIN_INSTANCE") -> systoolsTables
        else -> null
    }
}

private fun zosExplain(
    sqlId: JdbcReply = one("SQLID" to "APP"),
    planTable: JdbcReply = query(listOf("OK"), emptyList()),
    explainPlan: JdbcReply = JdbcReply.Command,
    statementCost: JdbcReply = one("TOTAL_COST" to 80.0),
    planRows: JdbcReply =
        query(
            PLAN_COLUMNS,
            listOf(listOf(1, 1, 0, 0, "APP", "ORDERS", "I", 1, "APP", "ORDERS_IX")),
        ),
    sorts: JdbcReply =
        query(
            listOf(
                "QBLOCKNO",
                "PLANNO",
                "MIXOPSEQ",
                "SORTC_UNIQ",
                "SORTC_JOIN",
                "SORTC_ORDERBY",
                "SORTC_GROUPBY",
                "SORTN_JOIN",
            ),
            listOf(listOf(1, 1, 0, "Y", "N", "Y", "N", "N")),
        ),
    deletePlan: JdbcReply = JdbcReply.Command,
    deleteStatement: JdbcReply = JdbcReply.Command,
): (String) -> JdbcReply? = { raw ->
    val sql = raw.compact()
    when {
        sql.contains("FROM SYSCAT.TABLES FETCH FIRST") -> undefined()
        sql.contains("SELECT CURRENT SQLID") -> sqlId
        sql.contains("EXPLAIN PLAN SET QUERYNO") -> explainPlan
        sql.contains("SORTC_UNIQ") -> sorts
        sql.contains("MAX(TOTAL_COST)") && sql.contains("DSN_STATEMNT_TABLE") -> statementCost
        sql.contains("DELETE FROM") && sql.contains("DSN_STATEMNT_TABLE") -> deleteStatement
        sql.contains("DELETE FROM") && sql.contains("PLAN_TABLE") -> deletePlan
        sql.contains("SELECT 1 FROM") && sql.contains("PLAN_TABLE") -> planTable
        sql.contains("QBLOCKNO") -> planRows
        else -> null
    }
}

private fun resultSet(query: JdbcReply.Rows): ResultSet {
    var index = -1
    return proxy(ResultSet::class.java) { method, args ->
        when (method.name) {
            "next" -> {
                index += 1
                index < query.rows.size
            }
            "getString" -> cell(query, index, args!!.first())?.toString()
            "getObject" -> cell(query, index, args!!.first())
            "getTimestamp" -> cell(query, index, args!!.first()) as Timestamp?
            "getLong" -> (cell(query, index, args!!.first()) as Number).toLong()
            "getMetaData" -> metaData(query)
            "close" -> null
            "toString" -> "ScriptedRows"
            else -> unexpected(method)
        }
    }
}

private fun cell(query: JdbcReply.Rows, rowIndex: Int, key: Any): Any? {
    val column =
        when (key) {
            is Int -> key - 1
            is String ->
                query.columns.indexOf(key).also { found ->
                    require(found >= 0) { "no column $key in ${query.columns}" }
                }
            else -> error("unsupported column key $key")
        }
    return query.rows[rowIndex][column]
}

private fun metaData(query: JdbcReply.Rows): ResultSetMetaData =
    proxy(ResultSetMetaData::class.java) { method, args ->
        when (method.name) {
            "getColumnCount" -> query.columns.size
            "getColumnLabel" -> query.columns[(args!!.first() as Int) - 1]
            "getColumnTypeName" ->
                query.typeNames.getOrElse((args!!.first() as Int) - 1) { "VARCHAR" }
            "toString" -> "ScriptedMeta"
            else -> unexpected(method)
        }
    }

private fun String.compact(): String = replace(Regex("\\s+"), " ").trim()

private fun unexpected(method: java.lang.reflect.Method): Nothing =
    error("unexpected JDBC call ${method.declaringClass.simpleName}.${method.name}")

private fun <T> proxy(
    type: Class<T>,
    handler: (java.lang.reflect.Method, Array<out Any>?) -> Any?,
): T =
    type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        }
    )
