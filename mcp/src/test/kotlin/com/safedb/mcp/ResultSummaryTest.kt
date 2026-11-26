package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class ResultSummaryTest {
    @Test
    fun summarizesNullsMinMaxMixedTypesAndDistinctCap() {
        val rows =
            (1..9).map { index ->
                listOf(
                    when (index) {
                        1 -> ResultCell.Null
                        2 -> ResultCell.float(2.5)
                        else -> ResultCell.integer(index.toLong())
                    },
                    ResultCell.text(if (index % 2 == 0) "a" else "b"),
                    ResultCell.bool(index % 2 == 0),
                    if (index == 1) ResultCell.Null
                    else ResultCell.binary(byteArrayOf(index.toByte())),
                )
            }
        val result =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("num", "float"),
                        ResultColumn("label", "text"),
                        ResultColumn("flag", "bool"),
                        ResultColumn("blob", "bytea"),
                    ),
                rows = rows,
                rowCount = 9,
                truncated = false,
                warnings = emptyList(),
            )
        val columns = summarizeColumns(result)
        assertEquals(4, columns.size)

        val num = columns[0]
        assertEquals(1, num.nullCount)
        assertEquals(JsonPrimitive(2.5), num.min)
        assertEquals(JsonPrimitive(9L), num.max)
        assertEquals(DISTINCT_VALUE_LIMIT, num.distinct.size)
        assertTrue(num.distinctTruncated)
        assertEquals(JsonNull, num.distinct.first())

        val label = columns[1]
        assertEquals(0, label.nullCount)
        assertEquals(JsonPrimitive("a"), label.min)
        assertEquals(JsonPrimitive("b"), label.max)
        assertEquals(2, label.distinct.size)
        assertFalse(label.distinctTruncated)

        val flag = columns[2]
        assertEquals(JsonPrimitive(false), flag.min)
        assertEquals(JsonPrimitive(true), flag.max)

        val blob = columns[3]
        assertEquals(1, blob.nullCount)
        assertNull(blob.min)
        assertNull(blob.max)
        val encoded = toolJson.encodeToString(ColumnSummary.serializer(), blob)
        assertFalse(encoded.contains("\"min\""))
        assertFalse(encoded.contains("\"max\""))
        assertTrue(blob.distinctTruncated)
    }

    @Test
    fun allNullColumnOmitsMinMaxWithoutThrowing() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("empty", "int")),
                rows = listOf(listOf(ResultCell.Null), listOf(ResultCell.Null)),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val columns = summarizeColumns(result)
        val empty = columns.single()
        assertEquals(result.rowCount, empty.nullCount)
        assertNull(empty.min)
        assertNull(empty.max)
        val encoded = toolJson.encodeToString(ColumnSummary.serializer(), empty)
        assertFalse(encoded.contains("\"min\""))
        assertFalse(encoded.contains("\"max\""))
    }

    @Test
    fun integerMinMaxAroundTwoToThe53StaysExact() {
        val smaller = 1L shl 53
        val larger = smaller + 1
        val result =
            QueryResult(
                columns = listOf(ResultColumn("big", "bigint")),
                rows =
                    listOf(
                        listOf(ResultCell.integer(larger)),
                        listOf(ResultCell.integer(smaller)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive(smaller), column.min)
        assertEquals(JsonPrimitive(larger), column.max)
        assertEquals("9007199254740992", (column.min as JsonPrimitive).content)
        assertEquals("9007199254740993", (column.max as JsonPrimitive).content)
    }

    @Test
    fun mixedIntegerAndTextColumnOmitsMinMax() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("mixed", "text")),
                rows =
                    listOf(
                        listOf(ResultCell.IntegerCell(1L)),
                        listOf(ResultCell.text("a")),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val columns = summarizeColumns(result)
        val mixed = columns.single()
        assertNull(mixed.min)
        assertNull(mixed.max)
        val encoded = toolJson.encodeToString(ColumnSummary.serializer(), mixed)
        assertFalse(encoded.contains("\"min\""))
        assertFalse(encoded.contains("\"max\""))
    }

    @Test
    fun decimalTextColumnComparesNumerically() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("amount", "decimal")),
                rows =
                    listOf("146.97", "97.64", "25.68", "501.23").map {
                        listOf(ResultCell.text(it))
                    },
                rowCount = 4,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive("25.68"), column.min)
        assertEquals(JsonPrimitive("501.23"), column.max)
    }

    @Test
    fun oracleNumberIntegerTextColumnComparesNumerically() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("total", "NUMBER")),
                rows = listOf("1420", "350", "890").map { listOf(ResultCell.text(it)) },
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive("350"), column.min)
        assertEquals(JsonPrimitive("1420"), column.max)
    }

    @Test
    fun numericTextColumnSkipsUnparseableValues() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("amount", "decimal")),
                rows = listOf("25.68", "N/A", "501.23").map { listOf(ResultCell.text(it)) },
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive("25.68"), column.min)
        assertEquals(JsonPrimitive("501.23"), column.max)
    }

    @Test
    fun digitStringVarcharColumnStaysLexicographic() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("code", "varchar")),
                rows = listOf("1420", "350", "890").map { listOf(ResultCell.text(it)) },
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive("1420"), column.min)
        assertEquals(JsonPrimitive("890"), column.max)
    }

    @Test
    fun floatColumnIgnoresNaNInMinMaxAndHandlesAllNaN() {
        val mixed =
            QueryResult(
                columns =
                    listOf(ResultColumn("score", "float8"), ResultColumn("nan_only", "float8")),
                rows =
                    listOf(
                        listOf(ResultCell.float(Double.NaN), ResultCell.float(Double.NaN)),
                        listOf(ResultCell.float(3.5), ResultCell.Null),
                        listOf(ResultCell.float(-1.25), ResultCell.float(Double.NaN)),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val columns = summarizeColumns(mixed)
        assertEquals(JsonPrimitive(-1.25), columns[0].min)
        assertEquals(JsonPrimitive(3.5), columns[0].max)
        assertNull(columns[1].min)
        assertNull(columns[1].max)
    }

    @Test
    fun mixedIntegerAndInfiniteFloatColumnResolvesMinMaxWithoutThrowing() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("val", "float8")),
                rows =
                    listOf(
                        listOf(ResultCell.integer(42L)),
                        listOf(ResultCell.float(Double.POSITIVE_INFINITY)),
                        listOf(ResultCell.float(Double.NEGATIVE_INFINITY)),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val column = summarizeColumns(result).single()
        assertEquals(JsonPrimitive("-Infinity"), column.min)
        assertEquals(JsonPrimitive("Infinity"), column.max)
    }
}
