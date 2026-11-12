package com.safedb.query

import com.safedb.model.BindValue
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.MetadataCoverage
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.parseSqlToSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecToSqlTest {
    @Test
    fun rendersSimpleSelectWithQuotedIdentifiers() {
        val spec = simpleSpec()

        val sql = specToSql(spec, Dialect.Postgres).unwrap()

        assertEquals(
            "SELECT c.id, c.name\n" + "FROM public.customers AS c\n" + "LIMIT 25",
            sql,
        )
    }

    @Test
    fun rendersDialectSpecificLimitsAndAliasSyntax() {
        val spec = simpleSpec()

        val mssql = specToSql(spec, Dialect.Mssql).unwrap()
        assertTrue(mssql.startsWith("SELECT TOP 25 "))
        assertTrue(mssql.contains("public.customers AS c"))

        val oracle = specToSql(spec, Dialect.Oracle).unwrap()
        assertTrue(oracle.contains("\"public\".\"customers\" \"c\""))
        assertTrue(oracle.endsWith("FETCH FIRST 25 ROWS ONLY"))

        val db2 = specToSql(spec, Dialect.Db2).unwrap()
        assertTrue(db2.endsWith("FETCH FIRST 25 ROWS ONLY"))

        val mysql = specToSql(spec, Dialect.MySql).unwrap()
        assertTrue(mysql.contains("c.id"))
        assertTrue(mysql.endsWith("LIMIT 25"))
    }

    @Test
    fun rendersStarDistinctAndUncappedSelects() {
        val spec = simpleSpec().copy(columns = emptyList(), distinct = true, uncapped = true)

        val sql = specToSql(spec, Dialect.Postgres).unwrap()

        assertTrue(sql.startsWith("SELECT DISTINCT *"))
        assertTrue(!sql.contains("LIMIT"))
    }

    @Test
    fun rendersEveryFilterFormWithLiterals() {
        val filters =
            FilterGroup(
                id = "root",
                connector = GroupConnector.And,
                children =
                    listOf(
                        leaf("name", FilterOp.Eq, single(LiteralKind.Text, "O'Brien")),
                        leaf("age", FilterOp.Ne, single(LiteralKind.Int, "42")),
                        leaf("total", FilterOp.Gte, single(LiteralKind.Decimal, "12.50")),
                        leaf("active", FilterOp.Eq, single(LiteralKind.Bool, "true")),
                        leaf(
                            "created_on",
                            FilterOp.Between,
                            pair(LiteralKind.Date, "2024-01-01", "2024-12-31"),
                        ),
                        leaf(
                            "id",
                            FilterOp.In,
                            list(LiteralKind.Int, "1", "2", "3"),
                        ),
                        leaf(
                            "id",
                            FilterOp.NotIn,
                            list(LiteralKind.Int, "4", "5"),
                        ),
                        leaf("name", FilterOp.IsNull, null),
                        leaf("name", FilterOp.IsNotNull, null),
                        leaf("name", FilterOp.IsEmpty, null),
                        leaf("name", FilterOp.IsNotEmpty, null),
                        leaf("name", FilterOp.Like, single(LiteralKind.Text, "a%")),
                        leaf("name", FilterOp.NotLike, single(LiteralKind.Text, "b%")),
                        leaf("name", FilterOp.Contains, single(LiteralKind.Text, "mid")),
                        leaf("name", FilterOp.StartsWith, single(LiteralKind.Text, "start")),
                        leaf("name", FilterOp.EndsWith, single(LiteralKind.Text, "end")),
                        leaf("name", FilterOp.NotContains, single(LiteralKind.Text, "no")),
                    ),
            )
        val sql = specToSql(simpleSpec().copy(filters = filters), Dialect.Postgres).unwrap()

        assertTrue(sql.contains("c.name = 'O''Brien'"))
        assertTrue(sql.contains("c.age <> 42"))
        assertTrue(sql.contains("c.total >= 12.50"))
        assertTrue(sql.contains("c.active = TRUE"))
        assertTrue(sql.contains("c.created_on BETWEEN '2024-01-01' AND '2024-12-31'"))
        assertTrue(sql.contains("c.id IN (1, 2, 3)"))
        assertTrue(sql.contains("c.id NOT IN (4, 5)"))
        assertTrue(sql.contains("c.name IS NULL"))
        assertTrue(sql.contains("c.name IS NOT NULL"))
        assertTrue(sql.contains("c.name = ''"))
        assertTrue(sql.contains("c.name <> ''"))
        assertTrue(sql.contains("c.name LIKE 'a%'"))
        assertTrue(sql.contains("c.name NOT LIKE 'b%'"))
        assertTrue(sql.contains("c.name LIKE '%mid%'"))
        assertTrue(sql.contains("c.name LIKE 'start%'"))
        assertTrue(sql.contains("c.name LIKE '%end'"))
        assertTrue(sql.contains("c.name NOT LIKE '%no%'"))
    }

    @Test
    fun rendersContainsIgnoreCaseAsIlikeOnlyOnPostgres() {
        val filters =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf(
                            "name",
                            FilterOp.ContainsIgnoreCase,
                            single(LiteralKind.Text, "abc"),
                        )
                    ),
            )
        val spec = simpleSpec().copy(filters = filters)

        assertTrue(
            specToSql(spec, Dialect.Postgres).unwrap().contains("c.name ILIKE '%abc%' ESCAPE '!'")
        )
        assertTrue(
            specToSql(spec, Dialect.MySql).unwrap().contains("c.name LIKE '%abc%' ESCAPE '!'")
        )
    }

    @Test
    fun rendersEmptyInListsAsConstantPredicates() {
        val filters =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf("id", FilterOp.In, list(LiteralKind.Int)),
                        leaf("id", FilterOp.NotIn, list(LiteralKind.Int)),
                    ),
            )
        val sql = specToSql(simpleSpec().copy(filters = filters), Dialect.Postgres).unwrap()

        assertTrue(sql.contains("1=0"))
        assertTrue(sql.contains("1=1"))
        assertTrue(!sql.contains("IS NULL"))
        assertTrue(!sql.contains("IS NOT NULL"))
    }

    @Test
    fun incompleteFriendlyPatternAndBetweenReturnErr() {
        val containsMissing =
            FilterGroup(
                id = "root",
                children = listOf(leaf("name", FilterOp.Contains, null)),
            )
        val containsFailure =
            specToSql(simpleSpec().copy(filters = containsMissing), Dialect.Postgres)
        assertIs<Outcome.Err>(containsFailure)
        assertTrue(containsFailure.message.contains("single text value"))

        val betweenMissing =
            FilterGroup(
                id = "root",
                children = listOf(leaf("created_on", FilterOp.Between, null)),
            )
        val betweenFailure =
            specToSql(simpleSpec().copy(filters = betweenMissing), Dialect.Postgres)
        assertIs<Outcome.Err>(betweenFailure)
        assertTrue(betweenFailure.message.contains("is missing its value"))

        val betweenWrongShape =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf(
                            "created_on",
                            FilterOp.Between,
                            single(LiteralKind.Date, "2024-01-01"),
                        )
                    ),
            )
        val wrongShapeFailure =
            specToSql(simpleSpec().copy(filters = betweenWrongShape), Dialect.Postgres)
        assertIs<Outcome.Err>(wrongShapeFailure)
        assertTrue(wrongShapeFailure.message.contains("BETWEEN expects a pair"))

        val invalidInt =
            FilterGroup(
                id = "root",
                children = listOf(leaf("age", FilterOp.Eq, single(LiteralKind.Int, "nope"))),
            )
        val invalidIntFailure = specToSql(simpleSpec().copy(filters = invalidInt), Dialect.Postgres)
        assertIs<Outcome.Err>(invalidIntFailure)
        assertTrue(invalidIntFailure.message.contains("not a valid integer"))
    }

    @Test
    fun escapesLikeWildcardsInFriendlyPatterns() {
        val filters =
            FilterGroup(
                id = "root",
                children =
                    listOf(leaf("name", FilterOp.Contains, single(LiteralKind.Text, "50%_!"))),
            )
        val sql = specToSql(simpleSpec().copy(filters = filters), Dialect.Postgres).unwrap()

        assertTrue(sql.contains("c.name LIKE '%50!%!_!!%' ESCAPE '!'"))
    }

    @Test
    fun roundTripsContainsWithWildcardCharacters() {
        val filters =
            FilterGroup(
                id = "root",
                children =
                    listOf(leaf("name", FilterOp.Contains, single(LiteralKind.Text, "50%_!"))),
            )
        val spec = simpleSpec().copy(filters = filters)

        for (dialect in Dialect.entries) {
            val sql = specToSql(spec, dialect).unwrap()
            val parsed =
                assertIs<SqlParseResult.Success>(
                    parseSqlToSpec(sql, dialect, schema(), null),
                    "failed for $dialect: $sql",
                )
            val parsedLeaf = assertIs<FilterNode.Leaf>(parsed.spec.filters.children.single())
            // ESCAPE is consumed, and Contains is not reconstructed from LIKE.
            assertEquals(FilterOp.Like, parsedLeaf.spec.op)
            assertTrue(parsedLeaf.spec.likeEscape)
            assertEquals("%50!%!_!!%", (parsedLeaf.spec.value as FilterValue.Single).literal.text)
            assertEscapedLikeRoundTrip(parsed.spec, dialect, "LIKE")
        }
    }

    @Test
    fun roundTripsNotLikeAndIlikeEscapeClauses() {
        val notLikeFilters =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf(
                            "name",
                            FilterOp.NotLike,
                            single(LiteralKind.Text, "%50!%!_!!%"),
                            likeEscape = true,
                        )
                    ),
            )
        val notLikeSpec = simpleSpec().copy(filters = notLikeFilters)
        for (dialect in Dialect.entries) {
            val sql = specToSql(notLikeSpec, dialect).unwrap()
            assertTrue(sql.contains("NOT LIKE"), sql)
            assertTrue(sql.contains("ESCAPE '!'"), sql)
            val parsed =
                assertIs<SqlParseResult.Success>(
                    parseSqlToSpec(sql, dialect, schema(), null),
                    "failed for $dialect: $sql",
                )
            val parsedLeaf = assertIs<FilterNode.Leaf>(parsed.spec.filters.children.single())
            assertEquals(FilterOp.NotLike, parsedLeaf.spec.op)
            assertTrue(parsedLeaf.spec.likeEscape)
            assertEscapedLikeRoundTrip(parsed.spec, dialect, "NOT LIKE")
        }

        val ilikeFilters =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf(
                            "name",
                            FilterOp.Ilike,
                            single(LiteralKind.Text, "%50!%!_!!%"),
                            likeEscape = true,
                        )
                    ),
            )
        val ilikeSpec = simpleSpec().copy(filters = ilikeFilters)
        val ilikeSql = specToSql(ilikeSpec, Dialect.Postgres).unwrap()
        assertTrue(ilikeSql.contains("ILIKE"))
        assertTrue(ilikeSql.contains("ESCAPE '!'"))
        val parsedIlike =
            assertIs<SqlParseResult.Success>(
                parseSqlToSpec(ilikeSql, Dialect.Postgres, schema(), null)
            )
        val ilikeLeaf = assertIs<FilterNode.Leaf>(parsedIlike.spec.filters.children.single())
        assertEquals(FilterOp.Ilike, ilikeLeaf.spec.op)
        assertTrue(ilikeLeaf.spec.likeEscape)
        assertEscapedLikeRoundTrip(parsedIlike.spec, Dialect.Postgres, "ILIKE")
    }

    @Test
    fun rendersJoinsGroupsAndSorts() {
        val spec =
            QuerySpec(
                tables =
                    listOf(
                        TableRef("public", "customers", "c"),
                        TableRef("public", "orders", "o"),
                    ),
                columns = listOf(ColumnSel("c", "id"), ColumnSel("c", "name")),
                joins = listOf(JoinSpec("o", "customer_id", "c", "id")),
                filters = FilterGroup(id = "root"),
                limit = 10,
                groups = listOf(GroupSpec("c", "id"), GroupSpec("c", "name")),
                sorts =
                    listOf(
                        SortSpec("c", "name", SortDirection.Desc),
                        SortSpec("c", "id", SortDirection.Asc),
                    ),
            )

        val sql = specToSql(spec, Dialect.Postgres).unwrap()

        assertTrue(sql.contains("INNER JOIN public.orders AS o ON o.customer_id = c.id"))
        assertTrue(sql.contains("GROUP BY c.id, c.name"))
        assertTrue(sql.contains("ORDER BY c.name DESC, c.id ASC"))
    }

    @Test
    fun roundTripsThroughParseSqlToSpecAcrossDialects() {
        for (dialect in Dialect.entries) {
            val spec = richSpec()
            val sql = specToSql(spec, dialect).unwrap()
            val parsed = parseSqlToSpec(sql, dialect, schema(), defaultSchema = null)
            val result = assertIs<SqlParseResult.Success>(parsed, "failed for $dialect: $sql")
            assertSpecMatches(spec, result.spec, dialect)
        }
    }

    @Test
    fun roundTripsDistinctGroupsSortsAndJoins() {
        val spec =
            QuerySpec(
                tables =
                    listOf(
                        TableRef("public", "customers", "c"),
                        TableRef("public", "orders", "o"),
                    ),
                columns =
                    listOf(
                        ColumnSel("c", "id"),
                        ColumnSel("c", "name"),
                        ColumnSel("o", "total"),
                    ),
                joins = listOf(JoinSpec("o", "customer_id", "c", "id")),
                filters = FilterGroup(id = "root"),
                limit = 20,
                distinct = true,
                groups =
                    listOf(
                        GroupSpec("c", "id"),
                        GroupSpec("c", "name"),
                        GroupSpec("o", "total"),
                    ),
                sorts = listOf(SortSpec("c", "name", SortDirection.Desc)),
                schemaVersion = CURRENT_SCHEMA_VERSION,
            )

        val parsed =
            assertIs<SqlParseResult.Success>(
                parseSqlToSpec(
                    specToSql(spec, Dialect.Postgres).unwrap(),
                    Dialect.Postgres,
                    schema(),
                    null,
                )
            )

        assertEquals(spec.copy(filters = parsed.spec.filters), parsed.spec)
    }

    @Test
    fun roundTripsStarProjectionAndUncappedQueries() {
        val spec = simpleSpec().copy(columns = emptyList(), uncapped = true, limit = DEFAULT_LIMIT)

        val parsed =
            assertIs<SqlParseResult.Success>(
                parseSqlToSpec(
                    specToSql(spec, Dialect.Postgres).unwrap(),
                    Dialect.Postgres,
                    schema(),
                    null,
                )
            )

        assertEquals(
            setOf(
                ColumnSel("c", "id"),
                ColumnSel("c", "name"),
                ColumnSel("c", "age"),
                ColumnSel("c", "active"),
                ColumnSel("c", "created_on"),
            ),
            parsed.spec.columns.toSet(),
        )
        assertTrue(parsed.spec.uncapped)
        assertEquals(DEFAULT_LIMIT, parsed.spec.limit)
    }

    @Test
    fun roundTripsIlikeAndLikeFilters() {
        val filters =
            FilterGroup(
                id = "root",
                children =
                    listOf(
                        leaf("name", FilterOp.Like, single(LiteralKind.Text, "a%")),
                        leaf("name", FilterOp.Ilike, single(LiteralKind.Text, "b%")),
                    ),
            )
        val spec = simpleSpec().copy(filters = filters)

        val parsed =
            assertIs<SqlParseResult.Success>(
                parseSqlToSpec(
                    specToSql(spec, Dialect.Postgres).unwrap(),
                    Dialect.Postgres,
                    schema(),
                    null,
                )
            )

        assertEquals(
            listOf(FilterOp.Like, FilterOp.Ilike),
            leaves(parsed.spec.filters).map { it.op },
        )
    }

    private fun assertEscapedLikeRoundTrip(
        spec: QuerySpec,
        dialect: Dialect,
        operator: String,
    ) {
        val rendered = specToSql(spec, dialect).unwrap()
        assertTrue(rendered.contains("ESCAPE '!'"), rendered)
        assertTrue(rendered.contains(operator), rendered)

        val validated = validateQuery(spec, schema(), emptyList(), dialect).unwrap().first
        val compiled = compileValidated(validated, dialect).unwrap()
        assertTrue(compiled.sql.contains("ESCAPE '!'"), compiled.sql)
        assertTrue(compiled.sql.contains(operator), compiled.sql)
        assertEquals(listOf(BindValue.Text("%50!%!_!!%")), compiled.params)
    }

    private fun assertSpecMatches(original: QuerySpec, parsed: QuerySpec, dialect: Dialect) {
        assertEquals(original.tables, parsed.tables, "tables differed for $dialect")
        assertEquals(original.columns, parsed.columns, "columns differed for $dialect")
        assertEquals(original.joins, parsed.joins, "joins differed for $dialect")
        assertEquals(original.distinct, parsed.distinct, "distinct differed for $dialect")
        assertEquals(original.groups, parsed.groups, "groups differed for $dialect")
        assertEquals(original.sorts, parsed.sorts, "sorts differed for $dialect")
        assertEquals(original.limit, parsed.limit, "limit differed for $dialect")
        assertEquals(original.uncapped, parsed.uncapped, "uncapped differed for $dialect")
        assertFilterGroupMatches(original.filters, parsed.filters)
    }

    private fun assertFilterGroupMatches(original: FilterGroup, parsed: FilterGroup) {
        assertEquals(original.connector, parsed.connector)
        assertEquals(original.children.size, parsed.children.size)
        original.children.zip(parsed.children).forEach { (expected, actual) ->
            when (expected) {
                is FilterNode.Leaf -> {
                    val actualLeaf = assertIs<FilterNode.Leaf>(actual)
                    assertEquals(expected.spec.copy(id = ""), actualLeaf.spec.copy(id = ""))
                }
                is FilterNode.Group -> {
                    val actualGroup = assertIs<FilterNode.Group>(actual)
                    assertFilterGroupMatches(expected.group, actualGroup.group)
                }
            }
        }
    }

    private fun leaves(group: FilterGroup): List<FilterSpec> =
        group.children.flatMap { child ->
            when (child) {
                is FilterNode.Leaf -> listOf(child.spec)
                is FilterNode.Group -> leaves(child.group)
            }
        }

    private fun simpleSpec() =
        QuerySpec(
            tables = listOf(TableRef("public", "customers", "c")),
            columns = listOf(ColumnSel("c", "id"), ColumnSel("c", "name")),
            filters = FilterGroup(id = "root"),
            limit = 25,
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )

    private fun richSpec() =
        QuerySpec(
            tables =
                listOf(
                    TableRef("public", "customers", "c"),
                    TableRef("public", "orders", "o"),
                ),
            columns =
                listOf(
                    ColumnSel("c", "id"),
                    ColumnSel("c", "name"),
                    ColumnSel("o", "total"),
                ),
            joins = listOf(JoinSpec("o", "customer_id", "c", "id")),
            filters =
                FilterGroup(
                    id = "root",
                    connector = GroupConnector.And,
                    children =
                        listOf(
                            leaf("name", FilterOp.Eq, single(LiteralKind.Text, "Ada")),
                            FilterNode.Group(
                                FilterGroup(
                                    id = "nested",
                                    connector = GroupConnector.Or,
                                    children =
                                        listOf(
                                            leaf(
                                                "age",
                                                FilterOp.Gte,
                                                single(LiteralKind.Int, "18"),
                                            ),
                                            leaf(
                                                "active",
                                                FilterOp.Eq,
                                                single(LiteralKind.Bool, "false"),
                                            ),
                                        ),
                                )
                            ),
                            leaf(
                                "created_on",
                                FilterOp.Between,
                                pair(LiteralKind.Date, "2024-01-01", "2024-12-31"),
                            ),
                            leaf("id", FilterOp.In, list(LiteralKind.Int, "1", "2")),
                        ),
                ),
            limit = 50,
            sorts = listOf(SortSpec("c", "name", SortDirection.Asc)),
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )
}

private fun schema() =
    Schema(
        tables =
            listOf(
                TableInfo(
                    schema = "public",
                    name = "customers",
                    columns =
                        listOf(
                            column("id", "int"),
                            column("name", "varchar"),
                            column("age", "int"),
                            column("active", "boolean"),
                            column("created_on", "date"),
                        ),
                    indexes = emptyList(),
                    indexMetadata = MetadataCoverage.complete(),
                    foreignKeyMetadata = MetadataCoverage.complete(),
                ),
                TableInfo(
                    schema = "public",
                    name = "orders",
                    columns =
                        listOf(
                            column("id", "int"),
                            column("customer_id", "int"),
                            column("total", "decimal"),
                        ),
                    indexes = emptyList(),
                    indexMetadata = MetadataCoverage.complete(),
                    foreignKeyMetadata = MetadataCoverage.complete(),
                ),
            )
    )

private fun column(name: String, dataType: String) =
    ColumnInfo(
        name = name,
        dataType = dataType,
        nullable = true,
        category = classifyColumn(dataType),
    )

private fun leaf(
    column: String,
    op: FilterOp,
    value: FilterValue?,
    likeEscape: Boolean = false,
): FilterNode.Leaf =
    FilterNode.Leaf(
        FilterSpec(
            id = "$column-$op",
            tableAlias = "c",
            column = column,
            op = op,
            value = value,
            likeEscape = likeEscape,
        )
    )

private fun single(kind: LiteralKind, text: String): FilterValue =
    FilterValue.Single(FilterLiteral(kind, text))

private fun pair(kind: LiteralKind, first: String, second: String): FilterValue =
    FilterValue.Pair(FilterLiteral(kind, first), FilterLiteral(kind, second))

private fun list(kind: LiteralKind, vararg values: String): FilterValue =
    FilterValue.ListValue(values.map { FilterLiteral(kind, it) })
