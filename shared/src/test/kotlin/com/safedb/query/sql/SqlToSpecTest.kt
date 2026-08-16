package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.GroupConnector
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.validateQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlToSpecTest {
    private val schema = sqlTestSchema()

    private fun success(
        sql: String,
        defaultSchema: String? = "public",
        dialect: Dialect = Dialect.Postgres,
    ): SqlParseResult.Success {
        val result = parseSqlToSpec(sql, dialect, schema, defaultSchema)
        assertIs<SqlParseResult.Success>(result, "expected success, got $result")
        return result
    }

    private fun spec(
        sql: String,
        defaultSchema: String? = "public",
        dialect: Dialect = Dialect.Postgres,
    ): QuerySpec = success(sql, defaultSchema, dialect).spec

    private fun failure(
        sql: String,
        defaultSchema: String? = "public",
        dialect: Dialect = Dialect.Postgres,
    ): SqlIssue {
        val result = parseSqlToSpec(sql, dialect, schema, defaultSchema)
        assertIs<SqlParseResult.Failure>(result, "expected failure for: $sql")
        return result.issues.single()
    }

    @Test
    fun appliesDefaultSchemaToUnqualifiedTables() {
        val spec = spec("SELECT id FROM users")
        assertEquals("public", spec.tables.single().schema)
        assertEquals("users", spec.tables.single().name)
        assertEquals("users", spec.tables.single().alias)
    }

    @Test
    fun requiresSchemaWhenNoneSelected() {
        val issue = failure("SELECT id FROM users", defaultSchema = null)
        assertEquals(SqlIssueCode.SchemaRequired, issue.code)
    }

    @Test
    fun schemaQualifiedTableOverridesDefault() {
        val spec = spec("SELECT \"InvoiceId\" FROM \"Sales\".\"Invoices\"")
        assertEquals("Sales", spec.tables.single().schema)
        assertEquals("Invoices", spec.tables.single().name)
    }

    @Test
    fun caseInsensitiveResolutionEmitsMetadataCasing() {
        val spec = spec("SELECT invoiceid FROM sales.invoices", defaultSchema = null)
        assertEquals("Sales", spec.tables.single().schema)
        assertEquals("Invoices", spec.tables.single().name)
        assertEquals("Invoices", spec.tables.single().alias)
        assertEquals("InvoiceId", spec.columns.single().column)
    }

    @Test
    fun explicitAliasesApply() {
        val withAs = spec("SELECT u.id FROM users AS u")
        assertEquals("u", withAs.tables.single().alias)
        val bare = spec("SELECT u.id FROM users u")
        assertEquals("u", bare.tables.single().alias)

        val folded = spec("SELECT U.id FROM users AS U LIMIT 5")
        assertEquals("u", folded.tables.single().alias)
        assertEquals(
            "u",
            spec("SELECT \"u\".id FROM users AS U LIMIT 5").columns.single().tableAlias,
        )

        val quoted = spec("SELECT \"U\".id FROM users AS \"U\" LIMIT 5")
        assertEquals("U", quoted.tables.single().alias)
        val mixed =
            spec(
                "SELECT \"U\".id FROM users AS \"U\" JOIN categories u " +
                    "ON users.category_id = u.id LIMIT 5"
            )
        assertEquals(listOf("U", "u"), mixed.tables.map { it.alias })
    }

    @Test
    fun duplicateAliasRejected() {
        val issue = failure("SELECT id FROM users u JOIN categories u ON u.category_id = u.id")
        assertEquals(SqlIssueCode.DuplicateAlias, issue.code)

        val folded =
            failure(
                "SELECT U.id FROM users AS U JOIN categories u ON users.category_id = u.id LIMIT 5"
            )
        assertEquals(SqlIssueCode.DuplicateAlias, folded.code)

        val mysql =
            failure(
                "SELECT U.id FROM users AS U JOIN categories u ON users.category_id = u.id LIMIT 5",
                dialect = Dialect.MySql,
            )
        assertEquals(SqlIssueCode.DuplicateAlias, mysql.code)
        val mssql =
            failure(
                "SELECT U.id FROM users AS U JOIN categories u ON users.category_id = u.id LIMIT 5",
                dialect = Dialect.Mssql,
            )
        assertEquals(SqlIssueCode.DuplicateAlias, mssql.code)
    }

    @Test
    fun tableNameQualifierMapsToAlias() {
        val spec = spec("SELECT users.name FROM users u")
        assertEquals("u", spec.columns.single().tableAlias)
        assertEquals("name", spec.columns.single().column)
    }

    @Test
    fun bareColumnResolution() {
        val unique = spec("SELECT email FROM users u JOIN categories c ON u.category_id = c.id")
        assertEquals("u", unique.columns.single().tableAlias)

        val ambiguous =
            failure("SELECT name FROM users u JOIN categories c ON u.category_id = c.id")
        assertEquals(SqlIssueCode.AmbiguousColumn, ambiguous.code)

        val unknown = failure("SELECT missing FROM users")
        assertEquals(SqlIssueCode.UnknownColumn, unknown.code)
    }

    @Test
    fun unknownTableAndSchemaCodes() {
        assertEquals(SqlIssueCode.UnknownTable, failure("SELECT id FROM nope").code)
        assertEquals(SqlIssueCode.UnknownSchema, failure("SELECT id FROM nowhere.users").code)
    }

    @Test
    fun bareStarKeepsColumnsEmpty() {
        assertTrue(spec("SELECT * FROM users").columns.isEmpty())
    }

    @Test
    fun tableStarExpandsFromMetadata() {
        val spec = spec("SELECT c.* FROM users u JOIN categories c ON u.category_id = c.id")
        assertEquals(listOf("id", "name"), spec.columns.map { it.column })
        assertTrue(spec.columns.all { it.tableAlias == "c" })
    }

    @Test
    fun literalKindsComeFromColumnTypes() {
        val spec =
            spec(
                "SELECT id FROM users WHERE created_at > '2024-01-01' AND active = true " +
                    "AND id IN (1, 2) AND name LIKE 'a%'"
            )
        val leaves = spec.filters.children.map { (it as FilterNode.Leaf).spec }
        assertEquals(LiteralKind.DateTime, leaves[0].singleLiteral().kind)
        // Postgres and MySQL accept a bare date against a timestamp column, so it is widened to
        // midnight here rather than reaching validateQuery, which only accepts a time component.
        assertEquals("2024-01-01T00:00:00", leaves[0].singleLiteral().text)
        assertEquals(LiteralKind.Bool, leaves[1].singleLiteral().kind)
        assertEquals(LiteralKind.Int, leaves[2].listLiterals().first().kind)
        assertEquals(LiteralKind.Text, leaves[3].singleLiteral().kind)
    }

    @Test
    fun dateOnlyTimestampFilterSurvivesValidation() {
        val parsed = spec("SELECT id FROM users WHERE created_at > '2024-01-01' LIMIT 5")
        val outcome = validateQuery(parsed, schema, emptyList(), Dialect.Postgres)
        assertIs<Outcome.Ok<*>>(outcome, "expected the parsed spec to validate, got $outcome")
    }

    @Test
    fun timestampFilterWithUnparseableTextIsRejectedAtParse() {
        val issue = failure("SELECT id FROM users WHERE created_at > 'yesterday' LIMIT 5")
        assertEquals(SqlIssueCode.LiteralTypeMismatch, issue.code)
    }

    @Test
    fun literalFormMustMatchTheColumnType() {
        // `name = 123` used to bind "123" as text, silently running a different comparison than
        // the one written — MySQL would have compared numerically.
        assertEquals(
            SqlIssueCode.LiteralTypeMismatch,
            failure("SELECT id FROM users WHERE name = 123").code,
        )
        assertEquals(
            SqlIssueCode.LiteralTypeMismatch,
            failure("SELECT id FROM users WHERE name = true").code,
        )
        assertEquals(
            LiteralKind.Text,
            (spec("SELECT id FROM users WHERE name = '123'").filters.children.single()
                    as FilterNode.Leaf)
                .spec
                .singleLiteral()
                .kind,
        )
        assertEquals(
            LiteralKind.Int,
            (spec("SELECT id FROM users WHERE id = 123").filters.children.single()
                    as FilterNode.Leaf)
                .spec
                .singleLiteral()
                .kind,
        )
        // A quoted number against a numeric column keeps the comparison intact, so it is allowed.
        assertEquals(
            LiteralKind.Int,
            (spec("SELECT id FROM users WHERE id = '123'").filters.children.single()
                    as FilterNode.Leaf)
                .spec
                .singleLiteral()
                .kind,
        )
        assertEquals(
            SqlIssueCode.LiteralTypeMismatch,
            failure("SELECT id FROM users WHERE active = 1").code,
        )
        assertEquals(
            SqlIssueCode.LiteralTypeMismatch,
            failure("SELECT id FROM users WHERE active = 0").code,
        )
        assertEquals(
            SqlIssueCode.LiteralTypeMismatch,
            failure("SELECT id FROM users WHERE active = ''").code,
        )
        assertEquals(
            LiteralKind.Bool,
            (spec("SELECT id FROM users WHERE active = true").filters.children.single()
                    as FilterNode.Leaf)
                .spec
                .singleLiteral()
                .kind,
        )
        assertEquals(
            LiteralKind.Bool,
            (spec("SELECT id FROM users WHERE active = 'false'").filters.children.single()
                    as FilterNode.Leaf)
                .spec
                .singleLiteral()
                .kind,
        )
        val mysqlLike =
            (success(
                    "SELECT id FROM users WHERE name LIKE '100\\%' OR name = '100\\_'",
                    dialect = Dialect.MySql,
                )
                .spec
                .filters
                .children)
        assertEquals("100\\%", (mysqlLike[0] as FilterNode.Leaf).spec.singleLiteral().text)
        assertEquals("100\\_", (mysqlLike[1] as FilterNode.Leaf).spec.singleLiteral().text)
    }

    @Test
    fun quotedIdentifiersMustMatchMetadataExactly() {
        // "invoiceid" spells a column that does not exist; falling back case-insensitively would
        // silently retarget InvoiceId.
        val issue =
            failure("SELECT \"invoiceid\" FROM \"Sales\".\"Invoices\"", defaultSchema = null)
        assertEquals(SqlIssueCode.UnknownColumn, issue.code)
    }

    @Test
    fun onConditionMustLinkTheJoinedTable() {
        // The second conjunct is not a join-graph edge, so buildJoinClause would have dropped it
        // and
        // executed a broader query than written.
        val issue =
            failure(
                "SELECT u.id FROM users u JOIN categories c " +
                    "ON u.category_id = c.id AND u.id = u.category_id LIMIT 5"
            )
        assertEquals(SqlIssueCode.Unsupported, issue.code)
    }

    @Test
    fun tableNameQualifierRejectsAmbiguity() {
        val issue =
            failure("SELECT users.id FROM users u JOIN users u2 ON u.category_id = u2.id LIMIT 5")
        assertEquals(SqlIssueCode.UnknownTable, issue.code)
    }

    @Test
    fun filterTreeShapeAndConnectors() {
        val spec = spec("SELECT id FROM users WHERE (name = 'a' OR name = 'b') AND active = true")
        assertEquals(GroupConnector.And, spec.filters.connector)
        assertEquals(2, spec.filters.children.size)
        val nested = assertIs<FilterNode.Group>(spec.filters.children[0]).group
        assertEquals(GroupConnector.Or, nested.connector)
        assertEquals(2, nested.children.size)
        assertTrue(spec.connectorOverrides.isEmpty())

        val single = spec("SELECT id FROM users WHERE id = 1")
        assertEquals(GroupConnector.And, single.filters.connector)
        val leaf = assertIs<FilterNode.Leaf>(single.filters.children.single()).spec
        assertEquals(FilterOp.Eq, leaf.op)
    }

    @Test
    fun reparsingIdenticalTextYieldsEqualSpecs() {
        val sql = "SELECT id FROM users u WHERE (name = 'a' OR email = 'b') AND id > 1 ORDER BY id"
        assertEquals(spec(sql), spec(sql))
    }

    @Test
    fun missingLimitDefaultsWithNote() {
        val result = success("SELECT id FROM users")
        assertEquals(DEFAULT_LIMIT, result.spec.limit)
        assertTrue(result.notes.single().contains("defaulting to $DEFAULT_LIMIT"))

        val explicit = success("SELECT id FROM users LIMIT 42")
        assertEquals(42, explicit.spec.limit)
        assertTrue(explicit.notes.isEmpty())
    }

    @Test
    fun joinsBecomeJoinSpecs() {
        val spec = spec("SELECT u.id FROM users u JOIN categories c ON u.category_id = c.id")
        val join = spec.joins.single()
        assertEquals("u", join.leftAlias)
        assertEquals("category_id", join.leftColumn)
        assertEquals("c", join.rightAlias)
        assertEquals("id", join.rightColumn)
    }

    @Test
    fun groupsAndSortsResolve() {
        val spec = spec("SELECT name FROM users GROUP BY name ORDER BY name DESC")
        assertEquals("name", spec.groups.single().column)
        assertEquals("users", spec.groups.single().tableAlias)
        assertEquals("name", spec.sorts.single().column)
    }
}

private fun com.safedb.model.FilterSpec.singleLiteral() =
    (value as com.safedb.model.FilterValue.Single).literal

private fun com.safedb.model.FilterSpec.listLiterals() =
    (value as com.safedb.model.FilterValue.ListValue).literals
