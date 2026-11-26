package com.safedb.explore

import com.safedb.model.ResultCell
import java.math.BigDecimal

internal fun resultCellText(cell: ResultCell?): String =
    when (cell) {
        null,
        ResultCell.Null -> ""
        is ResultCell.BoolCell -> cell.value.toString()
        is ResultCell.IntegerCell -> cell.value.toString()
        is ResultCell.FloatCell -> cell.value.toString()
        is ResultCell.TextCell -> cell.value.text
        is ResultCell.BinaryCell -> cell.value.base64
    }

internal fun resultCellDecimal(cell: ResultCell?): BigDecimal? =
    when (cell) {
        is ResultCell.IntegerCell -> BigDecimal.valueOf(cell.value)
        is ResultCell.FloatCell -> cell.value.takeIf { it.isFinite() }?.let(BigDecimal::valueOf)
        is ResultCell.TextCell -> cell.value.text.decimalOrNull()
        else -> null
    }

private fun String.decimalOrNull(): BigDecimal? = trim().toBigDecimalOrNull()

// Ordering can include infinities, even though decimal arithmetic cannot.
sealed class ResultNumber(private val order: Int) : Comparable<ResultNumber> {
    private data class Finite(val value: BigDecimal) : ResultNumber(0)

    private data object NegativeInfinity : ResultNumber(-1)

    private data object PositiveInfinity : ResultNumber(1)

    override fun compareTo(other: ResultNumber): Int =
        if (this is Finite && other is Finite) value.compareTo(other.value)
        else order.compareTo(other.order)

    companion object {
        fun fromCell(cell: ResultCell?, numericText: Boolean): ResultNumber? =
            when {
                cell is ResultCell.TextCell -> if (numericText) parse(cell.value.text) else null
                cell is ResultCell.FloatCell && cell.value.isInfinite() ->
                    if (cell.value < 0) NegativeInfinity else PositiveInfinity
                else -> resultCellDecimal(cell)?.let(::Finite)
            }

        fun parse(text: String): ResultNumber? =
            when (text.trim().lowercase()) {
                "-infinity" -> NegativeInfinity
                "infinity",
                "+infinity" -> PositiveInfinity
                else -> text.decimalOrNull()?.let(::Finite)
            }
    }
}
