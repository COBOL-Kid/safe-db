package com.safedb.query

import com.safedb.model.ColumnCategory
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
import com.safedb.model.GroupConnector
import com.safedb.model.IndexInfo
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.Schema
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
    fun postgresCompilesLargeLimitPlusOneForTruncationDetection() {
        val spec = twoTableSpec().copy(limit = 10_000)
        val compiled = compile(spec, Dialect.Postgres).unwrap()
        assertTrue(compiled.sql.endsWith("LIMIT 10001"))
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

        assertTrue(failure.message.startsWith(COST_GUARD_PREFIX))
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
