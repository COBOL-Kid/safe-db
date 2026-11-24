package com.safedb.export

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvWriterTest {
    @Test
    fun escapesCsvHeadersAndAllCellKinds() {
        val output = ByteArrayOutputStream()
        val result =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("plain", "varchar"),
                        ResultColumn("needs,quote", "varchar"),
                        ResultColumn("flag", "bool"),
                        ResultColumn("count", "bigint"),
                        ResultColumn("ratio", "double"),
                        ResultColumn("blob", "bytea"),
                        ResultColumn("empty", "varchar"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Ada"),
                            ResultCell.text("hello, \"world\"\r\nagain"),
                            ResultCell.BoolCell(true),
                            ResultCell.IntegerCell(42L),
                            ResultCell.FloatCell(3.5),
                            ResultCell.binary(byteArrayOf(1, 2, 3)),
                            ResultCell.Null,
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )

        writeQueryResultCsv(result, output)

        assertEquals(
            "plain,\"needs,quote\",flag,count,ratio,blob,empty\r\n" +
                "Ada,\"hello, \"\"world\"\"\r\nagain\",true,42,3.5,AQID,\r\n",
            output.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun writesHeaderOnlyForEmptyResult() {
        val output = ByteArrayOutputStream()
        val result =
            QueryResult(
                columns = listOf(ResultColumn("id", "int"), ResultColumn("name", "varchar")),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            )

        writeQueryResultCsv(result, output)

        assertEquals("id,name\r\n", output.toString(Charsets.UTF_8))
    }
}
