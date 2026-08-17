package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.Settings
import com.safedb.query.compileValidated
import com.safedb.query.evaluateQueryRisk
import com.safedb.query.validateQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlRoundTripTest {
    private val schema = sqlTestSchema()

    private fun compile(sql: String, dialect: Dialect): String {
        val parsed = parseSqlToSpec(sql, dialect, schema, "public")
        val spec = assertIs<SqlParseResult.Success>(parsed, "parse failed: $parsed").spec
        val (validated, _) = validateQuery(spec, schema, emptyList(), dialect).unwrap()
        return compileValidated(validated, dialect).unwrap().sql
    }

    @Test
    fun postgresRoundTripUsesDoubleQuotesAndLimit() {
        val sql = compile("SELECT u.id FROM users u WHERE u.name = 'x' LIMIT 5", Dialect.Postgres)
        assertTrue(sql.contains("\"public\".\"users\""), sql)
        assertTrue(sql.contains("LIMIT 6"), sql)
        assertTrue(sql.contains("$1"), sql)
    }

    @Test
    fun mysqlRoundTripUsesBackticks() {
        val sql = compile("SELECT u.id FROM users u LIMIT 5", Dialect.MySql)
        assertTrue(sql.contains("`public`.`users`"), sql)
        assertTrue(sql.contains("LIMIT 6"), sql)
    }

    @Test
    fun mssqlRoundTripUsesTopAndBrackets() {
        val sql = compile("SELECT TOP 5 u.id FROM users u", Dialect.Mssql)
        assertTrue(sql.contains("[public].[users]"), sql)
        assertTrue(sql.contains("TOP 6"), sql)
    }

    @Test
    fun oracleRoundTripUsesFetchFirst() {
        val sql = compile("SELECT u.id FROM users u FETCH FIRST 5 ROWS ONLY", Dialect.Oracle)
        assertTrue(sql.contains("\"public\".\"users\""), sql)
        assertTrue(sql.contains("FETCH FIRST 6 ROWS ONLY"), sql)
    }

    @Test
    fun parsedSpecFeedsRiskScoring() {
        val parsed =
            parseSqlToSpec(
                "SELECT u.id, u.name FROM users u WHERE u.id = 1",
                Dialect.Postgres,
                schema,
                "public",
            )
        val spec = assertIs<SqlParseResult.Success>(parsed).spec
        val evaluation = evaluateQueryRisk(spec, schema, Settings(), Dialect.Postgres).unwrap()
        val static = assertNotNull(evaluation.staticAssessment)
        assertEquals(static.severity, evaluation.finalAssessment?.severity)
    }
}
