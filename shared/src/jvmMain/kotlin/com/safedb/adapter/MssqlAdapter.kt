package com.safedb.adapter

import com.safedb.model.ColumnInfo
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.IndexInfo
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.markIndexedColumns
import com.zaxxer.hikari.HikariDataSource

object MssqlAdapter {
    fun test(dataSource: HikariDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@VERSION AS version").use { rs ->
                    rs.next()
                    readString(rs, "version").ifBlank { "Unknown" }
                }
            }
        }

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val tables = mutableListOf<TableInfo>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT TABLE_SCHEMA, TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_TYPE = 'BASE TABLE'
                      AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'sys', 'guest')
                    ORDER BY TABLE_SCHEMA, TABLE_NAME
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val schema = readString(rs, "TABLE_SCHEMA")
                        val table = readString(rs, "TABLE_NAME")
                        var columns = introspectColumns(conn, schema, table)
                        val indexes = introspectIndexes(conn, schema, table)
                        markIndexedColumns(columns, indexes)
                        tables.add(TableInfo(schema, table, columns, indexes))
                    }
                }
            }
            return Schema(tables)
        }
    }

    private fun introspectColumns(conn: java.sql.Connection, schema: String, table: String): MutableList<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    columns.add(
                        ColumnInfo(
                            name = readString(rs, "COLUMN_NAME"),
                            dataType = readString(rs, "DATA_TYPE"),
                            nullable = readString(rs, "IS_NULLABLE") == "YES",
                        ),
                    )
                }
            }
        }
        return columns
    }

    private fun introspectIndexes(conn: java.sql.Connection, schema: String, table: String): List<IndexInfo> {
        val indexMap = linkedMapOf<String, IndexInfo>()
        conn.prepareStatement(
            """
            SELECT i.name AS index_name,
                   c.name AS column_name,
                   i.is_unique,
                   i.is_primary_key
            FROM sys.indexes i
            JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
            JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            JOIN sys.tables t ON i.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            WHERE s.name = ? AND t.name = ? AND ic.is_included_column = 0
            ORDER BY i.is_primary_key DESC, i.name, ic.key_ordinal
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = readString(rs, "index_name")
                    val columnName = readString(rs, "column_name")
                    val isUnique = rs.getBoolean("is_unique")
                    val isPrimary = rs.getBoolean("is_primary_key")
                    val entry = indexMap.getOrPut(indexName) {
                        IndexInfo(
                            name = indexName,
                            columns = emptyList(),
                            kind = "BTREE",
                            supportsEquality = true,
                            isUnique = isUnique,
                            isPrimary = isPrimary,
                        )
                    }
                    indexMap[indexName] = entry.copy(columns = entry.columns + columnName)
                }
            }
        }
        return indexMap.values.toList()
    }

    fun executeQuery(dataSource: HikariDataSource, compiled: CompiledQuery, timeoutMs: Int): QueryResult =
        dataSource.connection.use { conn ->
            var queryResult: QueryResult? = null
            withQueryTimeout(conn, timeoutMs) { timedConn ->
                timedConn.autoCommit = false
                timedConn.createStatement().use { stmt ->
                    stmt.execute("BEGIN TRANSACTION")
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED")
                    stmt.execute("SET LOCK_TIMEOUT $timeoutMs")
                }
                queryResult = try {
                    val result = prepareStatement(timedConn, compiled, Dialect.Mssql).use { ps ->
                        ps.executeQuery().use { rs ->
                            val meta = rs.metaData
                            val rows = mutableListOf<List<com.safedb.model.ResultCell>>()
                            val columns = if (!rs.next()) {
                                columnsFromCompiledSql(compiled.sql, Dialect.Mssql).map { ResultColumn(it, "unknown") }
                            } else {
                                val cols = (1..meta.columnCount).map { i ->
                                    ResultColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i))
                                }
                                do {
                                    val row = (1..meta.columnCount).map { i ->
                                        decodeJdbcValue(rs, i, meta.getColumnTypeName(i))
                                    }
                                    rows.add(row)
                                } while (rs.next())
                                cols
                            }
                            QueryResult.fromRows(columns, rows)
                        }
                    }
                    timedConn.createStatement().use { it.execute("COMMIT TRANSACTION") }
                    result
                } catch (e: Exception) {
                    runCatching { timedConn.createStatement().use { it.execute("ROLLBACK TRANSACTION") } }
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
            val xml = try {
                prepareStatement(conn, compiled, Dialect.Mssql).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildString {
                            while (rs.next()) {
                                append(readString(rs, 1))
                            }
                        }
                    }
                }
            } finally {
                repeat(3) {
                    if (runCatching { conn.createStatement().use { it.execute("SET SHOWPLAN_XML OFF") } }.isSuccess) {
                        return@repeat
                    }
                }
            }
            val cost = parseShowplanCost(xml)
            cost?.let { ExplainResult.Estimated(it) }
                ?: ExplainResult.Unavailable("Could not parse EXPLAIN cost from SHOWPLAN_XML")
        }
}
