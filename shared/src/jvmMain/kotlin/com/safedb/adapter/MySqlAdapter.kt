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

object MySqlAdapter {
    fun test(dataSource: HikariDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT VERSION()").use { rs ->
                    rs.next()
                    rs.getString(1)
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
                    FROM information_schema.TABLES
                    WHERE TABLE_TYPE = 'BASE TABLE'
                      AND TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
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
            FROM information_schema.COLUMNS
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
            SELECT INDEX_NAME, COLUMN_NAME, INDEX_TYPE, (NON_UNIQUE = 0) AS is_unique
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = readString(rs, "INDEX_NAME")
                    val columnName = readString(rs, "COLUMN_NAME")
                    val isUnique = rs.getBoolean("is_unique")
                    val indexType = readString(rs, "INDEX_TYPE")
                    val entry = indexMap.getOrPut(indexName) {
                        IndexInfo(
                            name = indexName,
                            columns = emptyList(),
                            kind = indexType,
                            supportsEquality = indexType in setOf("BTREE", "HASH"),
                            isUnique = isUnique,
                            isPrimary = indexName == "PRIMARY",
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
            conn.createStatement().use { stmt ->
                stmt.execute("SET SESSION MAX_EXECUTION_TIME = $timeoutMs")
            }
            try {
                conn.autoCommit = false
                conn.createStatement().use { it.execute("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED") }
                val rows = mutableListOf<List<com.safedb.model.ResultCell>>()
                prepareStatement(conn, compiled, Dialect.MySql).use { ps ->
                    ps.executeQuery().use { rs ->
                        val meta = rs.metaData
                        val columns = if (!rs.next()) {
                            columnsFromCompiledSql(compiled.sql, Dialect.MySql).map { ResultColumn(it, "unknown") }
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
                        conn.commit()
                        QueryResult.fromRows(columns, rows)
                    }
                }
            } finally {
                runCatching { conn.createStatement().use { it.execute("SET SESSION MAX_EXECUTION_TIME = 0") } }
            }
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult {
        val explainSql = "EXPLAIN FORMAT=JSON ${compiled.sql}"
        return dataSource.connection.use { conn ->
            prepareStatement(conn, compiled.copy(sql = explainSql), Dialect.MySql).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    val planJson = rs.getString(1)
                    val cost = parseMysqlExplainCost(planJson)
                    cost?.let { ExplainResult.Estimated(it) }
                        ?: ExplainResult.Unavailable("Could not parse EXPLAIN cost from MySQL JSON plan")
                }
            }
        }
    }
}
