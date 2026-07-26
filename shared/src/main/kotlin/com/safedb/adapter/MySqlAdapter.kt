package com.safedb.adapter

import com.safedb.model.ColumnInfo
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.ForeignKeyInfo
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
            val excluded = "('information_schema', 'mysql', 'performance_schema', 'sys')"
            val tables = conn.metadataRows(
                "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES " +
                    "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA NOT IN $excluded " +
                    "ORDER BY TABLE_SCHEMA, TABLE_NAME",
            ) { rs -> MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME")) }
            val columns = conn.metadataRows(
                "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA NOT IN $excluded " +
                    "ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION",
            ) { rs ->
                MetadataColumn(
                    MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME")),
                    ColumnInfo(readString(rs, "COLUMN_NAME"), readString(rs, "DATA_TYPE"), readString(rs, "IS_NULLABLE") == "YES"),
                )
            }
            val indexes = conn.metadataRows(
                "SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, COLUMN_NAME, INDEX_TYPE, " +
                    "(NON_UNIQUE = 0) AS is_unique FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA NOT IN $excluded ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX",
            ) { rs ->
                val kind = readString(rs, "INDEX_TYPE")
                val name = readString(rs, "INDEX_NAME")
                MetadataIndex(
                    MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME")),
                    name, readString(rs, "COLUMN_NAME"), kind, kind in setOf("BTREE", "HASH"),
                    rs.getBoolean("is_unique"), name == "PRIMARY",
                )
            }
            val foreignKeys = conn.metadataRows(
                "SELECT TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA, " +
                    "REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                    "WHERE TABLE_SCHEMA NOT IN $excluded AND REFERENCED_TABLE_SCHEMA IS NOT NULL " +
                    "AND REFERENCED_TABLE_NAME IS NOT NULL AND REFERENCED_COLUMN_NAME IS NOT NULL " +
                    "ORDER BY TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION",
            ) { rs ->
                MetadataForeignKey(
                    MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME")),
                    readString(rs, "CONSTRAINT_NAME"), readString(rs, "COLUMN_NAME"),
                    readString(rs, "REFERENCED_TABLE_SCHEMA"), readString(rs, "REFERENCED_TABLE_NAME"),
                    readString(rs, "REFERENCED_COLUMN_NAME"),
                )
            }
            return assembleSchema(tables, columns, indexes, foreignKeys)
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

    private fun introspectForeignKeys(conn: java.sql.Connection, schema: String, table: String): List<ForeignKeyInfo> {
        val foreignKeyMap = linkedMapOf<String, ForeignKeyInfo>()
        conn.prepareStatement(
            """
            SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA,
                   REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
              AND REFERENCED_TABLE_SCHEMA IS NOT NULL
              AND REFERENCED_TABLE_NAME IS NOT NULL
              AND REFERENCED_COLUMN_NAME IS NOT NULL
            ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = readString(rs, "CONSTRAINT_NAME")
                    val referencedSchema = readString(rs, "REFERENCED_TABLE_SCHEMA")
                    val referencedTable = readString(rs, "REFERENCED_TABLE_NAME")
                    val column = readString(rs, "COLUMN_NAME")
                    val referencedColumn = readString(rs, "REFERENCED_COLUMN_NAME")
                    val key = "$name|$referencedSchema|$referencedTable"
                    val entry = foreignKeyMap.getOrPut(key) {
                        ForeignKeyInfo(
                            name = name,
                            referencedSchema = referencedSchema,
                            referencedTable = referencedTable,
                        )
                    }
                    foreignKeyMap[key] = entry.copy(
                        columns = entry.columns + column,
                        referencedColumns = entry.referencedColumns + referencedColumn,
                    )
                }
            }
        }
        return foreignKeyMap.values.toList()
    }

    fun executeQuery(dataSource: HikariDataSource, compiled: CompiledQuery, timeoutMs: Int): QueryResult =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SET SESSION MAX_EXECUTION_TIME = $timeoutMs")
            }
            try {
                conn.autoCommit = false
                conn.createStatement().use { it.execute("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED") }
                prepareStatement(conn, compiled, Dialect.MySql).use { ps ->
                    ps.executeQuery().use { rs ->
                        val result = decodeQueryResult(rs, compiled.sql, Dialect.MySql)
                        conn.commit()
                        result
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
