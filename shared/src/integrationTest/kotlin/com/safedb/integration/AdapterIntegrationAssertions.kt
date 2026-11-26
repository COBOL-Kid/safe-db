package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.adapter.QueryTimedOutException
import com.safedb.adapter.isServerPlanTimeout
import com.safedb.model.ColumnSel
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
import com.safedb.model.Outcome
import com.safedb.model.PlanAccessMethod
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.RESULT_TRUNCATED_WARNING
import com.safedb.model.ResultCell
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableRef
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskSignalCode
import com.safedb.query.assessValidatedQueryRisk
import com.safedb.query.compileValidated
import com.safedb.query.literalKindForColumn
import com.safedb.query.refineRiskWithPlan
import com.safedb.query.validateQuery
import com.safedb.testsupport.IntegrationFixtures
import java.sql.SQLTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

internal object AdapterIntegrationAssertions {
    private val WIDE_ROWS_COLUMNS: List<String> =
        listOf(
            "id",
            "indexed_key",
            "col_int",
            "col_dec",
            "col_bool",
            "col_date",
            "col_text",
            "col_null",
        ) + (1..16).map { "col_${it.toString().padStart(2, '0')}" }

    fun assertDecodedOrdersRow(result: QueryResult) {
        assertTrue(result.rows.isNotEmpty())
        val total = assertIs<ResultCell.TextCell>(result.rows.first()[0])
        assertTrue(total.value.text.startsWith("146.97"), total.value.text)
        assertTrue(result.rows.any { it[1] is ResultCell.Null })
        val orderDate = assertIs<ResultCell.TextCell>(result.rows.first()[2])
        assertTrue("2025-06-01" in orderDate.value.text, orderDate.value.text)
    }

    fun assertEmptyCustomerLookupMetadata(result: QueryResult) {
        assertTrue(result.rows.isEmpty())
        assertEquals(listOf("t0__id", "t0__email"), result.columns.map { it.name })
    }

    inline fun assertExecutionTimeout(block: () -> Unit) {
        val startedAt = System.nanoTime()
        val failure = assertFailsWith<Exception>(block = block)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(
            elapsedMs in 800..5_000,
            "expected the 1s query budget to stop execution within 1-5s; stopped after ${elapsedMs}ms",
        )
        val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
        val texts = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        val timedOut =
            chain.any { it is QueryTimedOutException || it is SQLTimeoutException } ||
                isServerPlanTimeout(chain, texts) ||
                "timed out" in texts ||
                "timeout" in texts
        assertTrue(
            timedOut,
            "expected the 1s query budget to cancel execution, got: " +
                chain.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" },
        )
    }

    fun assertOrdersCustomerForeignKey(
        foreignKey: ForeignKeyInfo,
        referencedSchema: String,
        columns: List<String> = listOf("customer_id"),
        referencedTable: String = "customers",
        referencedColumns: List<String> = listOf("id"),
    ) {
        assertEquals(columns, foreignKey.columns)
        assertEquals(referencedSchema, foreignKey.referencedSchema)
        assertEquals(referencedTable, foreignKey.referencedTable)
        assertEquals(referencedColumns, foreignKey.referencedColumns)
    }

    suspend fun assertScanEventsExplainedAsLargeScan(
        adapter: Adapter,
        schema: Schema,
        dialect: Dialect,
        expectedSchema: String,
    ) {
        // Select both id and non-indexed amount so Postgres cannot satisfy the query with an Index
        // Only Scan (which maps to BoundedRange instead of TableScan/FullIndexScan).
        val table =
            IntegrationFixtures.requireSeededTable(
                schema,
                "scan_events",
                setOf("id", "amount"),
                expectedSchema = expectedSchema,
            )
        assertEquals(
            expectedSchema,
            table.schema,
            "scan_events must come from $expectedSchema, found ${table.schema}.${table.name}",
        )
        val spec =
            QuerySpec(
                tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
                columns =
                    listOf(
                        ColumnSel(
                            tableAlias = "t0",
                            column = IntegrationFixtures.seededColumn(table, "id"),
                        ),
                        ColumnSel(
                            tableAlias = "t0",
                            column = IntegrationFixtures.seededColumn(table, "amount"),
                        ),
                    ),
                joins = emptyList(),
                filters =
                    FilterGroup(
                        id = "integration-scan-events-filters",
                        connector = GroupConnector.And,
                        children = emptyList(),
                    ),
                limit = DEFAULT_LIMIT,
                uncapped = true,
            )
        val assessed =
            when (
                val result = assessValidatedQueryRisk(spec, schema, Settings.default(), dialect)
            ) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> error(result.message)
            }
        val compiled =
            when (val compilation = compileValidated(assessed.validated, dialect)) {
                is Outcome.Ok -> compilation.value
                is Outcome.Err -> error(compilation.message)
            }
        // EXPLAIN only: never execute the uncapped 1M-row query.
        val explain = Adapter.explainWithTimeout(adapter, compiled)
        val plan = assertIs<ExplainResult.Available>(explain, "Expected plan: $explain").plan
        assertTrue(
            plan.relations.any {
                it.method == PlanAccessMethod.TableScan ||
                    it.method == PlanAccessMethod.FullIndexScan
            },
            "expected TableScan or FullIndexScan for scan_events: $plan",
        )
        val staticAssessment = requireNotNull(assessed.assessment) { "query risk gate is disabled" }
        val refined = refineRiskWithPlan(staticAssessment, plan, assessed.normalizedSpec, schema)
        assertTrue(
            refined.severity == QueryRiskSeverity.Elevated ||
                refined.severity == QueryRiskSeverity.High ||
                refined.severity == QueryRiskSeverity.VeryHigh,
            "expected Elevated-or-higher live scan_events classification, got severity=${refined.severity} plan=$plan",
        )
        assertTrue(
            refined.signals.any { it.code == RiskSignalCode.PlanConfirmedLargeScan },
            "expected PlanConfirmedLargeScan for scan_events, got signals=${refined.signals.map { it.code }} plan=$plan",
        )
    }

    suspend fun assertWideRowsAndEventNotesDecoded(
        adapter: Adapter,
        schema: Schema,
        dialect: Dialect,
        expectedWideRowsSchema: String,
        expectedEventNotesSchema: String,
        expectedSpecialNoteBody: String,
    ) {
        val wideTable =
            IntegrationFixtures.requireSeededTable(
                schema,
                "wide_rows",
                WIDE_ROWS_COLUMNS.toSet(),
                expectedSchema = expectedWideRowsSchema,
            )
        assertEquals(expectedWideRowsSchema, wideTable.schema)
        val wideIdColName = IntegrationFixtures.seededColumn(wideTable, "id")
        val wideIdColInfo = wideTable.columns.single { it.name == wideIdColName }
        val wideSpec =
            QuerySpec(
                tables =
                    listOf(
                        TableRef(schema = wideTable.schema, name = wideTable.name, alias = "t0"),
                    ),
                columns =
                    WIDE_ROWS_COLUMNS.map {
                        ColumnSel(
                            tableAlias = "t0",
                            column = IntegrationFixtures.seededColumn(wideTable, it),
                        )
                    },
                joins = emptyList(),
                filters =
                    FilterGroup(
                        id = "wide-rows-id-lte-14",
                        connector = GroupConnector.And,
                        children =
                            listOf(
                                FilterNode.Leaf(
                                    FilterSpec(
                                        id = "wide-id-lte",
                                        tableAlias = "t0",
                                        column = wideIdColName,
                                        op = FilterOp.Lte,
                                        value =
                                            FilterValue.Single(
                                                FilterLiteral(
                                                    literalKindForColumn(wideIdColInfo.dataType),
                                                    "14",
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                    ),
                sorts =
                    listOf(
                        SortSpec(
                            tableAlias = "t0",
                            column = wideIdColName,
                            direction = SortDirection.Asc,
                        ),
                    ),
                limit = 50,
                uncapped = false,
            )
        val wideCompiled = compileSpec(wideSpec, schema, dialect)
        val wideResult = adapter.executeQuery(wideCompiled, 10_000)
        assertEquals(24, wideResult.columns.size)
        assertEquals(14, wideResult.rowCount)
        assertEquals(14, wideResult.rows.size)
        assertFalse(wideResult.truncated)
        wideResult.rows.forEach { row ->
            assertEquals(24, row.size)
            row.forEach { cell ->
                when (cell) {
                    is ResultCell.TextCell -> assertFalse(cell.value.truncated)
                    is ResultCell.BinaryCell -> assertFalse(cell.value.truncated)
                    else -> Unit
                }
            }
        }
        // col_null: id=1 -> "present", id=7 & id=14 -> Null
        val colNull1 = assertIs<ResultCell.TextCell>(wideResult.rows[0][7])
        assertEquals("present", colNull1.value.text)
        assertIs<ResultCell.Null>(wideResult.rows[6][7])
        assertIs<ResultCell.Null>(wideResult.rows[13][7])

        // col_text: Db2 pads CHAR(n) in 'wide-' || CHAR(n), so trim before comparing.
        val colText1 = assertIs<ResultCell.TextCell>(wideResult.rows[0][6])
        assertEquals("wide-1", colText1.value.text.trim())

        // col_dec: valid BigDecimal across all engines
        val colDec1 = assertIs<ResultCell.TextCell>(wideResult.rows[0][3])
        assertTrue(
            colDec1.value.text.toBigDecimalOrNull() != null,
            "expected valid BigDecimal for col_dec, got '${colDec1.value.text}'",
        )

        // col_bool: BoolCell (PG/MSSQL), IntegerCell (MySQL/Db2), or TextCell "0"/"1" (Oracle)
        val decodedBools = wideResult.rows.map { decodeBoolBit(it[4]) }
        assertFalse(decodedBools[0], "expected row id=1 col_bool to be false/0")
        assertTrue(decodedBools[1], "expected row id=2 col_bool to be true/1")

        // col_date: non-blank TextCell
        val colDate1 = assertIs<ResultCell.TextCell>(wideResult.rows[0][5])
        assertTrue(colDate1.value.text.isNotBlank())

        // col_01..col_16 (indices 8..23): IntegerCell or numeric TextCell (Oracle NUMBER)
        wideResult.rows.forEach { row ->
            for (idx in 8..23) {
                val cell = row[idx]
                assertTrue(
                    cell is ResultCell.IntegerCell ||
                        (cell is ResultCell.TextCell &&
                            cell.value.text.trim().toLongOrNull() != null),
                    "expected IntegerCell or numeric TextCell at column $idx, got $cell",
                )
            }
        }

        val notesCols = listOf("id", "event_id", "body", "flagged")
        val notesTable =
            IntegrationFixtures.requireSeededTable(
                schema,
                "event_notes",
                notesCols.toSet(),
                expectedSchema = expectedEventNotesSchema,
            )
        assertEquals(expectedEventNotesSchema, notesTable.schema)
        val notesIdColName = IntegrationFixtures.seededColumn(notesTable, "id")
        val notesIdColInfo = notesTable.columns.single { it.name == notesIdColName }
        val notesSpec =
            QuerySpec(
                tables =
                    listOf(
                        TableRef(schema = notesTable.schema, name = notesTable.name, alias = "t0"),
                    ),
                columns =
                    notesCols.map {
                        ColumnSel(
                            tableAlias = "t0",
                            column = IntegrationFixtures.seededColumn(notesTable, it),
                        )
                    },
                joins = emptyList(),
                filters =
                    FilterGroup(
                        id = "event-notes-id-lte-15",
                        connector = GroupConnector.And,
                        children =
                            listOf(
                                FilterNode.Leaf(
                                    FilterSpec(
                                        id = "notes-id-lte",
                                        tableAlias = "t0",
                                        column = notesIdColName,
                                        op = FilterOp.Lte,
                                        value =
                                            FilterValue.Single(
                                                FilterLiteral(
                                                    literalKindForColumn(notesIdColInfo.dataType),
                                                    "15",
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                    ),
                sorts =
                    listOf(
                        SortSpec(
                            tableAlias = "t0",
                            column = notesIdColName,
                            direction = SortDirection.Asc,
                        ),
                    ),
                limit = 50,
                uncapped = false,
            )
        val notesCompiled = compileSpec(notesSpec, schema, dialect)
        val notesResult = adapter.executeQuery(notesCompiled, 10_000)
        assertEquals(15, notesResult.rowCount)
        assertEquals(15, notesResult.rows.size)
        assertFalse(notesResult.truncated)

        val body1 = assertIs<ResultCell.TextCell>(notesResult.rows[0][2])
        assertEquals("note body 1", body1.value.text.trim())
        assertIs<ResultCell.Null>(notesResult.rows[10][2])
        val body13 = assertIs<ResultCell.TextCell>(notesResult.rows[12][2])
        assertEquals(expectedSpecialNoteBody, body13.value.text)
    }

    suspend fun assertPayloadRowsTruncatedAtOutputCap(
        adapter: Adapter,
        schema: Schema,
        dialect: Dialect,
        expectedSchema: String,
        timeoutMs: Int = 30_000,
    ) {
        val table =
            IntegrationFixtures.requireSeededTable(
                schema,
                "payload_rows",
                setOf("id", "payload"),
                expectedSchema = expectedSchema,
            )
        assertEquals(expectedSchema, table.schema)
        val spec =
            QuerySpec(
                tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
                columns =
                    listOf(
                        ColumnSel(
                            tableAlias = "t0",
                            column = IntegrationFixtures.seededColumn(table, "payload"),
                        ),
                    ),
                joins = emptyList(),
                filters =
                    FilterGroup(
                        id = "payload-rows-filters",
                        connector = GroupConnector.And,
                        children = emptyList(),
                    ),
                limit = DEFAULT_LIMIT,
                uncapped = true,
            )
        val compiled = compileSpec(spec, schema, dialect)
        val result = adapter.executeQuery(compiled, timeoutMs)
        assertTrue(result.truncated, "expected payload_rows read to hit byte budget")
        assertTrue(
            result.rowCount in 1 until 20_000,
            "expected partial payload_rows read within output cap, got ${result.rowCount}",
        )
        assertTrue(
            RESULT_TRUNCATED_WARNING in result.warnings,
            "expected RESULT_TRUNCATED_WARNING, got ${result.warnings}",
        )
        val firstCell = assertIs<ResultCell.TextCell>(result.rows.first().single())
        assertEquals(2048, firstCell.value.text.length)
        assertFalse(firstCell.value.truncated)
    }

    private fun compileSpec(spec: QuerySpec, schema: Schema, dialect: Dialect) =
        when (val validated = validateQuery(spec, schema, emptyList(), dialect)) {
            is Outcome.Err -> error(validated.message)
            is Outcome.Ok ->
                when (val compiled = compileValidated(validated.value.first, dialect)) {
                    is Outcome.Err -> error(compiled.message)
                    is Outcome.Ok -> compiled.value
                }
        }

    private fun decodeBoolBit(cell: ResultCell): Boolean =
        when (cell) {
            is ResultCell.BoolCell -> cell.value
            is ResultCell.IntegerCell ->
                when (cell.value) {
                    0L -> false
                    1L -> true
                    else -> fail("expected 0 or 1 for IntegerCell bool, got ${cell.value}")
                }
            is ResultCell.TextCell ->
                when (cell.value.text.trim()) {
                    "0" -> false
                    "1" -> true
                    else -> fail("expected '0' or '1' for TextCell bool, got '${cell.value.text}'")
                }
            else -> fail("expected BoolCell, IntegerCell, or TextCell, got $cell")
        }
}
