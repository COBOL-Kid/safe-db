package com.safedb.integration

import com.safedb.adapter.QueryTimedOutException
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal object AdapterIntegrationAssertions {
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

    fun assertExecutionTimeout(elapsedMs: Long, error: Throwable) {
        assertTrue(
            elapsedMs in 800..5_000,
            "expected the 1s query budget to stop execution within 1-5s; stopped after ${elapsedMs}ms",
        )
        val chain = generateSequence(error) { it.cause }.toList()
        val texts = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        val timedOut =
            chain.any { it is QueryTimedOutException || it is SQLTimeoutException } ||
                "timed out" in texts ||
                "timeout" in texts ||
                "cancel" in texts ||
                // SQL Server's budget is a socket read deadline; mssql-jdbc answers it by
                // closing the connection instead of reporting a timeout message.
                (error is SQLException && "connection is closed" in texts)
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
}
