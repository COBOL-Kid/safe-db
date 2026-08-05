package com.safedb.export

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

fun writeQueryResultCsv(result: QueryResult, output: OutputStream) {
    BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
        writer.writeCsvRow(result.columns.map { it.name })
        for (row in result.rows) {
            writer.writeCsvRow(row.map(::cellToCsvValue))
        }
    }
}

private fun BufferedWriter.writeCsvRow(values: List<String>) {
    write(values.joinToString(",") { it.csvEscaped() })
    write("\r\n")
}

private fun String.csvEscaped(): String {
    val needsQuotes = any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return this
    return "\"" + replace("\"", "\"\"") + "\""
}

private fun cellToCsvValue(cell: ResultCell): String =
    when (cell) {
        is ResultCell.Null -> ""
        is ResultCell.BoolCell -> cell.value.toString()
        is ResultCell.IntegerCell -> cell.value.toString()
        is ResultCell.FloatCell -> cell.value.toString()
        is ResultCell.TextCell -> cell.value.text
        is ResultCell.BinaryCell -> cell.value.base64
    }
