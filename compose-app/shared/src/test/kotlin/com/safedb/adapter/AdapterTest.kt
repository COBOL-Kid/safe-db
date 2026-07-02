package com.safedb.adapter

import com.safedb.model.Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdapterTest {
    @Test
    fun parseShowplanCostExtractsSubtreeCost() {
        val xml =
            """<ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple StatementSubTreeCost="12.5"/></Statements></Batch></BatchSequence></ShowPlanXML>"""
        assertEquals(12.5, parseShowplanCost(xml))
    }

    @Test
    fun parseShowplanCostReturnsNoneWhenMissing() {
        assertNull(parseShowplanCost("<Plan />"))
    }

    @Test
    fun parseMysqlExplainCostHandlesLegacyQueryBlockShape() {
        val plan = """{"query_block":{"cost_info":{"query_cost":"12.50"}}}"""
        assertEquals(12.5, parseMysqlExplainCost(plan))
    }

    @Test
    fun parseMysqlExplainCostHandlesMysql9QueryPlanShape() {
        val plan = """{"query_plan":{"estimated_rows":15.0,"estimated_total_cost":1.75},"json_schema_version":"2.0"}"""
        assertEquals(1.75, parseMysqlExplainCost(plan))
    }

    @Test
    fun parseMysqlExplainCostReturnsNoneWhenMissing() {
        val plan = """{"query_plan":{"estimated_rows":15.0}}"""
        assertNull(parseMysqlExplainCost(plan))
    }

    @Test
    fun columnsFromCompiledSqlHandlesDialectQuotesAndMssqlTop() {
        val pg = """
            SELECT "t0"."id" AS "t0__id", "t0"."name" AS "t0__name"
            FROM "public"."users" AS "t0"
            LIMIT 101
        """.trimIndent()
        assertEquals(listOf("t0__id", "t0__name"), columnsFromCompiledSql(pg, Dialect.Postgres))

        val mysql = "SELECT `t0`.`id` AS `t0__id`\nFROM `app`.`users` AS `t0`\nLIMIT 101"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mysql, Dialect.MySql))

        val mssql = "SELECT TOP 101 [t0].[id] AS [t0__id]\nFROM [dbo].[users] AS [t0]"
        assertEquals(listOf("t0__id"), columnsFromCompiledSql(mssql, Dialect.Mssql))
    }
}
