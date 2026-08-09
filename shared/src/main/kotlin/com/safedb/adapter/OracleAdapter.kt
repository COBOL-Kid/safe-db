package com.safedb.adapter

import com.safedb.model.ColumnInfo
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.IndexCapabilities
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID

object OracleAdapter {
    private val blockedOwners =
        setOf(
            "SYS",
            "SYSTEM",
            "OUTLN",
            "DBSNMP",
            "APPQOSSYS",
            "DBSFWUSER",
            "ORACLE_OCM",
            "ANONYMOUS",
            "XS\$NULL",
            "GSMADMIN_INTERNAL",
            "AUDSYS",
            "DVSYS",
            "LBACSYS",
            "REMOTE_SCHEDULER_AGENT",
            "WMSYS",
            "XDB",
            "CTXSYS",
            "ORDSYS",
            "ORDPLUGINS",
            "SI_INFORMTN_SCHEMA",
            "MDSYS",
            "OLAPSYS",
            "MDDATA",
            "SPATIAL_WFS_ADMIN_USR",
            "SPATIAL_CSW_ADMIN_USR",
            "SYSMAN",
            "APEX_030200",
            "FLOWS_FILES",
            "APEX_PUBLIC_USER",
            "ORDDATA",
            "APEX_040000",
            "APEX_040200",
        )

    fun encodeConnectQueryValue(value: String): String =
        buildString(value.length) {
            for (byte in value.toByteArray()) {
                val ch = byte.toInt().toChar()
                if (ch.isLetterOrDigit() || ch in "-_./") append(ch)
                else append("%${"%02X".format(byte)}")
            }
        }

    fun validateConnectField(field: String, label: String): Result<Unit> {
        if (field.isEmpty())
            return Result.failure(IllegalArgumentException("$label must not be empty"))
        if (field.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) {
            return Result.failure(IllegalArgumentException("$label contains invalid characters"))
        }
        return Result.success(Unit)
    }

    fun test(dataSource: HikariDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt
                    .executeQuery("SELECT banner FROM v\$version WHERE banner LIKE 'Oracle%'")
                    .use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            }
        }

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            val blocked = blockedOwners.joinToString(",") { "'$it'" }
            val tables =
                conn.metadataRows(
                    "SELECT owner, table_name FROM all_tables WHERE owner NOT IN ($blocked) ORDER BY owner, table_name"
                ) { rs ->
                    MetadataTableKey(readString(rs, "owner"), readString(rs, "table_name"))
                }
            val columns =
                conn.metadataRows(
                    "SELECT owner, table_name, column_name, data_type, nullable FROM all_tab_columns " +
                        "WHERE owner NOT IN ($blocked) ORDER BY owner, table_name, column_id"
                ) { rs ->
                    MetadataColumn(
                        MetadataTableKey(readString(rs, "owner"), readString(rs, "table_name")),
                        ColumnInfo(
                            readString(rs, "column_name"),
                            readString(rs, "data_type"),
                            readString(rs, "nullable") == "Y",
                        ),
                    )
                }
            val indexes =
                conn.metadataRows(
                    """
                SELECT aic.table_owner, aic.table_name, aic.index_name, aic.column_name, aic.descend,
                       aie.column_expression,
                       CASE WHEN ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                       CASE WHEN aic.index_name LIKE 'SYS_%' AND ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_primary
                FROM all_ind_columns aic
                JOIN all_indexes ai ON aic.index_owner = ai.owner AND aic.index_name = ai.index_name
                LEFT JOIN all_ind_expressions aie
                  ON aie.index_owner = aic.index_owner
                 AND aie.index_name = aic.index_name
                 AND aie.table_owner = aic.table_owner
                 AND aie.table_name = aic.table_name
                 AND aie.column_position = aic.column_position
                WHERE aic.table_owner NOT IN ($blocked)
                ORDER BY aic.table_owner, aic.table_name, ai.uniqueness DESC, aic.index_name, aic.column_position
                """
                        .trimIndent()
                ) { rs ->
                    MetadataIndex(
                        MetadataTableKey(
                            readString(rs, "table_owner"),
                            readString(rs, "table_name"),
                        ),
                        readString(rs, "index_name"),
                        readString(rs, "column_name").takeIf {
                            rs.getString("column_expression") == null
                        },
                        "NORMAL",
                        true,
                        rs.getInt("is_unique") == 1,
                        rs.getInt("is_primary") == 1,
                        direction =
                            when (readString(rs, "descend")) {
                                "ASC" -> SortDirection.Asc
                                "DESC" -> SortDirection.Desc
                                else -> null
                            },
                        capabilities =
                            IndexCapabilities(
                                equality = true,
                                ordering = true,
                                specializedText = null,
                                expressionKeys = true,
                                partialPredicate = false,
                                includedColumns = false,
                            ),
                        partial = false,
                        expression = rs.getString("column_expression") != null,
                    )
                }
            val foreignKeys =
                conn.metadataRows(
                    """
                SELECT child.owner AS table_schema, child.table_name, child.constraint_name,
                       child_cols.column_name, parent.owner AS referenced_schema,
                       parent.table_name AS referenced_table, parent_cols.column_name AS referenced_column
                FROM all_constraints child
                JOIN all_cons_columns child_cols ON child.owner = child_cols.owner AND child.constraint_name = child_cols.constraint_name
                JOIN all_constraints parent ON child.r_owner = parent.owner AND child.r_constraint_name = parent.constraint_name
                JOIN all_cons_columns parent_cols ON parent.owner = parent_cols.owner
                  AND parent.constraint_name = parent_cols.constraint_name AND parent_cols.position = child_cols.position
                WHERE child.constraint_type = 'R' AND child.owner NOT IN ($blocked)
                ORDER BY child.owner, child.table_name, child.constraint_name, child_cols.position
                """
                        .trimIndent()
                ) { rs ->
                    MetadataForeignKey(
                        MetadataTableKey(
                            readString(rs, "table_schema"),
                            readString(rs, "table_name"),
                        ),
                        readString(rs, "constraint_name"),
                        readString(rs, "column_name"),
                        readString(rs, "referenced_schema"),
                        readString(rs, "referenced_table"),
                        readString(rs, "referenced_column"),
                    )
                }
            val tableSizes = runCatching {
                conn
                    .metadataRows(
                        "SELECT owner, table_name, num_rows FROM all_tables WHERE owner NOT IN ($blocked)"
                    ) { rs ->
                        MetadataTableKey(
                            readString(rs, "owner"),
                            readString(rs, "table_name"),
                        ) to
                            normalizeTableSize(
                                (rs.getObject("num_rows") as? Number)?.toDouble(),
                                EvidenceConfidence.Low,
                            )
                    }
                    .toMap()
            }
                .getOrDefault(emptyMap())
            return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
        }
    }

    fun executeQuery(
        dataSource: HikariDataSource,
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): QueryResult =
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            try {
                prepareStatement(conn, compiled, Dialect.Oracle).use { ps ->
                    ps.queryTimeout = timeoutMs / 1000
                    ps.executeQuery().use { rs ->
                        val result = decodeQueryResult(rs, compiled.sql, Dialect.Oracle)
                        conn.createStatement().use { it.execute("COMMIT") }
                        result
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
            prepareStatement(conn, compiled.copy(sql = explainSql), Dialect.Oracle).use {
                it.execute()
            }
            var cleanupFailure: Throwable? = null
            val planRows =
                try {
                    conn
                        .prepareStatement(
                            """
                            SELECT id, parent_id, operation, options, object_owner, object_name, object_alias, cardinality, cost
                                                    FROM plan_table WHERE statement_id = ? ORDER BY id
                            """
                                .trimIndent()
                        )
                        .use { ps ->
                            ps.setString(1, statementId)
                            ps.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(
                                            OraclePlanRow(
                                                id = rs.getInt(1),
                                                parentId =
                                                    rs.getObject(2)?.let { (it as Number).toInt() },
                                                operation = rs.getString(3).orEmpty(),
                                                options = rs.getString(4).orEmpty(),
                                                owner = rs.getString(5),
                                                objectName = rs.getString(6),
                                                alias =
                                                    rs.getString(7)
                                                        ?.substringBefore('@')
                                                        ?.trim('"'),
                                                rows =
                                                    rs.getObject(8)?.let {
                                                        (it as Number).toLong()
                                                    },
                                                cost =
                                                    rs.getObject(9)?.let {
                                                        (it as Number).toDouble()
                                                    },
                                            )
                                        )
                                    }
                                }
                            }
                        }
                } finally {
                    runCatching {
                        conn.prepareStatement("DELETE FROM plan_table WHERE statement_id = ?").use {
                            it.setString(1, statementId)
                            it.execute()
                        }
                    }
                        .onFailure { cleanupFailure = it }
                }
            if (cleanupFailure != null) {
                ExplainResult.Unavailable(
                    com.safedb.model.PlanUnavailableReason.CleanupFailure,
                    "Oracle PLAN_TABLE cleanup failed; plan evidence was discarded",
                )
            } else {
                val normalized = normalizeOraclePlan(planRows)
                if (normalized == null) {
                    ExplainResult.Unavailable(
                        com.safedb.model.PlanUnavailableReason.UnsupportedShape,
                        "Oracle PLAN_TABLE returned no plan rows",
                    )
                } else {
                    ExplainResult.Available(normalized)
                }
            }
        }
    }
}
