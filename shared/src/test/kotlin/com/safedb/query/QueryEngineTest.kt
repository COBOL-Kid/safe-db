package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.BindValue
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.IndexInfo
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.model.parseDateTimeLiteral
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class QueryEngineTest {
    @Test
    fun validateRejectsEmptyTables() {
        val spec = sampleSpec().copy(tables = emptyList())
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("At least one table"))
    }

    @Test
    fun validateRejectsBlockedSystemSchema() {
        val spec = sampleSpec().copy(
            tables = listOf(TableRef(schema = "pg_catalog", name = "users", alias = "t0")),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("blocked"))
    }

    @Test
    fun validateRejectsCustomBlockedSchemaCaseInsensitive() {
        val spec = sampleSpec().copy(
            tables = listOf(TableRef(schema = "Audit", name = "users", alias = "t0")),
        )
        val err = validate(spec, sampleSchema(), listOf("audit")).unwrapErr()
        assertTrue(err.contains("blocked"))
    }

    @Test
    fun validateRejectsJoinOnNonIndexedColumn() {
        val spec = sampleSpec().copy(
            tables = sampleSpec().tables + TableRef(schema = "public", name = "categories", alias = "t1"),
            joins = listOf(
                JoinSpec(leftAlias = "t0", leftColumn = "name", rightAlias = "t1", rightColumn = "id"),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("leading key"))
    }

    @Test
    fun validateAcceptsJoinOnIndexedColumns() {
        val spec = sampleSpec().copy(
            tables = sampleSpec().tables + TableRef(schema = "public", name = "categories", alias = "t1"),
            joins = listOf(
                JoinSpec(leftAlias = "t0", leftColumn = "id", rightAlias = "t1", rightColumn = "id"),
            ),
        )
        val (_, outcome) = validate(spec, sampleSchema(), emptyList()).unwrap()
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun validateAndCompileAcceptCompleteCompositeForeignKeyJoin() {
        val child = TableInfo(
            schema = "public",
            name = "line_items",
            columns = listOf(
                ColumnInfo("order_id", "int", false, true, true, ColumnCategory.Integer),
                ColumnInfo("store_id", "int", false, true, false, ColumnCategory.Integer),
            ),
            indexes = listOf(IndexInfo("line_items_order_idx", listOf("order_id", "store_id"))),
            foreignKeys = listOf(
                ForeignKeyInfo(
                    name = "line_items_order_fkey",
                    columns = listOf("order_id", "store_id"),
                    referencedSchema = "public",
                    referencedTable = "orders",
                    referencedColumns = listOf("id", "store_id"),
                ),
            ),
        )
        val parent = TableInfo(
            schema = "public",
            name = "orders",
            columns = listOf(
                ColumnInfo("id", "int", false, true, true, ColumnCategory.Integer),
                ColumnInfo("store_id", "int", false, true, false, ColumnCategory.Integer),
            ),
            indexes = listOf(IndexInfo("orders_pkey", listOf("id", "store_id"), isPrimary = true, isUnique = true)),
        )
        val spec = QuerySpec(
            tables = listOf(TableRef("public", "line_items", "t0"), TableRef("public", "orders", "t1")),
            columns = listOf(ColumnSel("t0", "order_id")),
            joins = listOf(
                JoinSpec("t0", "order_id", "t1", "id"),
                JoinSpec("t0", "store_id", "t1", "store_id"),
            ),
            filters = FilterGroup.empty(),
            limit = 100,
        )

        val normalized = validate(spec, Schema(listOf(child, parent)), emptyList()).unwrap().first
        val sql = compile(normalized, Dialect.Postgres).unwrap().sql

        assertTrue(sql.contains("\"t0\".\"order_id\" = \"t1\".\"id\""))
        assertTrue(sql.contains("\"t0\".\"store_id\" = \"t1\".\"store_id\""))
    }

    @Test
    fun validateErrorsOnDisconnectedTables() {
        val spec = sampleSpec().copy(
            tables = sampleSpec().tables + TableRef(schema = "public", name = "categories", alias = "t1"),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("connected by joins"))
    }

    @Test
    fun validateDefaultsZeroLimitAndCapsExcess() {
        var spec = sampleSpec().copy(limit = 0)
        var (normalized, outcome) = validate(spec, sampleSchema(), emptyList()).unwrap()
        assertEquals(DEFAULT_LIMIT, normalized.limit)
        assertTrue(outcome.warnings.any { it.contains("defaulted") })

        spec = sampleSpec().copy(limit = 5000)
        outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertEquals(5000, outcome.limit)
        assertTrue(outcome.warnings.any { it.contains("useful for reporting") })

        spec = sampleSpec().copy(limit = MAX_LIMIT)
        outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertEquals(MAX_LIMIT, outcome.limit)
        assertTrue(outcome.warnings.any { it.contains("useful for reporting") })

        spec = sampleSpec().copy(limit = MAX_LIMIT + 1)
        val capped = validate(spec, sampleSchema(), emptyList()).unwrap()
        assertEquals(MAX_LIMIT, capped.first.limit)
        assertEquals(MAX_LIMIT, capped.second.limit)
        assertTrue(capped.second.warnings.any { it.contains("capped") })
    }

    @Test
    fun validateWarnsWhenNoColumnsSelected() {
        val spec = sampleSpec().copy(columns = emptyList())
        val outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertTrue(outcome.warnings.any { it.contains("No columns selected") })
    }

    @Test
    fun validateRejectsFilterMissingValue() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(leaf(FilterOp.Eq, null)),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("requires a value"))
    }

    @Test
    fun validateRejectsIlikeOnNumericColumn() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.Ilike, FilterValue.Single(lit(LiteralKind.Text, "foo"))),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("not applicable"))
    }

    @Test
    fun validateAcceptsIsEmptyOnTextColumn() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("name", FilterOp.IsEmpty, null),
                ),
            ),
        )
        val outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertTrue(outcome.warnings.none { it.contains("not applicable") })
    }

    @Test
    fun validateWarnsWhenFilteringNonIndexedColumn() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + listOf(
                    FilterNode.Leaf(
                        leafOn("name", FilterOp.Eq, FilterValue.Single(lit(LiteralKind.Text, "Alice"))),
                    ),
                    FilterNode.Leaf(
                        leafOn("name", FilterOp.Like, FilterValue.Single(lit(LiteralKind.Text, "A%"))),
                    ),
                ),
            ),
        )
        val outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        val warnings = outcome.warnings.filter { it.contains("non-indexed field 'users.name'") }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("row limit and timeout"))
    }

    @Test
    fun textFilterChoicesOfferFriendlyPatternsInsteadOfRawLikeOperators() {
        val offered = opsForColumn("varchar")

        assertTrue(FilterOp.Contains in offered)
        assertTrue(FilterOp.ContainsIgnoreCase in offered)
        assertTrue(FilterOp.NotContains in offered)
        assertTrue(FilterOp.StartsWith in offered)
        assertTrue(FilterOp.EndsWith in offered)
        assertFalse(FilterOp.Like in offered)
        assertFalse(FilterOp.NotLike in offered)
        assertFalse(FilterOp.Ilike in offered)
        assertEquals("contains", opLabel(FilterOp.Contains))
        assertEquals("contains (case-insensitive)", opLabel(FilterOp.ContainsIgnoreCase))
        assertEquals("does not contain", opLabel(FilterOp.NotContains))
        assertEquals("starts with", opLabel(FilterOp.StartsWith))
        assertEquals("ends with", opLabel(FilterOp.EndsWith))
    }

    @Test
    fun friendlyTextPatternsCompileWithExpectedOperatorsAndBoundValues() {
        val cases = listOf(
            Triple(FilterOp.Contains, "LIKE", "%chair%"),
            Triple(FilterOp.NotContains, "NOT LIKE", "%chair%"),
            Triple(FilterOp.StartsWith, "LIKE", "chair%"),
            Triple(FilterOp.EndsWith, "LIKE", "%chair"),
        )

        for ((op, sqlOperator, expectedValue) in cases) {
            val spec = textFilterSpec(op, "chair")
            val compiled = compile(spec, Dialect.Postgres).unwrap()

            assertTrue(compiled.sql.contains("\"t0\".\"name\" $sqlOperator \$1 ESCAPE '!'"))
            assertEquals(listOf(BindValue.Text(expectedValue)), compiled.params)
        }
    }

    @Test
    fun friendlyTextPatternsEscapeWildcardsAcrossDialects() {
        val spec = textFilterSpec(FilterOp.Contains, "50%_!")
        val expectedSql = mapOf(
            Dialect.Postgres to "\"t0\".\"name\" LIKE \$1 ESCAPE '!'",
            Dialect.MySql to "`t0`.`name` LIKE ? ESCAPE '!'",
            Dialect.Mssql to "[t0].[name] LIKE @P1 ESCAPE '!'",
            Dialect.Oracle to "\"t0\".\"name\" LIKE :1 ESCAPE '!'",
        )

        for ((dialect, fragment) in expectedSql) {
            val compiled = compile(spec, dialect).unwrap()

            assertTrue(compiled.sql.contains(fragment))
            assertEquals(listOf(BindValue.Text("%50!%!_!!%")), compiled.params)
        }
    }

    @Test
    fun caseInsensitiveContainsEscapesWildcardsAcrossDialects() {
        val spec = textFilterSpec(FilterOp.ContainsIgnoreCase, "50%_!")
        val expectedSql = mapOf(
            Dialect.Postgres to "\"t0\".\"name\" ILIKE \$1 ESCAPE '!'",
            Dialect.MySql to "LOWER(`t0`.`name`) LIKE LOWER(?) ESCAPE '!'",
            Dialect.Mssql to "LOWER([t0].[name]) LIKE LOWER(@P1) ESCAPE '!'",
            Dialect.Oracle to "UPPER(\"t0\".\"name\") LIKE UPPER(:1) ESCAPE '!'",
        )

        for ((dialect, fragment) in expectedSql) {
            val compiled = compile(spec, dialect).unwrap()

            assertTrue(compiled.sql.contains(fragment))
            assertEquals(listOf(BindValue.Text("%50!%!_!!%")), compiled.params)
        }
    }

    @Test
    fun friendlyTextPatternsRejectMissingOrNonTextValuesDuringCompilation() {
        val missingValue = textFilterSpec(FilterOp.Contains, "chair").copy(
            filters = FilterGroup(
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            tableAlias = "t0",
                            column = "name",
                            op = FilterOp.Contains,
                            value = null,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(compile(missingValue, Dialect.Postgres).unwrapErr().contains("single text value"))

        val nonTextValue = textFilterSpec(FilterOp.Contains, "chair").copy(
            filters = FilterGroup(
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            tableAlias = "t0",
                            column = "name",
                            op = FilterOp.Contains,
                            value = FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(compile(nonTextValue, Dialect.Postgres).unwrapErr().contains("text value"))
    }

    @Test
    fun legacyRawLikeOperatorsStillValidateAndCompileUnchanged() {
        val cases = listOf(
            Triple(FilterOp.Like, "A%", "LIKE \$1"),
            Triple(FilterOp.NotLike, "B_", "NOT LIKE \$1"),
            Triple(FilterOp.Ilike, "C%", "ILIKE \$1"),
        )

        for ((op, value, fragment) in cases) {
            val spec = textFilterSpec(op, value)
            validate(spec, sampleSchema(), emptyList()).unwrap()
            val compiled = compile(spec, Dialect.Postgres).unwrap()

            assertTrue(compiled.sql.contains(fragment))
            assertFalse(compiled.sql.contains("ESCAPE '!'"))
            assertEquals(listOf(BindValue.Text(value)), compiled.params)
        }
    }

    @Test
    fun validateRejectsInWithEmptyList() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.In, FilterValue.ListValue(emptyList())),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("empty value list"))
    }

    @Test
    fun validateRejectsBetweenMissingPair() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.Between, FilterValue.Single(lit(LiteralKind.Int, "5"))),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("range"))
    }

    @Test
    fun validateRejectsNonIntegerValueForIntColumn() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.Eq, FilterValue.Single(lit(LiteralKind.Int, "abc"))),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("not a valid integer"))
    }

    @Test
    fun validateRejectsTextLiteralKindForIntColumn() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.Eq, FilterValue.Single(lit(LiteralKind.Text, "123"))),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("expected Int"), "got: $err")
    }

    @Test
    fun validateAcceptsNestedGroups() {
        val spec = sampleSpec().copy(
            filters = FilterGroup(
                id = "g0",
                connector = GroupConnector.And,
                children = listOf(
                    FilterNode.Group(
                        FilterGroup(
                            id = "g1",
                            connector = GroupConnector.Or,
                            children = listOf(
                                FilterNode.Leaf(
                                    leafOn("id", FilterOp.Eq, FilterValue.Single(lit(LiteralKind.Int, "1"))),
                                ),
                                FilterNode.Leaf(
                                    leafOn("id", FilterOp.Eq, FilterValue.Single(lit(LiteralKind.Int, "2"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun validateWarnsOnEmptyGroup() {
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Group(
                    FilterGroup(id = "g-empty", connector = GroupConnector.Or, children = emptyList()),
                ),
            ),
        )
        val outcome = validate(spec, sampleSchema(), emptyList()).unwrap().second
        assertTrue(outcome.warnings.any { it.contains("no conditions") })
    }

    @Test
    fun validateRejectsExcessiveNesting() {
        val spec = sampleSpec().copy(filters = buildDeepFilterGroup(MAX_FILTER_DEPTH + 2))
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("maximum depth"))
    }

    @Test
    fun classifyColumnCoversCommonTypes() {
        assertEquals(ColumnCategory.Integer, classifyColumn("int"))
        assertEquals(ColumnCategory.Integer, classifyColumn("INTEGER"))
        assertEquals(ColumnCategory.Decimal, classifyColumn("number"))
        assertEquals(ColumnCategory.Decimal, classifyColumn("NUMBER"))
        assertEquals(ColumnCategory.Text, classifyColumn("varchar"))
        assertEquals(ColumnCategory.Text, classifyColumn("VARCHAR2"))
        assertEquals(ColumnCategory.Bool, classifyColumn("boolean"))
        assertEquals(ColumnCategory.Date, classifyColumn("date"))
        assertEquals(ColumnCategory.DateTime, classifyColumn("timestamp without time zone"))
        assertEquals(ColumnCategory.DateTime, classifyColumn("datetime"))
        assertEquals(LiteralKind.Int, literalKindForColumn("int"))
        assertTrue(opsForColumn("int").contains(FilterOp.Between))
    }

    @Test
    fun inListAtMaxSizeIsAccepted() {
        val values = (0 until MAX_IN_LIST_SIZE).map { lit(LiteralKind.Int, it.toString()) }
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.In, FilterValue.ListValue(values)),
                ),
            ),
        )
        validate(spec, sampleSchema(), emptyList()).unwrap()
    }

    @Test
    fun inListOverMaxSizeIsRejected() {
        val values = (0..MAX_IN_LIST_SIZE).map { lit(LiteralKind.Int, it.toString()) }
        val spec = sampleSpec().copy(
            filters = sampleSpec().filters.copy(
                children = sampleSpec().filters.children + FilterNode.Leaf(
                    leafOn("id", FilterOp.In, FilterValue.ListValue(values)),
                ),
            ),
        )
        val err = validate(spec, sampleSchema(), emptyList()).unwrapErr()
        assertTrue(err.contains("too many values"))
    }

    @Test
    fun postgresCompilesQuotingPlaceholdersAndLimit() {
        val compiled = compile(twoTableSpec(), Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.contains("SELECT \"t0\".\"id\""))
        assertTrue(compiled.sql.contains("FROM \"public\".\"products\" AS \"t0\""))
        assertTrue(compiled.sql.contains("INNER JOIN \"public\".\"categories\" AS \"t1\""))
        assertTrue(compiled.sql.contains("\"t0\".\"name\" = \$1"))
        assertTrue(compiled.sql.contains("\"t0\".\"deleted_at\" IS NULL"))
        assertTrue(compiled.sql.endsWith("LIMIT 51"))
        assertEquals(1, compiled.params.size)
    }

    @Test
    fun distinctCompilesInDialectCorrectKeywordOrder() {
        val spec = sampleSpec().copy(distinct = true)

        assertTrue(compile(spec, Dialect.Postgres).unwrap().sql.startsWith("SELECT DISTINCT "))
        assertTrue(compile(spec, Dialect.MySql).unwrap().sql.startsWith("SELECT DISTINCT "))
        assertTrue(compile(spec, Dialect.Oracle).unwrap().sql.startsWith("SELECT DISTINCT "))
        assertTrue(compile(spec, Dialect.Mssql).unwrap().sql.startsWith("SELECT DISTINCT TOP 101 "))
        assertFalse(compile(sampleSpec(), Dialect.Postgres).unwrap().sql.startsWith("SELECT DISTINCT "))
    }

    @Test
    fun sortsAreValidatedQuotedAndCompiledBeforeLimitInPriorityOrder() {
        val spec = sampleSpec().copy(
            sorts = listOf(
                SortSpec("t0", "name", SortDirection.Desc),
                SortSpec("t0", "id", SortDirection.Asc),
            ),
        )

        val normalized = validate(spec, sampleSchema(), emptyList()).unwrap().first
        val sql = compile(normalized, Dialect.Postgres).unwrap().sql

        assertTrue(sql.contains("ORDER BY \"t0\".\"name\" DESC, \"t0\".\"id\" ASC"))
        assertTrue(sql.indexOf("ORDER BY") < sql.indexOf("LIMIT 101"))
    }

    @Test
    fun validationRejectsUnknownAndDuplicateSortColumns() {
        val unknownAlias = sampleSpec().copy(sorts = listOf(SortSpec("missing", "id")))
        assertTrue(validate(unknownAlias, sampleSchema(), emptyList()).unwrapErr().contains("unknown table alias"))

        val duplicate = sampleSpec().copy(sorts = listOf(SortSpec("t0", "id"), SortSpec("t0", "id", SortDirection.Desc)))
        assertTrue(validate(duplicate, sampleSchema(), emptyList()).unwrapErr().contains("duplicated"))
    }

    @Test
    fun groupsAreValidatedQuotedAndCompiledBeforeOrderAndLimitInPriorityOrder() {
        val spec = sampleSpec().copy(
            columns = listOf(ColumnSel("t0", "id"), ColumnSel("t0", "name")),
            groups = listOf(GroupSpec("t0", "name"), GroupSpec("t0", "id")),
            sorts = listOf(SortSpec("t0", "name")),
        )

        val normalized = validate(spec, sampleSchema(), emptyList()).unwrap().first
        val sql = compile(normalized, Dialect.Postgres).unwrap().sql

        assertTrue(sql.contains("GROUP BY \"t0\".\"name\", \"t0\".\"id\""))
        assertTrue(sql.indexOf("GROUP BY") < sql.indexOf("ORDER BY"))
        assertTrue(sql.indexOf("ORDER BY") < sql.indexOf("LIMIT 101"))

        val mysql = compile(normalized, Dialect.MySql).unwrap().sql
        assertTrue(mysql.contains("GROUP BY `t0`.`name`, `t0`.`id`"))
        assertTrue(mysql.indexOf("GROUP BY") < mysql.indexOf("ORDER BY"))
    }

    @Test
    fun validationRejectsInvalidGroupingAndUngroupedSorts() {
        val unknownAlias = sampleSpec().copy(groups = listOf(GroupSpec("missing", "id")))
        assertTrue(validate(unknownAlias, sampleSchema(), emptyList()).unwrapErr().contains("unknown table alias"))

        val missingOutput = sampleSpec().copy(groups = listOf(GroupSpec("t0", "id")), columns = emptyList())
        assertTrue(validate(missingOutput, sampleSchema(), emptyList()).unwrapErr().contains("explicitly selected"))

        val ungroupedOutput = sampleSpec().copy(groups = listOf(GroupSpec("t0", "name")))
        assertTrue(validate(ungroupedOutput, sampleSchema(), emptyList()).unwrapErr().contains("must appear in GROUP BY"))

        val ungroupedSort = sampleSpec().copy(
            groups = listOf(GroupSpec("t0", "id")),
            sorts = listOf(SortSpec("t0", "name")),
        )
        assertTrue(validate(ungroupedSort, sampleSchema(), emptyList()).unwrapErr().contains("Sort column"))

        val duplicate = sampleSpec().copy(groups = listOf(GroupSpec("t0", "id"), GroupSpec("t0", "id")))
        assertTrue(validate(duplicate, sampleSchema(), emptyList()).unwrapErr().contains("duplicated"))
    }

    @Test
    fun dialectValidationRejectsNonComparableGroupAndSortTypes() {
        val jsonColumn = ColumnInfo(
            name = "payload",
            dataType = "json",
            nullable = true,
            isIndexed = false,
            category = ColumnCategory.Json,
        )
        val users = sampleSchema().tables.first()
        val schema = sampleSchema().copy(
            tables = listOf(users.copy(columns = users.columns + jsonColumn)),
        )
        val grouped = sampleSpec().copy(
            columns = listOf(ColumnSel("t0", "payload")),
            groups = listOf(GroupSpec("t0", "payload")),
        )
        val sorted = sampleSpec().copy(
            sorts = listOf(SortSpec("t0", "payload")),
        )

        assertTrue(
            validate(grouped, schema, emptyList(), Dialect.Postgres)
                .unwrapErr()
                .contains("cannot be grouped by PostgreSQL"),
        )
        assertTrue(
            validate(sorted, schema, emptyList(), Dialect.Postgres)
                .unwrapErr()
                .contains("cannot be sorted by PostgreSQL"),
        )
        assertTrue(supportsGroupingAndSorting("jsonb", Dialect.Postgres))
        assertFalse(supportsGroupingAndSorting("CLOB", Dialect.Oracle))
        assertFalse(supportsGroupingAndSorting("ntext", Dialect.Mssql))
    }

    @Test
    fun olderSerializedQueryWithoutSortsOrGroupsUsesEmptyDefaults() {
        val decoded = com.safedb.model.SafeDbJson.lenient.decodeFromString<QuerySpec>(
            """{"tables":[],"columns":[],"joins":[],"filters":{"children":[]},"limit":100}""",
        )

        assertTrue(decoded.sorts.isEmpty())
        assertTrue(decoded.groups.isEmpty())
        assertFalse(decoded.distinct)
    }

    @Test
    fun postgresCompilesLargeLimitPlusOneForTruncationDetection() {
        val spec = twoTableSpec().copy(limit = MAX_LIMIT)
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.endsWith("LIMIT 5001"))
    }

    @Test
    fun mysqlCompilesBackticksAndQuestionMarkParams() {
        val compiled = compile(twoTableSpec(), Dialect.MySql).unwrap()
        assertTrue(compiled.sql.contains("SELECT `t0`.`id`"))
        assertTrue(compiled.sql.contains("`t0`.`name` = ?"))
        assertTrue(compiled.sql.endsWith("LIMIT 51"))
        assertEquals(1, compiled.params.size)
    }

    @Test
    fun mssqlCompilesTopInsteadOfLimit() {
        val compiled = compile(twoTableSpec(), Dialect.Mssql).unwrap()
        assertTrue(compiled.sql.contains("SELECT TOP 51 "))
        assertTrue(compiled.sql.contains("[t0].[name] = @P1"))
        assertFalse(compiled.sql.contains("LIMIT"))
    }

    @Test
    fun oracleCompilesFetchFirst() {
        val compiled = compile(twoTableSpec(), Dialect.Oracle).unwrap()
        assertTrue(compiled.sql.contains("FETCH FIRST 51 ROWS ONLY"))
        assertTrue(compiled.sql.contains(":1"))
    }

    @Test
    fun emptyColumnsSelectStar() {
        val spec = twoTableSpec().copy(
            columns = emptyList(),
            joins = emptyList(),
            tables = listOf(twoTableSpec().tables[0]),
            filters = FilterGroup(id = "g0", connector = GroupConnector.And, children = emptyList()),
        )
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.startsWith("SELECT *"))
    }

    @Test
    fun nestedGroupsEmitParens() {
        val spec = QuerySpec(
            tables = listOf(TableRef(schema = "public", name = "products", alias = "t0")),
            columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
            joins = emptyList(),
            filters = FilterGroup(
                id = "g0",
                connector = GroupConnector.And,
                children = listOf(
                    FilterNode.Group(
                        FilterGroup(
                            id = "g1",
                            connector = GroupConnector.Or,
                            children = listOf(
                                FilterNode.Leaf(
                                    FilterSpec(
                                        id = "l0",
                                        tableAlias = "t0",
                                        column = "id",
                                        op = FilterOp.Eq,
                                        value = FilterValue.Single(lit(LiteralKind.Int, "1")),
                                    ),
                                ),
                                FilterNode.Leaf(
                                    FilterSpec(
                                        id = "l1",
                                        tableAlias = "t0",
                                        column = "id",
                                        op = FilterOp.Eq,
                                        value = FilterValue.Single(lit(LiteralKind.Int, "2")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            limit = 100,
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.contains("WHERE (\"t0\".\"id\" = \$1 OR \"t0\".\"id\" = \$2)"))
        assertEquals(2, compiled.params.size)
    }

    @Test
    fun inCompilesMultiplePlaceholders() {
        val spec = QuerySpec(
            tables = listOf(TableRef(schema = "public", name = "products", alias = "t0")),
            columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
            joins = emptyList(),
            filters = FilterGroup(
                id = "g0",
                connector = GroupConnector.And,
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            id = "l0",
                            tableAlias = "t0",
                            column = "id",
                            op = FilterOp.In,
                            value = FilterValue.ListValue(
                                listOf(
                                    lit(LiteralKind.Int, "1"),
                                    lit(LiteralKind.Int, "2"),
                                    lit(LiteralKind.Int, "3"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            limit = 100,
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.contains("\"t0\".\"id\" IN (\$1, \$2, \$3)"))
        assertEquals(3, compiled.params.size)
    }

    @Test
    fun notInCompiles() {
        val spec = QuerySpec(
            tables = listOf(TableRef(schema = "public", name = "products", alias = "t0")),
            columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
            joins = emptyList(),
            filters = FilterGroup(
                id = "g0",
                connector = GroupConnector.And,
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            id = "l0",
                            tableAlias = "t0",
                            column = "id",
                            op = FilterOp.NotIn,
                            value = FilterValue.ListValue(
                                listOf(lit(LiteralKind.Int, "4"), lit(LiteralKind.Int, "5")),
                            ),
                        ),
                    ),
                ),
            ),
            limit = 100,
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.contains("\"t0\".\"id\" NOT IN (\$1, \$2)"))
    }

    @Test
    fun betweenCompilesTwoPlaceholders() {
        val spec = QuerySpec(
            tables = listOf(TableRef(schema = "public", name = "products", alias = "t0")),
            columns = listOf(ColumnSel(tableAlias = "t0", column = "price")),
            joins = emptyList(),
            filters = FilterGroup(
                id = "g0",
                connector = GroupConnector.And,
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            id = "l0",
                            tableAlias = "t0",
                            column = "price",
                            op = FilterOp.Between,
                            value = FilterValue.Pair(
                                lit(LiteralKind.Int, "10"),
                                lit(LiteralKind.Int, "100"),
                            ),
                        ),
                    ),
                ),
            ),
            limit = 100,
            schemaVersion = CURRENT_SCHEMA_VERSION,
        )
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.contains("\"t0\".\"price\" BETWEEN \$1 AND \$2"))
        assertEquals(2, compiled.params.size)
    }

    @Test
    fun runQueryCoreTruncatesResultsToLimit() = runBlocking {
        val def = sampleConnection("c1")
        val spec = sampleSpec().copy(limit = 1)
        val runner = MockRunner(
            explainResult = ExplainResult.Estimated(0.0),
            execute = Outcome.ok(
                QueryResult(
                    columns = emptyList(),
                    rows = listOf(emptyList(), emptyList(), emptyList()),
                    rowCount = 3,
                    truncated = false,
                    warnings = listOf("adapter warning"),
                ),
            ),
        )

        val result = when (val outcome = runQueryCore(runner, def, spec, sampleSchema(), sampleSettings(), false)) {
            is QueryCoreOutcome.Success -> outcome.result
            is QueryCoreOutcome.Failure -> error(outcome.error.message)
        }

        assertEquals(1, result.rowCount)
        assertTrue(result.truncated)
        assertEquals(listOf("adapter warning"), result.warnings)
    }

    @Test
    fun runQueryCoreSuccessCarriesNormalizedHistorySpec() = runBlocking {
        val def = sampleConnection("c1")
        val spec = sampleSpec().copy(limit = MAX_LIMIT + 1)
        val runner = MockRunner(explainResult = ExplainResult.Estimated(0.0))

        val success = when (val outcome = runQueryCore(runner, def, spec, sampleSchema(), sampleSettings(), false)) {
            is QueryCoreOutcome.Success -> outcome
            is QueryCoreOutcome.Failure -> error(outcome.error.message)
        }

        assertEquals(MAX_LIMIT, success.historySpec.limit)
        assertEquals(MAX_LIMIT + 1, spec.limit)
    }

    @Test
    fun runQueryCoreBlocksWhenCostExceedsThreshold() = runBlocking {
        val def = sampleConnection("c1")
        val spec = sampleSpec()
        val settings = sampleSettings().copy(
            explainCostThreshold = 1.0,
            explainCostThresholds = mapOf(Dialect.Postgres to 1.0),
        )
        val runner = MockRunner(explainResult = ExplainResult.Estimated(5.0))

        val failure = when (val outcome = runQueryCore(runner, def, spec, sampleSchema(), settings, false)) {
            is QueryCoreOutcome.Success -> error("expected failure")
            is QueryCoreOutcome.Failure -> outcome.error
        }

        assertTrue(failure.error is QueryError.CostGuard)
        assertTrue(failure.warnings.any { it.contains("exceeds threshold") })
        assertEquals(100, failure.historySpec?.limit)
    }

    @Test
    fun runQueryCoreCostGuardCarriesNormalizedHistorySpec() = runBlocking {
        val def = sampleConnection("c1")
        val spec = sampleSpec().copy(limit = MAX_LIMIT + 1)
        val settings = sampleSettings().copy(
            explainCostThreshold = 1.0,
            explainCostThresholds = mapOf(Dialect.Postgres to 1.0),
        )
        val runner = MockRunner(explainResult = ExplainResult.Estimated(5.0))

        val failure = when (val outcome = runQueryCore(runner, def, spec, sampleSchema(), settings, false)) {
            is QueryCoreOutcome.Success -> error("expected failure")
            is QueryCoreOutcome.Failure -> outcome.error
        }

        assertEquals(MAX_LIMIT, failure.historySpec?.limit)
    }

    @Test
    fun runQueryCoreAllowsForcedRetryAfterCostGuard() = runBlocking {
        val def = sampleConnection("c1")
        val spec = sampleSpec()
        val settings = sampleSettings().copy(
            explainCostThreshold = 1.0,
            explainCostThresholds = mapOf(Dialect.Postgres to 1.0),
        )
        val runner = MockRunner(explainResult = ExplainResult.Estimated(5.0))

        val result = when (val outcome = runQueryCore(runner, def, spec, sampleSchema(), settings, true)) {
            is QueryCoreOutcome.Success -> outcome.result
            is QueryCoreOutcome.Failure -> error(outcome.error.message)
        }

        assertEquals(0, result.rowCount)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun resultCellTextTruncatesOnUtf8ByteBoundary() {
        val value = "a".repeat(com.safedb.model.MAX_CELL_BYTES - 1) + "é"
        val cell = ResultCell.text(value) as ResultCell.TextCell

        assertTrue(cell.value.truncated)
        assertEquals(com.safedb.model.MAX_CELL_BYTES - 1, cell.value.text.toByteArray(Charsets.UTF_8).size)
        assertTrue(cell.value.text.endsWith("a"))
    }

    @Test
    fun offsetDateTimeLiteralNormalizesToUtc() {
        val parsed = parseDateTimeLiteral("2026-07-01T12:30:00-05:00").getOrThrow()

        assertEquals("2026-07-01T17:30", parsed.toString())
    }

    private fun sampleConnection(id: String) = ConnectionDef(
        id = id,
        name = "Conn $id",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "demo",
        username = "user",
    )

    private fun sampleSettings() = Settings(blockedSchemas = emptyList())

    private fun sampleSchema() = Schema(
        tables = listOf(
            TableInfo(
                schema = "public",
                name = "users",
                columns = listOf(
                    ColumnInfo(
                        name = "id",
                        dataType = "int",
                        nullable = false,
                        isIndexed = true,
                        joinEligible = true,
                        category = ColumnCategory.Integer,
                    ),
                    ColumnInfo(
                        name = "name",
                        dataType = "text",
                        nullable = true,
                        isIndexed = false,
                        joinEligible = false,
                        category = ColumnCategory.Text,
                    ),
                    ColumnInfo(
                        name = "category_id",
                        dataType = "int",
                        nullable = true,
                        isIndexed = true,
                        joinEligible = true,
                        category = ColumnCategory.Integer,
                    ),
                ),
                indexes = listOf(
                    IndexInfo(
                        name = "users_pkey",
                        columns = listOf("id"),
                        supportsEquality = true,
                        isUnique = true,
                        isPrimary = true,
                    ),
                    IndexInfo(
                        name = "users_category_id_idx",
                        columns = listOf("category_id"),
                        supportsEquality = true,
                        isUnique = false,
                        isPrimary = false,
                    ),
                ),
            ),
            TableInfo(
                schema = "public",
                name = "categories",
                columns = listOf(
                    ColumnInfo(
                        name = "id",
                        dataType = "int",
                        nullable = false,
                        isIndexed = true,
                        joinEligible = true,
                        category = ColumnCategory.Integer,
                    ),
                    ColumnInfo(
                        name = "name",
                        dataType = "text",
                        nullable = true,
                        isIndexed = false,
                        joinEligible = false,
                        category = ColumnCategory.Text,
                    ),
                ),
                indexes = listOf(
                    IndexInfo(
                        name = "categories_pkey",
                        columns = listOf("id"),
                        supportsEquality = true,
                        isUnique = true,
                        isPrimary = true,
                    ),
                ),
            ),
        ),
    )

    private fun sampleSpec() = QuerySpec(
        tables = listOf(TableRef(schema = "public", name = "users", alias = "t0")),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

    private fun textFilterSpec(op: FilterOp, value: String): QuerySpec = sampleSpec().copy(
        filters = FilterGroup(
            id = "g0",
            children = listOf(
                FilterNode.Leaf(
                    FilterSpec(
                        id = "text-pattern",
                        tableAlias = "t0",
                        column = "name",
                        op = op,
                        value = FilterValue.Single(FilterLiteral(LiteralKind.Text, value)),
                    ),
                ),
            ),
        ),
    )

    private fun lit(kind: LiteralKind, text: String) = FilterLiteral(kind = kind, text = text)

    private fun leaf(op: FilterOp, value: FilterValue?) = FilterSpec(
        id = java.util.UUID.randomUUID().toString(),
        tableAlias = "t0",
        column = "id",
        op = op,
        value = value,
    )

    private fun leafOn(column: String, op: FilterOp, value: FilterValue?) = FilterSpec(
        id = java.util.UUID.randomUUID().toString(),
        tableAlias = "t0",
        column = column,
        op = op,
        value = value,
    )

    private fun twoTableSpec() = QuerySpec(
        tables = listOf(
            TableRef(schema = "public", name = "products", alias = "t0"),
            TableRef(schema = "public", name = "categories", alias = "t1"),
        ),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "id")),
        joins = listOf(
            JoinSpec(
                leftAlias = "t0",
                leftColumn = "category_id",
                rightAlias = "t1",
                rightColumn = "id",
            ),
        ),
        filters = FilterGroup(
            id = "g0",
            connector = GroupConnector.And,
            children = listOf(
                FilterNode.Leaf(
                    FilterSpec(
                        id = "l0",
                        tableAlias = "t0",
                        column = "name",
                        op = FilterOp.Eq,
                        value = FilterValue.Single(lit(LiteralKind.Text, "widget")),
                    ),
                ),
                FilterNode.Leaf(
                    FilterSpec(
                        id = "l1",
                        tableAlias = "t0",
                        column = "deleted_at",
                        op = FilterOp.IsNull,
                    ),
                ),
            ),
        ),
        limit = 50,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

    private fun buildDeepFilterGroup(levels: Int): FilterGroup {
        var current = FilterGroup(
            id = "g-${levels - 1}",
            connector = GroupConnector.And,
            children = emptyList(),
        )
        for (i in levels - 2 downTo 0) {
            current = FilterGroup(
                id = "g-$i",
                connector = GroupConnector.And,
                children = listOf(FilterNode.Group(current)),
            )
        }
        return current
    }

    private class MockRunner(
        private var explainResult: ExplainResult = ExplainResult.Estimated(0.0),
        private var execute: Outcome<QueryResult> = Outcome.ok(
            QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            ),
        ),
    ) : QueryRunner {
        var executeCalls = 0

        override suspend fun explain(compiled: CompiledQuery): ExplainResult = explainResult

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> {
            executeCalls++
            return execute
        }
    }
}

private fun <T> Outcome<T>.unwrap(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected Ok, got Err: $message")
}

private fun <T> Outcome<T>.unwrapErr(): String = when (this) {
    is Outcome.Err -> message
    is Outcome.Ok -> throw AssertionError("expected Err, got Ok")
}
