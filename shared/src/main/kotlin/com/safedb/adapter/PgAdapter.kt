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

object PgAdapter {
    fun test(dataSource: HikariDataSource): String = probeVersion(dataSource, "SELECT version()")

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val excluded = "('pg_catalog', 'information_schema', 'pg_toast')"
            val tables =
                conn.metadataRows(
                    "SELECT table_schema, table_name FROM information_schema.tables " +
                        "WHERE table_type = 'BASE TABLE' AND table_schema NOT IN $excluded " +
                        "ORDER BY table_schema, table_name"
                ) { rs ->
                    MetadataTableKey(readString(rs, "table_schema"), readString(rs, "table_name"))
                }
            val columns =
                conn.metadataRows(
                    "SELECT table_schema, table_name, column_name, data_type, is_nullable " +
                        "FROM information_schema.columns WHERE table_schema NOT IN $excluded " +
                        "ORDER BY table_schema, table_name, ordinal_position"
                ) { rs ->
                    columnRow(rs)
                }
            val indexes =
                conn.metadataRows(
                    """
                SELECT n.nspname AS table_schema, t.relname AS table_name, i.relname AS index_name,
                       a.attname AS column_name, idx.indisunique, idx.indisprimary, am.amname AS index_type,
                       key.ordinality > idx.indnkeyatts AS is_included,
                       CASE WHEN key.ordinality <= idx.indnkeyatts
                            THEN (idx.indoption[(key.ordinality - 1)::int] & 1) = 1
                            ELSE NULL END AS is_descending,
                       idx.indpred IS NOT NULL AS is_partial
                FROM pg_index idx
                JOIN pg_class t ON t.oid = idx.indrelid
                JOIN pg_class i ON i.oid = idx.indexrelid
                JOIN pg_am am ON am.oid = i.relam
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN LATERAL unnest(idx.indkey) WITH ORDINALITY AS key(attnum, ordinality)
                  ON key.ordinality <= idx.indnatts
                LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = key.attnum
                WHERE n.nspname NOT IN $excluded
                ORDER BY n.nspname, t.relname, idx.indisprimary DESC, i.relname, key.ordinality
                """
                        .trimIndent()
                ) { rs ->
                    val kind = readString(rs, "index_type")
                    MetadataIndex(
                        MetadataTableKey(
                            readString(rs, "table_schema"),
                            readString(rs, "table_name"),
                        ),
                        readString(rs, "index_name"),
                        rs.getString("column_name"),
                        kind,
                        kind in setOf("btree", "hash"),
                        rs.getBoolean("indisunique"),
                        rs.getBoolean("indisprimary"),
                        direction =
                            if (rs.getBoolean("is_included")) {
                                null
                            } else if (rs.getBoolean("is_descending")) {
                                SortDirection.Desc
                            } else {
                                SortDirection.Asc
                            },
                        included = rs.getBoolean("is_included"),
                        capabilities =
                            IndexCapabilities(
                                equality = kind in setOf("btree", "hash"),
                                ordering = kind == "btree",
                                specializedText = null,
                                expressionKeys = true,
                                partialPredicate = true,
                                includedColumns = true,
                            ),
                        partial = rs.getBoolean("is_partial"),
                        expression = rs.getString("column_name") == null,
                    )
                }
            val foreignKeys =
                conn.metadataRows(
                    """
                SELECT child_ns.nspname AS table_schema, child.relname AS table_name,
                       con.conname AS constraint_name, child_attr.attname AS column_name,
                       parent_ns.nspname AS referenced_schema, parent.relname AS referenced_table,
                       parent_attr.attname AS referenced_column
                FROM pg_constraint con
                JOIN pg_class child ON child.oid = con.conrelid
                JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
                JOIN pg_class parent ON parent.oid = con.confrelid
                JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
                JOIN unnest(con.conkey) WITH ORDINALITY AS child_key(attnum, ordinality) ON true
                JOIN unnest(con.confkey) WITH ORDINALITY AS parent_key(attnum, ordinality)
                  ON parent_key.ordinality = child_key.ordinality
                JOIN pg_attribute child_attr ON child_attr.attrelid = child.oid AND child_attr.attnum = child_key.attnum
                JOIN pg_attribute parent_attr ON parent_attr.attrelid = parent.oid AND parent_attr.attnum = parent_key.attnum
                WHERE con.contype = 'f' AND child_ns.nspname NOT IN $excluded
                ORDER BY child_ns.nspname, child.relname, con.conname, child_key.ordinality
                """
                        .trimIndent()
                ) { rs ->
                    foreignKeyRow(rs)
                }
            val tableSizes =
                conn.tableSizes(
                    """
                    SELECT n.nspname AS table_schema, c.relname AS table_name, c.reltuples
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN $excluded
                    """
                        .trimIndent(),
                    rowEstimateLabel = "reltuples",
                    confidence = EvidenceConfidence.Low,
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
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.execute("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
                stmt.execute("SET LOCAL statement_timeout = $timeoutMs")
            }
            prepareStatement(conn, compiled, Dialect.Postgres).use { ps ->
                ps.executeQuery().use { rs ->
                    val result = decodeQueryResult(rs, compiled.sql, Dialect.Postgres)
                    conn.commit()
                    result
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
                    parsePostgresPlan(planJson)?.let(ExplainResult::Available)
                        ?: ExplainResult.Unavailable(
                            com.safedb.model.PlanUnavailableReason.ParseFailure,
                            "Could not normalize PostgreSQL JSON plan",
                        )
                }
            }
        }
    }
}
