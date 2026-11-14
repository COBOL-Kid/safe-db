package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.query.compileValidated
import com.safedb.query.validateQuery
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SqlRoundTripTest {
    private val schema = sqlTestSchema()

    private fun compile(sql: String, dialect: Dialect): String {
        val parsed = parseSqlToSpec(sql, dialect, schema, "public")
        val spec = assertIs<SqlParseResult.Success>(parsed, "parse failed: $parsed").spec
        val (validated, _) = validateQuery(spec, schema, emptyList(), dialect).unwrap()
        return compileValidated(validated, dialect).unwrap().sql
    }

    @Test
    fun omittedLimitCompilesWithoutRowLimitClause() {
        for (dialect in Dialect.entries) {
            val sql = compile("SELECT u.id FROM users u", dialect)
            assertFalse(sql.contains("LIMIT"), sql)
            assertFalse(sql.contains("TOP "), sql)
            assertFalse(sql.contains("FETCH FIRST"), sql)
        }
    }
}
