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
        is ResultCell.FloatCell -> BigDecimal.valueOf(cell.value)
        is ResultCell.TextCell ->
            cell.value.text.trim().takeIf(String::isNotEmpty)?.toBigDecimalOrNull()
        else -> null
    }
