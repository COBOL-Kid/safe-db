package com.safedb.export

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvWriterTest {
    @Test
    fun escapesCsvHeadersAndCells() {
        val output = ByteArrayOutputStream()
        val result = QueryResult(
            columns = listOf(
                ResultColumn("plain", "varchar"),
                ResultColumn("needs,quote", "varchar"),
                ResultColumn("empty", "varchar"),
            ),
            rows = listOf(
                listOf(
                    ResultCell.text("Ada"),
                    ResultCell.text("hello, \"world\"\nagain"),
                    ResultCell.Null,
                ),
            ),
            rowCount = 1,
            truncated = false,
            warnings = emptyList(),
        )

        writeQueryResultCsv(result, output)

        assertEquals(
            "plain,\"needs,quote\",empty\r\nAda,\"hello, \"\"world\"\"\nagain\",\r\n",
            output.toString(Charsets.UTF_8),
        )
    }
}
