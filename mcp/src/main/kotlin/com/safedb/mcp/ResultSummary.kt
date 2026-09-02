package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal const val DISTINCT_VALUE_LIMIT = 8

@Serializable
internal data class ResultSummary(
    @SerialName("result_id") val resultId: String,
    @SerialName("row_count") val rowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    val columns: List<ColumnSummary>,
)

@Serializable
internal data class ColumnSummary(
    val name: String,
    @SerialName("data_type") val dataType: String,
    @SerialName("null_count") val nullCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val min: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val max: JsonElement? = null,
    val distinct: List<JsonElement>,
    @SerialName("distinct_truncated") val distinctTruncated: Boolean,
)

internal fun summarizeColumns(result: QueryResult): List<ColumnSummary> =
    result.columns.mapIndexed { colIndex, column ->
        var nullCount = 0
        val distinct = mutableListOf<JsonElement>()
        var distinctTruncated = false
        val comparable = mutableListOf<ResultCell>()
        for (row in result.rows) {
            val cell = row.getOrElse(colIndex) { ResultCell.Null }
            if (cell is ResultCell.Null) nullCount++
            val flat = flattenCell(cell)
            when {
                distinct.any { it == flat } -> {}
                distinct.size < DISTINCT_VALUE_LIMIT -> distinct += flat
                else -> distinctTruncated = true
            }
            when (cell) {
                ResultCell.Null,
                is ResultCell.BinaryCell -> {}
                else -> comparable += cell
            }
        }
        val (min, max) = columnMinMax(comparable)
        ColumnSummary(
            name = column.name,
            dataType = column.dataType,
            nullCount = nullCount,
            min = min,
            max = max,
            distinct = distinct,
            distinctTruncated = distinctTruncated,
        )
    }

private fun columnMinMax(cells: List<ResultCell>): Pair<JsonElement?, JsonElement?> {
    if (cells.isEmpty()) return null to null
    val allNumeric = cells.all { it is ResultCell.IntegerCell || it is ResultCell.FloatCell }
    val allText = cells.all { it is ResultCell.TextCell }
    val allBool = cells.all { it is ResultCell.BoolCell }
    return when {
        allNumeric -> {
            val usable = cells.filter { cell ->
                when (cell) {
                    is ResultCell.FloatCell -> !cell.value.isNaN()
                    is ResultCell.IntegerCell -> true
                    else -> false
                }
            }
            if (usable.isEmpty()) return null to null
            flattenCell(usable.minBy { numericValue(it) }) to
                flattenCell(usable.maxBy { numericValue(it) })
        }
        allText -> {
            val texts = cells.filterIsInstance<ResultCell.TextCell>()
            flattenCell(texts.minBy { it.value.text }) to flattenCell(texts.maxBy { it.value.text })
        }
        allBool -> {
            val flags = cells.filterIsInstance<ResultCell.BoolCell>()
            flattenCell(flags.minBy { it.value }) to flattenCell(flags.maxBy { it.value })
        }
        else -> null to null
    }
}

private fun numericValue(cell: ResultCell): Double =
    when (cell) {
        is ResultCell.IntegerCell -> cell.value.toDouble()
        is ResultCell.FloatCell -> cell.value
        else -> error("not numeric")
    }
