package com.safedb.adapter

import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.IndexCapabilities
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.zaxxer.hikari.HikariDataSource

object MssqlAdapter {
    fun test(dataSource: HikariDataSource): String =
        probeVersion(dataSource, "SELECT @@VERSION AS version").ifBlank { "Unknown" }

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val excluded = "('INFORMATION_SCHEMA', 'sys', 'guest')"
            val tables =
                conn.metadataRows(
                    "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA NOT IN $excluded " +
                        "ORDER BY TABLE_SCHEMA, TABLE_NAME"
                ) { rs ->
                    MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME"))
                }
            val columns =
                conn.metadataRows(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE " +
                        "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA NOT IN $excluded " +
                        "ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION"
                ) { rs ->
                    columnRow(
                        rs,
                        schemaLabel = "TABLE_SCHEMA",
                        tableLabel = "TABLE_NAME",
                        columnLabel = "COLUMN_NAME",
                        typeLabel = "DATA_TYPE",
                        nullableLabel = "IS_NULLABLE",
                    )
                }
            val indexes =
                conn.metadataRows(
                    """
                SELECT s.name AS table_schema, t.name AS table_name, i.name AS index_name,
                       c.name AS column_name, i.is_unique, i.is_primary_key, i.has_filter,
                       ic.is_descending_key, ic.is_included_column
                FROM sys.indexes i
                JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                JOIN sys.tables t ON i.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name NOT IN $excluded
                ORDER BY s.name, t.name, i.is_primary_key DESC, i.name,
                         ic.is_included_column, ic.key_ordinal, ic.index_column_id
                """
                        .trimIndent()
                ) { rs ->
                    MetadataIndex(
                        MetadataTableKey(
                            readString(rs, "table_schema"),
                            readString(rs, "table_name"),
                        ),
                        readString(rs, "index_name"),
                        readString(rs, "column_name"),
                        "BTREE",
                        true,
                        rs.getBoolean("is_unique"),
                        rs.getBoolean("is_primary_key"),
                        direction =
                            if (rs.getBoolean("is_descending_key")) SortDirection.Desc
                            else SortDirection.Asc,
                        included = rs.getBoolean("is_included_column"),
                        capabilities =
                            IndexCapabilities(
                                equality = true,
                                ordering = true,
                                specializedText = false,
                                expressionKeys = false,
                                partialPredicate = true,
                                includedColumns = true,
                            ),
                        partial = rs.getBoolean("has_filter"),
                    )
                }
            val foreignKeys =
                conn.metadataRows(
                    """
                SELECT child_schema.name AS table_schema, child_table.name AS table_name,
                       fk.name AS constraint_name, child_col.name AS column_name,
                       parent_schema.name AS referenced_schema, parent_table.name AS referenced_table,
                       parent_col.name AS referenced_column
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
                JOIN sys.tables child_table ON fk.parent_object_id = child_table.object_id
                JOIN sys.schemas child_schema ON child_table.schema_id = child_schema.schema_id
                JOIN sys.columns child_col ON child_col.object_id = child_table.object_id AND child_col.column_id = fkc.parent_column_id
                JOIN sys.tables parent_table ON fk.referenced_object_id = parent_table.object_id
                JOIN sys.schemas parent_schema ON parent_table.schema_id = parent_schema.schema_id
                JOIN sys.columns parent_col ON parent_col.object_id = parent_table.object_id AND parent_col.column_id = fkc.referenced_column_id
                WHERE child_schema.name NOT IN $excluded
                ORDER BY child_schema.name, child_table.name, fk.name, fkc.constraint_column_id
                """
                        .trimIndent()
                ) { rs ->
                    foreignKeyRow(rs)
                }
            val tableSizes =
                conn.tableSizes(
                    """
                    SELECT s.name AS table_schema, t.name AS table_name, SUM(p.row_count) AS row_count
                    FROM sys.tables t
                    JOIN sys.schemas s ON t.schema_id = s.schema_id
                    JOIN sys.dm_db_partition_stats p ON p.object_id = t.object_id AND p.index_id IN (0, 1)
                    WHERE s.name NOT IN $excluded
                    GROUP BY s.name, t.name
                    """
                        .trimIndent(),
                    rowEstimateLabel = "row_count",
                    confidence = EvidenceConfidence.Medium,
                )
            return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
        }
    }

    fun executeQuery(
        dataSource: HikariDataSource,
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): QueryResult =
        dataSource.connection.use { conn ->
            var queryResult: QueryResult? = null
            withQueryTimeout(conn, timeoutMs) { timedConn ->
                timedConn.autoCommit = false
                timedConn.createStatement().use { stmt ->
                    stmt.execute("BEGIN TRANSACTION")
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED")
                    stmt.execute("SET LOCK_TIMEOUT $timeoutMs")
                }
                queryResult =
                    try {
                        val result =
                            prepareStatement(timedConn, compiled, Dialect.Mssql).use { ps ->
                                ps.executeQuery().use { rs ->
                                    decodeQueryResult(rs, compiled.sql, Dialect.Mssql)
                                }
                            }
                        timedConn.createStatement().use { it.execute("COMMIT TRANSACTION") }
                        result
                    } catch (e: Exception) {
                        runCatching {
                            timedConn.createStatement().use { it.execute("ROLLBACK TRANSACTION") }
                        }
                        if (e.message?.contains("timed out", ignoreCase = true) == true) {
                            throw QueryTimedOutException(timeoutMs)
                        }
                        throw e
                    }
            }
            queryResult ?: error("query did not produce a result")
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult =
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("SET SHOWPLAN_XML ON") }
            var showplanRestored = false
            val xml =
                try {
                    conn.createStatement().use { stmt ->
                        buildString {
                            var hasResult = stmt.execute(mssqlShowplanSql(compiled))
                            while (hasResult || stmt.updateCount != -1) {
                                if (hasResult) {
                                    stmt.resultSet.use { rs ->
                                        while (rs.next()) {
                                            append(readString(rs, 1))
                                        }
                                    }
                                }
                                hasResult = stmt.moreResults
                            }
                        }
                    }
                } finally {
                    repeat(3) {
                        if (!showplanRestored) {
                            showplanRestored =
                                runCatching {
                                    conn.createStatement().use {
                                        it.execute("SET SHOWPLAN_XML OFF")
                                    }
                                }
                                    .isSuccess
                        }
                    }
                }
            if (!showplanRestored) {
                return@use ExplainResult.Unavailable(
                    com.safedb.model.PlanUnavailableReason.CleanupFailure,
                    "SQL Server plan session could not restore SHOWPLAN_XML OFF; the dedicated connection was discarded",
                )
            }
            parseSqlServerPlan(xml)?.let(ExplainResult::Available)
                ?: ExplainResult.Unavailable(
                    com.safedb.model.PlanUnavailableReason.ParseFailure,
                    "Could not normalize SQL Server SHOWPLAN XML",
                )
        }
}

internal fun mssqlShowplanSql(compiled: CompiledQuery): String = buildString {
    // The JDBC prepared-statement RPC does not surface SHOWPLAN's result set. Keep values typed
    // and escaped in a direct batch so text parameters cannot become executable SQL.
    compiled.params.forEachIndexed { index, value ->
        append("DECLARE @P${index + 1} ")
        append(mssqlDeclaration(value))
        append(";\n")
    }
    append(compiled.sql)
}

private fun mssqlDeclaration(value: BindValue): String =
    when (value) {
        is BindValue.Text -> "nvarchar(max) = N'${value.value.replace("'", "''")}'"
        is BindValue.Int -> "bigint = ${value.value}"
        is BindValue.Decimal -> {
            val integerDigits = (value.value.precision() - value.value.scale()).coerceAtLeast(0)
            val scale = value.value.scale().coerceIn(0, (38 - integerDigits).coerceAtLeast(0))
            "decimal(38,$scale) = ${value.value.toPlainString()}"
        }
        is BindValue.Float -> "float = ${value.value}"
        is BindValue.Bool -> "bit = ${if (value.value) 1 else 0}"
        is BindValue.Date -> "date = '${value.value}'"
        is BindValue.DateTime -> "datetime2 = '${value.value}'"
        BindValue.Null -> "nvarchar(max) = NULL"
    }
