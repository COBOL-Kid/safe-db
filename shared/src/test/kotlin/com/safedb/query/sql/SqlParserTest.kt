package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.FilterOp
import com.safedb.model.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlParserTest {
    private fun parse(sql: String, dialect: Dialect = Dialect.Postgres): SqlSelectAst {
        val result = parseSqlStatement(sql, dialect)
        assertIs<SqlStatementResult.Ok>(result, "expected success, got $result")
        return result.ast
    }

    private fun failParse(sql: String, dialect: Dialect = Dialect.Postgres): SqlIssue {
        val result = parseSqlStatement(sql, dialect)
        assertIs<SqlStatementResult.Fail>(result, "expected failure for: $sql")
        return result.issue
    }

    @Test
    fun parsesFullSelect() {
        val ast =
            parse(
                """
                SELECT DISTINCT u.id, u.name
                FROM users u
                INNER JOIN categories c ON u.category_id = c.id
                WHERE (u.active = true OR u.name LIKE 'a%') AND u.id > 10
                GROUP BY u.id, u.name
                ORDER BY u.name DESC, u.id
                LIMIT 50
                """
                    .trimIndent()
            )
        assertTrue(ast.distinct)
        assertEquals(2, ast.items!!.size)
        assertEquals("users", ast.from.name.name)
        assertEquals("u", ast.from.alias?.name)
        assertEquals(1, ast.joins.size)
        assertEquals(1, ast.joins[0].conditions.size)
        assertEquals(2, ast.groupBy.size)
        assertEquals(SortDirection.Desc, ast.orderBy[0].second)
        assertEquals(SortDirection.Asc, ast.orderBy[1].second)
        assertEquals(50, ast.limit)

        val where = assertIs<SqlConditionAst.And>(ast.where)
        val nested = assertIs<SqlConditionAst.Or>(where.children[0])
        assertEquals(2, nested.children.size)
    }

    @Test
    fun andBindsTighterThanOr() {
        val ast = parse("SELECT id FROM t WHERE a = 1 OR b = 2 AND c = 3")
        val where = assertIs<SqlConditionAst.Or>(ast.where)
        assertIs<SqlConditionAst.Predicate>(where.children[0])
        assertIs<SqlConditionAst.And>(where.children[1])
    }

    @Test
    fun parsesPredicateForms() {
        val ast =
            parse(
                "SELECT id FROM t WHERE a <> 1 AND b IS NOT NULL AND c NOT LIKE 'x' " +
                    "AND d NOT IN (1, 2) AND e BETWEEN 1 AND 5 AND f ILIKE 'y' AND g <= -2"
            )
        val ops =
            assertIs<SqlConditionAst.And>(ast.where).children.map {
                assertIs<SqlConditionAst.Predicate>(it).op
            }
        assertEquals(
            listOf(
                FilterOp.Ne,
                FilterOp.IsNotNull,
                FilterOp.NotLike,
                FilterOp.NotIn,
                FilterOp.Between,
                FilterOp.Ilike,
                FilterOp.Lte,
            ),
            ops,
        )
        val negative = assertIs<SqlConditionAst.And>(ast.where).children.last()
        assertEquals("-2", assertIs<SqlConditionAst.Predicate>(negative).values.single().raw)
    }

    @Test
    fun bareJoinIsInner() {
        val ast = parse("SELECT id FROM a JOIN b ON a.x = b.y AND a.z = b.w")
        assertEquals(2, ast.joins[0].conditions.size)
    }

    @Test
    fun bareStarAndNoLimit() {
        val ast = parse("SELECT * FROM users")
        assertNull(ast.items)
        assertNull(ast.limit)
    }

    @Test
    fun tableStarInSelectList() {
        val ast = parse("SELECT u.*, c.name FROM users u JOIN categories c ON u.category_id = c.id")
        val items = ast.items!!
        assertIs<SqlSelectItem.TableStar>(items[0])
        assertIs<SqlSelectItem.Column>(items[1])
    }

    @Test
    fun trailingSemicolonAllowed() {
        assertEquals(5, parse("SELECT id FROM t LIMIT 5;").limit)
    }

    @Test
    fun limitSyntaxAcceptanceMatrix() {
        assertEquals(7, parse("SELECT id FROM t LIMIT 7", Dialect.MySql).limit)
        assertEquals(7, parse("SELECT id FROM t FETCH FIRST 7 ROWS ONLY", Dialect.MySql).limit)
        assertEquals(7, parse("SELECT TOP 7 id FROM t", Dialect.Mssql).limit)
        assertEquals(7, parse("SELECT id FROM t LIMIT 7", Dialect.Mssql).limit)

        val issue = failParse("SELECT TOP 7 id FROM t", Dialect.Postgres)
        assertEquals(SqlIssueCode.Unsupported, issue.code)
        assertTrue(issue.message.contains("SQL Server"))
    }

    @Test
    fun limitMustBeWholeNumber() {
        assertEquals(SqlIssueCode.InvalidLimit, failParse("SELECT id FROM t LIMIT 2.5").code)
        assertEquals(SqlIssueCode.InvalidLimit, failParse("SELECT id FROM t LIMIT x").code)
    }

    @Test
    fun rejectsNonSelectStatements() {
        val update = failParse("UPDATE users SET name = 'x'")
        assertEquals(SqlIssueCode.NotSelect, update.code)
        assertTrue(update.message.contains("UPDATE"))
        assertEquals(SqlSpan(0, 6), update.span)

        assertEquals(SqlIssueCode.NotSelect, failParse("DROP TABLE users").code)
        assertEquals(SqlIssueCode.NotSelect, failParse("delete from users").code)
    }

    @Test
    fun rejectsMultipleStatements() {
        val issue = failParse("SELECT id FROM users; DROP TABLE users")
        assertEquals(SqlIssueCode.MultipleStatements, issue.code)
        assertEquals(22, issue.span?.start)
    }

    @Test
    fun commentsDoNotHideStatements() {
        assertIs<SqlStatementResult.Ok>(
            parseSqlStatement("SELECT id FROM users -- note", Dialect.Postgres)
        )
        val hidden = failParse("SELECT id FROM users /* x */ ; DELETE FROM users")
        assertEquals(SqlIssueCode.MultipleStatements, hidden.code)
    }

    @Test
    fun rejectsUnsupportedConstructs() {
        assertEquals(SqlMessages.CTE, failParse("WITH x AS (SELECT 1) SELECT * FROM x").message)
        assertEquals(
            SqlMessages.SET_OPERATION,
            failParse("SELECT id FROM a UNION SELECT id FROM b").message,
        )
        assertEquals(
            SqlMessages.OUTER_JOIN,
            failParse("SELECT id FROM a LEFT JOIN b ON a.x = b.y").message,
        )
        assertEquals(SqlMessages.FUNCTIONS, failParse("SELECT COUNT(id) FROM t").message)
        assertEquals(
            SqlMessages.FUNCTIONS,
            failParse("SELECT id FROM t WHERE LOWER(name) = 'x'").message,
        )
        assertEquals(
            SqlMessages.SUBQUERY,
            failParse("SELECT id FROM t WHERE id IN (SELECT id FROM u)").message,
        )
        assertEquals(SqlMessages.SUBQUERY, failParse("SELECT id FROM (SELECT id FROM t) x").message)
        assertEquals(SqlMessages.EXPRESSION, failParse("SELECT a + b FROM t").message)
        assertEquals(SqlMessages.COLUMN_ALIAS, failParse("SELECT id AS x FROM t").message)
        assertEquals(SqlMessages.COLUMN_ALIAS, failParse("SELECT id x FROM t").message)
        assertEquals(SqlMessages.OFFSET, failParse("SELECT id FROM t LIMIT 5 OFFSET 5").message)
        assertEquals(
            SqlMessages.HAVING,
            failParse("SELECT id FROM t GROUP BY id HAVING id > 1").message,
        )
        assertEquals(
            SqlMessages.NOT_CONDITION,
            failParse("SELECT id FROM t WHERE NOT (a = 1)").message,
        )
        assertEquals(SqlMessages.COMPARE_NULL, failParse("SELECT id FROM t WHERE a = NULL").message)
        assertEquals(SqlMessages.COLUMN_COMPARE, failParse("SELECT id FROM t WHERE a = b").message)
        assertEquals(
            SqlMessages.JOIN_CONDITION,
            failParse("SELECT id FROM a JOIN b ON a.x > b.y").message,
        )
        assertEquals(
            SqlMessages.JOIN_CONDITION,
            failParse("SELECT id FROM a JOIN b ON a.x = 1").message,
        )
        assertEquals(SqlMessages.STAR_MIX, failParse("SELECT *, id FROM t").message)
    }

    @Test
    fun subqueryDetectedInParenthesizedWhere() {
        assertEquals(
            SqlMessages.SUBQUERY,
            failParse("SELECT id FROM t WHERE (SELECT 1) = 1").message,
        )
    }

    @Test
    fun errorSpansPointAtTheProblem() {
        val sql = "SELECT id FROM t WHERE a = NULL"
        val issue = failParse(sql)
        val span = issue.span!!
        assertEquals("NULL", sql.substring(span.start, span.end))
    }

    @Test
    fun emptyInputFails() {
        assertEquals(SqlIssueCode.Syntax, failParse("").code)
        assertEquals(SqlIssueCode.Syntax, failParse("   -- just a comment").code)
    }

    @Test
    fun emptyInListRejected() {
        assertEquals(SqlIssueCode.Syntax, failParse("SELECT id FROM t WHERE a IN ()").code)
    }

    @Test
    fun quotedIdentifierIsNeverAKeyword() {
        val ast = parse("SELECT \"select\" FROM t")
        val item = assertIs<SqlSelectItem.Column>(ast.items!!.single())
        assertEquals("select", item.ref.name.name)
        assertTrue(item.ref.name.quoted)
    }

    @Test
    fun clauseWordsAreUsableAsUnquotedColumns() {
        // FIRST/ROWS/ONLY exist only inside FETCH FIRST n ROWS ONLY; reserving them made ordinary
        // Postgres and MySQL column names unusable.
        val ast = parse("SELECT first, rows, only FROM t")
        assertEquals(3, ast.items!!.size)
        assertEquals(3, parse("SELECT id FROM t FETCH FIRST 3 ROWS ONLY").limit)
    }

    @Test
    fun topIsOnlyAClauseOnMssql() {
        // TOP is a keyword only on MSSQL, so elsewhere `top` is just a column name.
        val ast = parse("SELECT top FROM t")
        assertEquals(1, ast.items!!.size)
        assertEquals(
            SqlIssueCode.Unsupported,
            failParse("SELECT TOP 7 id FROM t", Dialect.Postgres).code,
        )
        assertEquals(7, parse("SELECT TOP 7 id FROM t", Dialect.Mssql).limit)
        assertEquals(7, parse("SELECT TOP (7) id FROM t", Dialect.Mssql).limit)
    }

    @Test
    fun ilikeIsPostgresOnly() {
        val sql = "SELECT id FROM t WHERE col ILIKE 'x'"
        val pred = assertIs<SqlConditionAst.Predicate>(parse(sql, Dialect.Postgres).where)
        assertEquals(FilterOp.Ilike, pred.op)

        for (dialect in listOf(Dialect.MySql, Dialect.Mssql, Dialect.Oracle)) {
            val issue = failParse(sql, dialect)
            assertEquals(SqlIssueCode.Unsupported, issue.code)
            assertTrue(issue.message.contains("ILIKE is PostgreSQL syntax"))
        }
    }

    @Test
    fun explicitZeroLimitIsRejected() {
        // QuerySpec reserves 0 as the builder's "unset" sentinel, which validateQuery rewrites to
        // 500 — running 500 rows for a typed LIMIT 0 would be the opposite of what was asked.
        assertEquals(SqlIssueCode.InvalidLimit, failParse("SELECT id FROM t LIMIT 0").code)
        assertEquals(
            SqlIssueCode.InvalidLimit,
            failParse("SELECT TOP 0 id FROM t", Dialect.Mssql).code,
        )
        assertEquals(
            SqlIssueCode.InvalidLimit,
            failParse("SELECT id FROM t FETCH FIRST 0 ROWS ONLY").code,
        )
    }

    @Test
    fun negatedOperatorsWithoutSupportNameTheConstruct() {
        assertEquals(
            SqlIssueCode.Unsupported,
            failParse("SELECT id FROM t WHERE a NOT BETWEEN 1 AND 2").code,
        )
        assertEquals(
            SqlIssueCode.Unsupported,
            failParse("SELECT id FROM t WHERE a NOT ILIKE 'x'", Dialect.Postgres).code,
        )
    }
}
