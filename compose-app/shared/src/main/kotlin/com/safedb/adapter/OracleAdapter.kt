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
import java.util.UUID

object OracleAdapter {
    private val blockedOwners = setOf(
        "SYS", "SYSTEM", "OUTLN", "DBSNMP", "APPQOSSYS", "DBSFWUSER", "ORACLE_OCM", "ANONYMOUS",
        "XS\$NULL", "GSMADMIN_INTERNAL", "AUDSYS", "DVSYS", "LBACSYS", "REMOTE_SCHEDULER_AGENT",
        "WMSYS", "XDB", "CTXSYS", "ORDSYS", "ORDPLUGINS", "SI_INFORMTN_SCHEMA", "MDSYS", "OLAPSYS",
        "MDDATA", "SPATIAL_WFS_ADMIN_USR", "SPATIAL_CSW_ADMIN_USR", "SYSMAN", "APEX_030200",
        "FLOWS_FILES", "APEX_PUBLIC_USER", "ORDDATA", "APEX_040000", "APEX_040200",
    )

    fun encodeConnectQueryValue(value: String): String = buildString(value.length) {
        for (byte in value.toByteArray()) {
            val ch = byte.toInt().toChar()
            if (ch.isLetterOrDigit() || ch in "-_./") append(ch) else append("%${"%02X".format(byte)}")
        }
    }

    fun validateConnectField(field: String, label: String): Result<Unit> {
        if (field.isEmpty()) return Result.failure(IllegalArgumentException("$label must not be empty"))
        if (field.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) {
            return Result.failure(IllegalArgumentException("$label contains invalid characters"))
        }
        return Result.success(Unit)
    }

    fun test(dataSource: HikariDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT banner FROM v\$version WHERE banner LIKE 'Oracle%'").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val tableData = mutableListOf<Pair<String, String>>()
            val blocked = blockedOwners.joinToString(",") { "'$it'" }
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT owner, table_name
                    FROM all_tables
                    WHERE owner NOT IN ($blocked)
                    ORDER BY owner, table_name
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        tableData.add(rs.getString(1) to rs.getString(2))
                    }
                }
            }
            val tables = tableData.map { (schema, table) ->
                var columns = introspectColumns(conn, schema, table)
                val indexes = introspectIndexes(conn, schema, table)
                markIndexedColumns(columns, indexes)
                TableInfo(schema, table, columns, indexes)
            }
            return Schema(tables)
        }
    }

    private fun introspectColumns(conn: java.sql.Connection, schema: String, table: String): MutableList<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """
            SELECT column_name, data_type, nullable
            FROM all_tab_columns
            WHERE owner = ? AND table_name = ?
            ORDER BY column_id
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
                            nullable = readString(rs, "nullable") == "Y",
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
            SELECT aic.index_name, aic.column_name,
                   CASE WHEN ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                   CASE WHEN aic.index_name LIKE 'SYS_%' AND ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_primary
            FROM all_ind_columns aic
            JOIN all_indexes ai ON aic.index_owner = ai.owner AND aic.index_name = ai.index_name
            WHERE aic.table_owner = ? AND aic.table_name = ?
            ORDER BY ai.uniqueness DESC, aic.index_name, aic.column_position
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = readString(rs, "index_name")
                    val columnName = readString(rs, "column_name")
                    val isUnique = rs.getInt("is_unique") == 1
                    val isPrimary = rs.getInt("is_primary") == 1
                    val entry = indexMap.getOrPut(indexName) {
                        IndexInfo(
                            name = indexName,
                            columns = emptyList(),
                            kind = "NORMAL",
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
            conn.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            try {
                val rows = mutableListOf<List<com.safedb.model.ResultCell>>()
                prepareStatement(conn, compiled, Dialect.Oracle).use { ps ->
                    ps.queryTimeout = timeoutMs / 1000
                    ps.executeQuery().use { rs ->
                        val meta = rs.metaData
                        val columns = if (!rs.next()) {
                            columnsFromCompiledSql(compiled.sql, Dialect.Oracle).map { ResultColumn(it, "unknown") }
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
                        conn.createStatement().use { it.execute("COMMIT") }
                        QueryResult.fromRows(columns, rows)
                    }
                }
            } catch (e: Exception) {
                runCatching { conn.createStatement().use { it.execute("ROLLBACK") } }
                throw e
            }
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult {
        val statementId = "safedb_${UUID.randomUUID().toString().replace("-", "")}"
        return dataSource.connection.use { conn ->
            val explainSql = "EXPLAIN PLAN SET STATEMENT_ID = '$statementId' FOR ${compiled.sql}"
            prepareStatement(conn, compiled.copy(sql = explainSql), Dialect.Oracle).use { it.execute() }
            conn.prepareStatement(
                "SELECT MAX(cost) FROM plan_table WHERE statement_id = ? AND id = 0",
            ).use { ps ->
                ps.setString(1, statementId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    val cost = rs.getObject(1)?.let { (it as Number).toDouble() }
                    runCatching {
                        conn.prepareStatement("DELETE FROM plan_table WHERE statement_id = ?").use {
                            it.setString(1, statementId)
                            it.execute()
                        }
                    }
                    cost?.let { ExplainResult.Estimated(it) }
                        ?: ExplainResult.Unavailable("Could not parse EXPLAIN cost from PLAN_TABLE")
                }
            }
        }
    }
}
