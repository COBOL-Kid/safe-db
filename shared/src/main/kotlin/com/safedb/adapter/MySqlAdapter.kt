package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.IndexCapabilities
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.zaxxer.hikari.HikariDataSource

internal fun mysqlIndexCapabilities(kind: String): IndexCapabilities =
    IndexCapabilities(
        equality = kind in setOf("BTREE", "HASH"),
        ordering = kind == "BTREE",
        // Builder text predicates compile to LIKE, not MATCH ... AGAINST, so FULLTEXT is
        // not compatible.
        specializedText = false,
        expressionKeys = true,
        partialPredicate = false,
        includedColumns = false,
    )

object MySqlAdapter {
    fun test(dataSource: HikariDataSource): String = probeVersion(dataSource, "SELECT VERSION()")

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val excluded = "('information_schema', 'mysql', 'performance_schema', 'sys')"
            val tables =
                conn.metadataRows(
                    "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES " +
                        "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA NOT IN $excluded " +
                        "ORDER BY TABLE_SCHEMA, TABLE_NAME"
                ) { rs ->
                    MetadataTableKey(readString(rs, "TABLE_SCHEMA"), readString(rs, "TABLE_NAME"))
                }
            val columns =
                conn.metadataRows(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE " +
                        "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA NOT IN $excluded " +
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
                    "SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, COLUMN_NAME, INDEX_TYPE, COLLATION, " +
                        "(NON_UNIQUE = 0) AS is_unique FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA NOT IN $excluded ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX"
                ) { rs ->
                    val kind = readString(rs, "INDEX_TYPE")
                    val name = readString(rs, "INDEX_NAME")
                    MetadataIndex(
                        MetadataTableKey(
                            readString(rs, "TABLE_SCHEMA"),
                            readString(rs, "TABLE_NAME"),
                        ),
                        name,
                        rs.getString("COLUMN_NAME"),
                        kind,
                        kind in setOf("BTREE", "HASH"),
                        rs.getBoolean("is_unique"),
                        name == "PRIMARY",
                        direction =
                            when (rs.getString("COLLATION")) {
                                "A" -> SortDirection.Asc
                                "D" -> SortDirection.Desc
                                else -> null
                            },
                        capabilities = mysqlIndexCapabilities(kind),
                        partial = false,
                        expression = rs.getString("COLUMN_NAME") == null,
                    )
                }
            val foreignKeys =
                conn.metadataRows(
                    "SELECT TABLE_SCHEMA AS table_schema, TABLE_NAME AS table_name, " +
                        "CONSTRAINT_NAME AS constraint_name, COLUMN_NAME AS column_name, " +
                        "REFERENCED_TABLE_SCHEMA AS referenced_schema, " +
                        "REFERENCED_TABLE_NAME AS referenced_table, " +
                        "REFERENCED_COLUMN_NAME AS referenced_column " +
                        "FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA NOT IN $excluded AND REFERENCED_TABLE_SCHEMA IS NOT NULL " +
                        "AND REFERENCED_TABLE_NAME IS NOT NULL AND REFERENCED_COLUMN_NAME IS NOT NULL " +
                        "ORDER BY TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION"
                ) { rs ->
                    foreignKeyRow(rs)
                }
            val tableSizes =
                conn.tableSizes(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES " +
                        "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA NOT IN $excluded",
                    rowEstimateLabel = "TABLE_ROWS",
                    confidence = EvidenceConfidence.Low,
                    schemaLabel = "TABLE_SCHEMA",
                    tableLabel = "TABLE_NAME",
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
            conn.createStatement().use { stmt ->
                stmt.execute("SET SESSION MAX_EXECUTION_TIME = $timeoutMs")
            }
            try {
                conn.autoCommit = false
                conn.createStatement().use {
                    it.execute("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
                }
                prepareStatement(conn, compiled, Dialect.MySql).use { ps ->
                    ps.executeQuery().use { rs ->
                        val result = decodeQueryResult(rs, compiled.sql, Dialect.MySql)
                        conn.commit()
                        result
                    }
                }
            } finally {
                runCatching {
                    conn.createStatement().use { it.execute("SET SESSION MAX_EXECUTION_TIME = 0") }
                }
            }
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult {
        val explainSql = "EXPLAIN FORMAT=JSON ${compiled.sql}"
        return dataSource.connection.use { conn ->
            prepareStatement(conn, compiled.copy(sql = explainSql), Dialect.MySql).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    val planJson = rs.getString(1)
                    parseMySqlPlan(planJson)?.let(ExplainResult::Available)
                        ?: ExplainResult.Unavailable(
                            com.safedb.model.PlanUnavailableReason.ParseFailure,
                            "Could not normalize MySQL JSON plan",
                        )
                }
            }
        }
    }
}
