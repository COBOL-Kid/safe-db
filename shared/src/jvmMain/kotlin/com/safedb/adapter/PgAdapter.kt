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

object PgAdapter {
    fun test(dataSource: HikariDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT version()").use { rs ->
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
                    SELECT table_schema, table_name
                    FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE'
                      AND table_schema NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                    ORDER BY table_schema, table_name
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val schema = readString(rs, "table_schema")
                        val table = readString(rs, "table_name")
                        var columns = introspectColumns(conn, schema, table)
                        val indexes = introspectIndexes(conn, schema, table)
                        val foreignKeys = introspectForeignKeys(conn, schema, table)
                        markIndexedColumns(columns, indexes)
                        tables.add(TableInfo(schema, table, columns, indexes, foreignKeys))
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
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    columns.add(
                        ColumnInfo(
                            name = readString(rs, "column_name"),
                            dataType = readString(rs, "data_type"),
                            nullable = readString(rs, "is_nullable") == "YES",
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
            SELECT i.relname AS index_name,
                   a.attname AS column_name,
                   idx.indisunique,
                   idx.indisprimary,
                   am.amname AS index_type
            FROM pg_index idx
            JOIN pg_class t ON t.oid = idx.indrelid
            JOIN pg_class i ON i.oid = idx.indexrelid
            JOIN pg_am am ON am.oid = i.relam
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN LATERAL unnest(idx.indkey) WITH ORDINALITY AS key(attnum, ordinality)
              ON key.ordinality <= idx.indnkeyatts
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = key.attnum
            WHERE n.nspname = ? AND t.relname = ?
            ORDER BY idx.indisprimary DESC, i.relname, key.ordinality
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = readString(rs, "index_name")
                    val columnName = readString(rs, "column_name")
                    val isUnique = rs.getBoolean("indisunique")
                    val isPrimary = rs.getBoolean("indisprimary")
                    val indexType = readString(rs, "index_type")
                    val entry = indexMap.getOrPut(indexName) {
                        IndexInfo(
                            name = indexName,
                            columns = emptyList(),
                            kind = indexType,
                            supportsEquality = indexType in setOf("btree", "hash"),
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

    private fun introspectForeignKeys(conn: java.sql.Connection, schema: String, table: String): List<ForeignKeyInfo> {
        val foreignKeyMap = linkedMapOf<String, ForeignKeyInfo>()
        conn.prepareStatement(
            """
            SELECT con.conname AS constraint_name,
                   child_attr.attname AS column_name,
                   parent_ns.nspname AS referenced_schema,
                   parent.relname AS referenced_table,
                   parent_attr.attname AS referenced_column
            FROM pg_constraint con
            JOIN pg_class child ON child.oid = con.conrelid
            JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
            JOIN pg_class parent ON parent.oid = con.confrelid
            JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
            JOIN unnest(con.conkey) WITH ORDINALITY AS child_key(attnum, ordinality) ON true
            JOIN unnest(con.confkey) WITH ORDINALITY AS parent_key(attnum, ordinality)
              ON parent_key.ordinality = child_key.ordinality
            JOIN pg_attribute child_attr
              ON child_attr.attrelid = child.oid AND child_attr.attnum = child_key.attnum
            JOIN pg_attribute parent_attr
              ON parent_attr.attrelid = parent.oid AND parent_attr.attnum = parent_key.attnum
            WHERE con.contype = 'f'
              AND child_ns.nspname = ?
              AND child.relname = ?
            ORDER BY con.conname, child_key.ordinality
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = readString(rs, "constraint_name")
                    val referencedSchema = readString(rs, "referenced_schema")
                    val referencedTable = readString(rs, "referenced_table")
                    val column = readString(rs, "column_name")
                    val referencedColumn = readString(rs, "referenced_column")
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
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.execute("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
                stmt.execute("SET LOCAL statement_timeout = $timeoutMs")
            }
            val rows = mutableListOf<List<com.safedb.model.ResultCell>>()
            prepareStatement(conn, compiled, Dialect.Postgres).use { ps ->
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val columns = if (!rs.next()) {
                        columnsFromCompiledSql(compiled.sql, Dialect.Postgres).map {
                            ResultColumn(it, "unknown")
                        }
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
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult {
        val explainSql = "EXPLAIN (FORMAT JSON) ${compiled.sql}"
        return dataSource.connection.use { conn ->
            prepareStatement(conn, compiled.copy(sql = explainSql), Dialect.Postgres).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    val planJson = rs.getString(1)
                    val cost = parsePgExplainCost(planJson)
                    cost?.let { ExplainResult.Estimated(it) }
                        ?: ExplainResult.Unavailable("Could not parse EXPLAIN cost from PostgreSQL JSON plan")
                }
            }
        }
    }
}
