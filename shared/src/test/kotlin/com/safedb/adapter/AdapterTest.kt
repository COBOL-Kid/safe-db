package com.safedb.adapter

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ResultCell
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun mysqlDisabledTransportAllowsPublicKeyRetrievalForLocalAuth() {
        val url = buildJdbcUrl(
            mysqlConnection(TransportSecurityMode.Disabled),
            password = "pw",
        )

        assertEquals(
            "jdbc:mysql://localhost:3306/safedb_test?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            url,
        )
    }

    @Test
    fun mysqlVerifiedTransportDoesNotAllowPublicKeyRetrieval() {
        val url = buildJdbcUrl(
            mysqlConnection(TransportSecurityMode.VerifyIdentity),
            password = "pw",
        )

        assertEquals("jdbc:mysql://localhost:3306/safedb_test?sslMode=VERIFY_IDENTITY", url)
    }

    @Test
    fun jdbcDecoderKeepsDecimalValuesAsText() {
        val cell = decodeJdbcValue(fakeResultSet(BigDecimal("1234567890.123456789")), 1, "DECIMAL")

        val text = assertIs<ResultCell.TextCell>(cell)
        assertEquals("1234567890.123456789", text.value.text)
    }

    @Test
    fun jdbcDecoderKeepsUnknownNumbersAsTextRatherThanLossyFloats() {
        val cell = decodeJdbcValue(fakeResultSet(BigDecimal("1.000000000000000001")), 1, "OTHER")

        val text = assertIs<ResultCell.TextCell>(cell)
        assertEquals("1.000000000000000001", text.value.text)
    }

    private fun mysqlConnection(mode: TransportSecurityMode) = ConnectionDef(
        id = "mysql-test",
        name = "MySQL test",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "testuser",
        transportSecurity = TransportSecurity(mode = mode),
    )
}

private fun fakeResultSet(value: Any?): ResultSet =
    Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getObject" -> value
            "getBoolean" -> value as Boolean
            "getLong" -> (value as Number).toLong()
            "getDouble" -> (value as Number).toDouble()
            "getBytes" -> value as ByteArray?
            "wasNull" -> value == null
            "toString" -> "FakeResultSet($value)"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet
