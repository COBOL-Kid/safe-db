package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.DB2_SYSTEM_SCHEMAS
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.IndexCapabilities
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import kotlin.random.Random

object Db2Adapter {
    // Same names as DB2_SYSTEM_SCHEMAS / BLOCKED_SCHEMAS. Unquoted SQL identifiers fold to
    // uppercase on both LUW and z/OS, so the filter compares UPPER(schema).
    private val excludedSchemas =
        DB2_SYSTEM_SCHEMAS.joinToString(prefix = "('", postfix = "')", separator = "','")

    fun test(dataSource: HikariDataSource): String =
        probeVersion(dataSource, "SELECT 1 FROM SYSIBM.SYSDUMMY1")

    fun introspect(dataSource: HikariDataSource): Schema {
        dataSource.connection.use { conn ->
            return if (conn.syscatAvailable()) introspectSyscat(conn) else introspectSysibm(conn)
        }
    }

    // SYSCAT exists on LUW. Only an undefined object (-204 / 42704) means z/OS. Authorization
    // errors and timeouts must surface instead of running the other catalog.
    private fun Connection.syscatAvailable(): Boolean {
        try {
            metadataRows("SELECT TABNAME FROM SYSCAT.TABLES FETCH FIRST 1 ROW ONLY") {
                readString(it, 1)
            }
            return true
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) return false
            throw e
        }
    }

    private fun introspectSyscat(conn: Connection): Schema {
        val tables =
            conn.metadataRows(
                "SELECT TABSCHEMA, TABNAME FROM SYSCAT.TABLES WHERE TYPE = 'T' " +
                    "AND UPPER(TABSCHEMA) NOT IN $excludedSchemas ORDER BY TABSCHEMA, TABNAME"
            ) { rs ->
                MetadataTableKey(readString(rs, "TABSCHEMA"), readString(rs, "TABNAME"))
            }
        val columns =
            conn.metadataRows(
                "SELECT TABSCHEMA, TABNAME, COLNAME, TYPENAME, NULLS FROM SYSCAT.COLUMNS " +
                    "WHERE UPPER(TABSCHEMA) NOT IN $excludedSchemas " +
                    "ORDER BY TABSCHEMA, TABNAME, COLNO"
            ) { rs ->
                columnRow(
                    rs,
                    schemaLabel = "TABSCHEMA",
                    tableLabel = "TABNAME",
                    columnLabel = "COLNAME",
                    typeLabel = "TYPENAME",
                    nullableLabel = "NULLS",
                    nullableValue = "Y",
                )
            }
        val indexes =
            conn.metadataRows(
                """
                SELECT I.TABSCHEMA, I.TABNAME, I.INDNAME, C.COLNAME, I.UNIQUERULE, C.COLORDER,
                       I.INDEXTYPE, C.VIRTUAL
                FROM SYSCAT.INDEXES I
                JOIN SYSCAT.INDEXCOLUSE C ON C.INDSCHEMA = I.INDSCHEMA AND C.INDNAME = I.INDNAME
                WHERE UPPER(I.TABSCHEMA) NOT IN $excludedSchemas
                ORDER BY I.TABSCHEMA, I.TABNAME, I.INDNAME, C.COLSEQ
                """
                    .trimIndent()
            ) { rs ->
                db2Index(
                    readString(rs, "TABSCHEMA"),
                    readString(rs, "TABNAME"),
                    readString(rs, "INDNAME"),
                    rs.getString("COLNAME"),
                    readString(rs, "UNIQUERULE"),
                    rs.getString("COLORDER"),
                    indexType = rs.getString("INDEXTYPE"),
                    virtual = rs.getString("VIRTUAL")?.trim().equals("Y", ignoreCase = true),
                )
            }
        val foreignKeys =
            completeDb2ForeignKeys(
                conn
                    .metadataRows(
                        """
                        SELECT R.TABSCHEMA AS table_schema, R.TABNAME AS table_name,
                               R.CONSTNAME AS constraint_name, CK.COLNAME AS column_name,
                               R.REFTABSCHEMA AS referenced_schema, R.REFTABNAME AS referenced_table,
                               PK.COLNAME AS referenced_column, R.COLCOUNT AS column_count
                        FROM SYSCAT.REFERENCES R
                        JOIN SYSCAT.KEYCOLUSE CK
                          ON CK.TABSCHEMA = R.TABSCHEMA AND CK.TABNAME = R.TABNAME
                         AND CK.CONSTNAME = R.CONSTNAME
                        JOIN SYSCAT.KEYCOLUSE PK
                          ON PK.TABSCHEMA = R.REFTABSCHEMA AND PK.TABNAME = R.REFTABNAME
                         AND PK.CONSTNAME = R.REFKEYNAME AND PK.COLSEQ = CK.COLSEQ
                        WHERE UPPER(R.TABSCHEMA) NOT IN $excludedSchemas
                        ORDER BY R.TABSCHEMA, R.TABNAME, R.CONSTNAME, CK.COLSEQ
                        """
                            .trimIndent()
                    ) { rs ->
                        countedForeignKey(rs)
                    }
                    .filterNotNull()
            )
        val tableSizes =
            conn.tableSizes(
                "SELECT TABSCHEMA, TABNAME, CARD FROM SYSCAT.TABLES " +
                    "WHERE UPPER(TABSCHEMA) NOT IN $excludedSchemas",
                rowEstimateLabel = "CARD",
                confidence = EvidenceConfidence.Low,
                schemaLabel = "TABSCHEMA",
                tableLabel = "TABNAME",
            )
        return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
    }

    private fun introspectSysibm(conn: Connection): Schema {
        val tables =
            conn.metadataRows(
                "SELECT CREATOR, NAME FROM SYSIBM.SYSTABLES WHERE TYPE = 'T' " +
                    "AND UPPER(CREATOR) NOT IN $excludedSchemas ORDER BY CREATOR, NAME"
            ) { rs ->
                MetadataTableKey(readString(rs, "CREATOR"), readString(rs, "NAME"))
            }
        val columns =
            conn.metadataRows(
                "SELECT TBCREATOR, TBNAME, NAME, COLTYPE, NULLS FROM SYSIBM.SYSCOLUMNS " +
                    "WHERE UPPER(TBCREATOR) NOT IN $excludedSchemas " +
                    "ORDER BY TBCREATOR, TBNAME, COLNO"
            ) { rs ->
                columnRow(
                    rs,
                    schemaLabel = "TBCREATOR",
                    tableLabel = "TBNAME",
                    columnLabel = "NAME",
                    typeLabel = "COLTYPE",
                    nullableLabel = "NULLS",
                    nullableValue = "Y",
                )
            }
        val indexes =
            conn.metadataRows(
                """
                SELECT I.TBCREATOR, I.TBNAME, I.NAME, K.COLNAME, I.UNIQUERULE, K.ORDERING
                FROM SYSIBM.SYSINDEXES I
                JOIN SYSIBM.SYSKEYS K ON K.IXCREATOR = I.CREATOR AND K.IXNAME = I.NAME
                WHERE UPPER(I.TBCREATOR) NOT IN $excludedSchemas
                ORDER BY I.TBCREATOR, I.TBNAME, I.NAME, K.COLSEQ
                """
                    .trimIndent()
            ) { rs ->
                db2Index(
                    readString(rs, "TBCREATOR"),
                    readString(rs, "TBNAME"),
                    readString(rs, "NAME"),
                    rs.getString("COLNAME"),
                    readString(rs, "UNIQUERULE"),
                    rs.getString("ORDERING"),
                    blankOrderMeansNotAKey = true,
                )
            }
        val foreignKeys =
            completeDb2ForeignKeys(
                conn
                    .metadataRows(
                        """
                        SELECT R.CREATOR AS table_schema, R.TBNAME AS table_name,
                               R.RELNAME AS constraint_name, F.COLNAME AS column_name,
                               R.REFTBCREATOR AS referenced_schema, R.REFTBNAME AS referenced_table,
                               CASE WHEN TRIM(R.IXNAME) = '' THEN PK.COLNAME ELSE UK.COLNAME END
                                   AS referenced_column,
                               R.COLCOUNT AS column_count
                        FROM SYSIBM.SYSRELS R
                        JOIN SYSIBM.SYSFOREIGNKEYS F
                          ON F.CREATOR = R.CREATOR AND F.TBNAME = R.TBNAME AND F.RELNAME = R.RELNAME
                        LEFT JOIN SYSIBM.SYSINDEXES PI
                          ON TRIM(R.IXNAME) = ''
                         AND PI.TBCREATOR = R.REFTBCREATOR AND PI.TBNAME = R.REFTBNAME
                         AND PI.UNIQUERULE = 'P'
                        LEFT JOIN SYSIBM.SYSKEYS PK
                          ON PK.IXCREATOR = PI.CREATOR AND PK.IXNAME = PI.NAME AND PK.COLSEQ = F.COLSEQ
                        LEFT JOIN SYSIBM.SYSKEYS UK
                          ON TRIM(R.IXNAME) <> ''
                         AND UK.IXCREATOR = R.IXOWNER AND UK.IXNAME = R.IXNAME
                         AND UK.COLSEQ = F.COLSEQ
                        WHERE UPPER(R.CREATOR) NOT IN $excludedSchemas
                        ORDER BY R.CREATOR, R.TBNAME, R.RELNAME, F.COLSEQ
                        """
                            .trimIndent()
                    ) { rs ->
                        countedForeignKey(rs)
                    }
                    .filterNotNull()
            )
        val tableSizes =
            conn.tableSizes(
                "SELECT CREATOR, NAME, CARDF FROM SYSIBM.SYSTABLES " +
                    "WHERE UPPER(CREATOR) NOT IN $excludedSchemas",
                rowEstimateLabel = "CARDF",
                confidence = EvidenceConfidence.Low,
                schemaLabel = "CREATOR",
                tableLabel = "NAME",
            )
        return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
    }

    internal fun db2Index(
        schema: String,
        table: String,
        name: String,
        column: String?,
        uniqueRule: String,
        ordering: String?,
        indexType: String? = null,
        virtual: Boolean = false,
        blankOrderMeansNotAKey: Boolean = false,
    ): MetadataIndex {
        val order = ordering?.trim()?.uppercase().orEmpty()
        val kind = indexType?.trim()?.uppercase().orEmpty().ifEmpty { "REG" }
        val included = order == "I" || (blankOrderMeansNotAKey && order.isEmpty())
        val text = kind in setOf("TEXT", "XPTH", "XRGN", "XVIL")
        val structured = kind == "REG" || kind == "CLUS"
        return MetadataIndex(
            MetadataTableKey(schema, table),
            name,
            column,
            kind,
            supportsEquality = !text,
            unique = uniqueRule == "U" || uniqueRule == "P",
            primary = uniqueRule == "P",
            direction =
                when (order) {
                    "A" -> SortDirection.Asc
                    "D" -> SortDirection.Desc
                    else -> null
                },
            included = included,
            capabilities =
                IndexCapabilities(
                    equality = !text,
                    ordering = structured && (order == "A" || order == "D"),
                    specializedText = text,
                    expressionKeys = virtual,
                    partialPredicate = false,
                    includedColumns = true,
                ),
            partial = false,
            expression = virtual || column == null,
        )
    }

    fun executeQuery(
        dataSource: HikariDataSource,
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): QueryResult =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.isReadOnly = true
            // Cursor stability locks the row under the cursor. Uncommitted read matches the
            // other dialects and does not block an updater of a row this query is fetching.
            conn.transactionIsolation = Connection.TRANSACTION_READ_UNCOMMITTED
            try {
                prepareStatement(conn, compiled, Dialect.Db2).use { ps ->
                    ps.queryTimeout = timeoutMs / 1000
                    ps.executeQuery().use { rs ->
                        val result = decodeQueryResult(rs, compiled.sql, Dialect.Db2)
                        conn.commit()
                        result
                    }
                }
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            }
        }

    fun explain(dataSource: HikariDataSource, compiled: CompiledQuery): ExplainResult =
        dataSource.connection.use { conn ->
            conn.isReadOnly = true
            // SYSCAT exists on LUW; z/OS exposes only SYSIBM, so the introspection probe
            // doubles as the EXPLAIN platform selector.
            if (conn.syscatAvailable()) explainLuw(conn, compiled) else explainZos(conn, compiled)
        }

    private fun explainLuw(conn: Connection, compiled: CompiledQuery): ExplainResult {
        val queryNo = Random.nextInt(100_000, Int.MAX_VALUE)
        try {
            prepareStatement(
                    conn,
                    compiled.copy(sql = "EXPLAIN PLAN SET QUERYNO = $queryNo FOR ${compiled.sql}"),
                    Dialect.Db2,
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.execute()
                }
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) return missingLuwExplainTables()
            throw e
        }
        val prefix =
            when (val resolved = conn.resolveLuwExplainPrefix()) {
                // EXPLAIN PLAN already wrote these rows, so a failed probe returns without deleting
                // them.
                LuwExplainPrefix.Unreadable -> return explainTablesUnreadable()
                LuwExplainPrefix.Missing -> return missingLuwExplainTables()
                is LuwExplainPrefix.Found -> resolved.prefix
            }
        var cleanupFailure: Throwable? = null
        var statementCost: Double? = null
        val explainKey = runCatching {
            conn.queryLuwExplainKey(prefix, queryNo)
        }
            .getOrElse {
                cleanupFailure = it
                null
            }
        val explainRows =
            try {
                if (explainKey == null) {
                    emptyList()
                } else {
                    statementCost = conn.queryLuwStatementCost(prefix, explainKey)
                    conn.queryLuwExplainRows(prefix, explainKey)
                }
            } finally {
                // Mirror OracleAdapter: a failed cleanup discards the plan evidence.
                // Delete even when the key lookup failed, so an inserted QUERYNO does not remain.
                conn.deleteLuwExplain(prefix, queryNo, explainKey).onFailure {
                    if (cleanupFailure == null && !it.isUndefinedObject()) cleanupFailure = it
                }
            }
        if (cleanupFailure != null) {
            return ExplainResult.Unavailable(
                PlanUnavailableReason.CleanupFailure,
                "Db2 EXPLAIN cleanup failed; plan evidence was discarded",
            )
        }
        if (explainKey == null || explainRows.isEmpty()) {
            return ExplainResult.Unavailable(
                PlanUnavailableReason.UnsupportedShape,
                "Db2 EXPLAIN produced no rows for QUERYNO $queryNo",
            )
        }
        return parseDb2LuwExplain(explainRows, statementCost)?.let(ExplainResult::Available)
            ?: ExplainResult.Unavailable(
                PlanUnavailableReason.ParseFailure,
                "Could not normalize Db2 LUW plan",
            )
    }

    private fun explainZos(conn: Connection, compiled: CompiledQuery): ExplainResult {
        val sqlId =
            try {
                conn.currentSqlId()
            } catch (_: SQLException) {
                null
            }
        if (sqlId == null) {
            return ExplainResult.Unavailable(
                PlanUnavailableReason.ExecutionFailure,
                "Db2 CURRENT SQLID could not be read",
            )
        }
        val qualifier =
            quoteDb2Ident(sqlId)
                ?: return ExplainResult.Unavailable(
                    PlanUnavailableReason.UnsupportedShape,
                    "Db2 PLAN_TABLE qualifier is not a usable identifier",
                )
        val planTable = "$qualifier.PLAN_TABLE"
        val statementTable = "$qualifier.DSN_STATEMNT_TABLE"
        when (conn.explainTablePresence("SELECT 1 FROM $planTable FETCH FIRST 1 ROW ONLY")) {
            ExplainTablePresence.Absent -> return missingZosPlanTable()
            ExplainTablePresence.Failed ->
                return ExplainResult.Unavailable(
                    PlanUnavailableReason.ExecutionFailure,
                    "Db2 PLAN_TABLE could not be read",
                )
            ExplainTablePresence.Present -> Unit
        }
        val queryNo = Random.nextInt(100_000, Int.MAX_VALUE)
        try {
            prepareStatement(
                    conn,
                    compiled.copy(sql = "EXPLAIN PLAN SET QUERYNO = $queryNo FOR ${compiled.sql}"),
                    Dialect.Db2,
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.execute()
                }
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) return missingZosPlanTable()
            throw e
        }
        return try {
            var cleanupFailure: Throwable? = null
            var statementCost: Double? = null
            val explainRows =
                try {
                    statementCost = conn.queryZosStatementCost(statementTable, queryNo)
                    conn.queryZosPlanRows(planTable, queryNo)
                } finally {
                    // Mirror OracleAdapter: a failed cleanup discards the plan evidence.
                    runCatching {
                        conn.prepareStatement("DELETE FROM $planTable WHERE QUERYNO = ?").use { ps
                            ->
                            ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                            ps.setInt(1, queryNo)
                            ps.execute()
                        }
                    }
                        .onFailure { if (!it.isUndefinedObject()) cleanupFailure = it }
                    // DSN tables are optional, so their cleanup is best-effort.
                    runCatching {
                        conn
                            .prepareStatement("DELETE FROM $statementTable WHERE QUERYNO = ?")
                            .use { ps ->
                                ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                                ps.setInt(1, queryNo)
                                ps.execute()
                            }
                    }
                }
            if (cleanupFailure != null) {
                ExplainResult.Unavailable(
                    PlanUnavailableReason.CleanupFailure,
                    "Db2 PLAN_TABLE cleanup failed; plan evidence was discarded",
                )
            } else if (explainRows.isEmpty()) {
                ExplainResult.Unavailable(
                    PlanUnavailableReason.UnsupportedShape,
                    "Db2 EXPLAIN returned no plan rows",
                )
            } else {
                parseDb2ZosPlan(explainRows, statementCost)?.let(ExplainResult::Available)
                    ?: ExplainResult.Unavailable(
                        PlanUnavailableReason.ParseFailure,
                        "Could not normalize Db2 z/OS plan",
                    )
            }
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) missingZosPlanTable() else throw e
        }
    }

    private fun missingLuwExplainTables(): ExplainResult.Unavailable =
        ExplainResult.Unavailable(
            PlanUnavailableReason.UnsupportedShape,
            "Db2 EXPLAIN tables are missing; create them with " +
                "CALL SYSPROC.SYSINSTALLOBJECTS('EXPLAIN', 'C', " +
                "CAST(NULL AS VARCHAR(128)), CAST(NULL AS VARCHAR(128)))",
        )

    private fun explainTablesUnreadable(): ExplainResult.Unavailable =
        ExplainResult.Unavailable(
            PlanUnavailableReason.ExecutionFailure,
            "Db2 EXPLAIN tables could not be read",
        )

    private fun missingZosPlanTable(): ExplainResult.Unavailable =
        ExplainResult.Unavailable(
            PlanUnavailableReason.UnsupportedShape,
            "Db2 PLAN_TABLE was not found; create the EXPLAIN tables by running job " +
                "DSNTIJXA (or ADMIN_EXPLAIN_MAINT) for your authorization ID",
        )

    // EXPLAIN writes under the session authorization ID, then SYSTOOLS. CURRENT SCHEMA does not
    // choose the tables, so currentSchema must not be the probe. SYSTOOLS is probed only after
    // the authorization-id tables are confirmed absent.
    private fun Connection.resolveLuwExplainPrefix(): LuwExplainPrefix {
        val quotedAuthId =
            try {
                sessionAuthId()?.let(::quoteDb2Ident)
            } catch (_: SQLException) {
                null
            }
        if (quotedAuthId == null) {
            return selectLuwExplainPrefix(null, ExplainTablePresence.Failed, null)
        }
        val authTables =
            explainTablePresence(
                "SELECT 1 FROM $quotedAuthId.EXPLAIN_INSTANCE FETCH FIRST 1 ROW ONLY"
            )
        val systoolsTables =
            if (authTables == ExplainTablePresence.Absent) {
                explainTablePresence(
                    "SELECT 1 FROM SYSTOOLS.EXPLAIN_INSTANCE FETCH FIRST 1 ROW ONLY"
                )
            } else {
                null
            }
        return selectLuwExplainPrefix(quotedAuthId, authTables, systoolsTables)
    }

    private fun Connection.sessionAuthId(): String? =
        createStatement().use { stmt ->
            stmt.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
            stmt.executeQuery("SELECT SESSION_USER FROM SYSIBM.SYSDUMMY1").use { rs ->
                if (rs.next()) rs.getString(1)?.trim()?.takeIf(String::isNotEmpty) else null
            }
        }

    private fun Connection.queryLuwExplainKey(prefix: String, queryNo: Int): LuwExplainKey? =
        prepareStatement(
                """
                SELECT EXPLAIN_REQUESTER, EXPLAIN_TIME, SOURCE_NAME, SOURCE_SCHEMA, SOURCE_VERSION,
                       EXPLAIN_LEVEL, STMTNO, SECTNO
                FROM ${prefix}EXPLAIN_INSTANCE
                WHERE QUERYNO = ? AND EXPLAIN_REQUESTER = SESSION_USER
                ORDER BY EXPLAIN_TIME DESC
                FETCH FIRST 1 ROW ONLY
                """
                    .trimIndent()
            )
            .use { ps ->
                ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                ps.setInt(1, queryNo)
                ps.executeQuery().use { rs ->
                    if (!rs.next() || rs.getTimestamp(2) == null) return null
                    LuwExplainKey(
                        requester = rs.getString(1),
                        explainTime = rs.getTimestamp(2),
                        sourceName = rs.getString(3).orEmpty(),
                        sourceSchema = rs.getString(4).orEmpty(),
                        sourceVersion = rs.getString(5).orEmpty(),
                        explainLevel = rs.getString(6).orEmpty(),
                        stmtNo = (rs.getObject(7) as Number).toInt(),
                        sectNo = (rs.getObject(8) as Number).toInt(),
                    )
                }
            }

    private fun Connection.deleteLuwExplain(
        prefix: String,
        queryNo: Int,
        key: LuwExplainKey?,
    ): Result<Unit> {
        var failure: Throwable? = null
        for (table in LUW_EXPLAIN_TABLES) {
            val sql =
                if (key != null) {
                    "DELETE FROM $prefix$table WHERE $LUW_KEY_PREDICATE"
                } else if (table == "EXPLAIN_INSTANCE" || table == "EXPLAIN_STATEMENT") {
                    "DELETE FROM $prefix$table WHERE QUERYNO = ? AND EXPLAIN_REQUESTER = SESSION_USER"
                } else {
                    "DELETE FROM $prefix$table WHERE EXPLAIN_REQUESTER = SESSION_USER AND " +
                        "(EXPLAIN_TIME, SOURCE_NAME, SOURCE_SCHEMA, SOURCE_VERSION, EXPLAIN_LEVEL, " +
                        "STMTNO, SECTNO) IN (SELECT EXPLAIN_TIME, SOURCE_NAME, SOURCE_SCHEMA, " +
                        "SOURCE_VERSION, EXPLAIN_LEVEL, STMTNO, SECTNO FROM ${prefix}EXPLAIN_INSTANCE " +
                        "WHERE QUERYNO = ? AND EXPLAIN_REQUESTER = SESSION_USER)"
                }
            runCatching {
                prepareStatement(sql).use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    if (key != null) ps.bindLuwKey(key) else ps.setInt(1, queryNo)
                    ps.execute()
                }
            }
                .onFailure { if (failure == null && !it.isUndefinedObject()) failure = it }
        }
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    private fun Connection.explainTablePresence(sql: String): ExplainTablePresence =
        try {
            createStatement().use { stmt ->
                stmt.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                stmt.executeQuery(sql).use {}
            }
            ExplainTablePresence.Present
        } catch (e: SQLException) {
            if (e.isUndefinedObject()) ExplainTablePresence.Absent else ExplainTablePresence.Failed
        }

    private fun Connection.queryLuwStatementCost(prefix: String, key: LuwExplainKey): Double? =
    // Best-effort: older explain table sets may lack EXPLAIN_STATEMENT entirely.
    runCatching {
        prepareStatement(
                "SELECT MAX(TOTAL_COST) FROM ${prefix}EXPLAIN_STATEMENT WHERE $LUW_KEY_PREDICATE"
            )
            .use { ps ->
                ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                ps.bindLuwKey(key)
                ps.executeQuery().use { rs ->
                    if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
                }
            }
    }
        .getOrNull()

    private fun Connection.queryLuwExplainRows(
        prefix: String,
        key: LuwExplainKey,
    ): List<Db2LuwExplainRow> {
        val operators =
            prepareStatement(
                    "SELECT OPERATOR_ID, OPERATOR_TYPE, TOTAL_COST " +
                        "FROM ${prefix}EXPLAIN_OPERATOR WHERE $LUW_KEY_PREDICATE ORDER BY OPERATOR_ID"
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.bindLuwKey(key)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Triple(
                                        (rs.getObject(1) as Number).toInt(),
                                        rs.getString(2).orEmpty(),
                                        (rs.getObject(3) as? Number)?.toDouble(),
                                    )
                                )
                            }
                        }
                    }
                }
        if (operators.isEmpty()) return emptyList()
        val objects =
            prepareStatement(
                    "SELECT OPERATOR_ID, OBJECT_SCHEMA, OBJECT_NAME, OBJECT_TYPE " +
                        "FROM ${prefix}EXPLAIN_OBJECT WHERE $LUW_KEY_PREDICATE"
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.bindLuwKey(key)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    LuwObjectRef(
                                        (rs.getObject(1) as Number).toInt(),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                    )
                                )
                            }
                        }
                    }
                }
                .groupBy(LuwObjectRef::operatorId)
        // Hierarchy edges are the streams without an object payload, mirroring the
        // last_explained reference view; SOURCE_ID is the child, TARGET_ID the parent.
        val parents = mutableMapOf<Int, Int>()
        val producedRows = mutableMapOf<Int, Long>()
        prepareStatement(
                "SELECT SOURCE_ID, TARGET_ID, STREAM_COUNT, OBJECT_NAME " +
                    "FROM ${prefix}EXPLAIN_STREAM WHERE $LUW_KEY_PREDICATE"
            )
            .use { ps ->
                ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                ps.bindLuwKey(key)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val source = (rs.getObject(1) as Number).toInt()
                        val target = (rs.getObject(2) as Number).toInt()
                        if (rs.getString(4) == null) parents.putIfAbsent(source, target)
                        val count = (rs.getObject(3) as? Number)?.toDouble()?.toLong()
                        if (count != null)
                            producedRows[source] = maxOf(producedRows[source] ?: 0, count)
                    }
                }
            }
        val predicates =
            prepareStatement(
                    "SELECT OPERATOR_ID, PREDICATE_TEXT " +
                        "FROM ${prefix}EXPLAIN_PREDICATE WHERE $LUW_KEY_PREDICATE"
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.bindLuwKey(key)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                rs.getString(2)?.let { text ->
                                    add((rs.getObject(1) as Number).toInt() to text)
                                }
                            }
                        }
                    }
                }
                .groupBy({ it.first }, { it.second })
        return operators.map { (id, type, cost) ->
            val refs = objects[id].orEmpty()
            val ref = refs.firstOrNull { isDb2TableObject(it.objectType) } ?: refs.firstOrNull()
            Db2LuwExplainRow(
                operatorId = id,
                parentId = parents[id],
                operatorType = type,
                objectSchema = ref?.objectSchema,
                objectName = ref?.objectName,
                objectType = ref?.objectType,
                predicateText = predicates[id].orEmpty().joinToString(" "),
                estimatedRows = producedRows[id],
                totalCost = cost,
            )
        }
    }

    private fun Connection.currentSqlId(): String? =
        createStatement().use { stmt ->
            stmt.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
            stmt.executeQuery("SELECT CURRENT SQLID FROM SYSIBM.SYSDUMMY1").use { rs ->
                if (rs.next()) rs.getString(1)?.trim()?.takeIf(String::isNotBlank) else null
            }
        }

    private fun Connection.queryZosPlanRows(
        planTable: String,
        queryNo: Int,
    ): List<Db2ZosPlanRow> {
        val rows =
            prepareStatement(
                    "SELECT QBLOCKNO, PLANNO, MIXOPSEQ, METHOD, CREATOR, TNAME, ACCESSTYPE, " +
                        "MATCHCOLS, ACCESSCREATOR, ACCESSNAME " +
                        "FROM $planTable WHERE QUERYNO = $queryNo ORDER BY QBLOCKNO, PLANNO, MIXOPSEQ"
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ZosPlanKey(
                                        (rs.getObject(1) as Number).toInt(),
                                        (rs.getObject(2) as Number).toInt(),
                                        (rs.getObject(3) as Number).toInt(),
                                        (rs.getObject(4) as Number).toInt(),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7).orEmpty(),
                                        (rs.getObject(8) as? Number)?.toInt() ?: 0,
                                        rs.getString(9),
                                        rs.getString(10),
                                    )
                                )
                            }
                        }
                    }
                }
        if (rows.isEmpty()) return emptyList()
        // Sort flags vary across PLAN_TABLE formats, so they stay out of the core read.
        val sorts = runCatching {
            prepareStatement(
                    "SELECT QBLOCKNO, PLANNO, MIXOPSEQ, SORTC_UNIQ, SORTC_JOIN, " +
                        "SORTC_ORDERBY, SORTC_GROUPBY, SORTN_JOIN " +
                        "FROM $planTable WHERE QUERYNO = $queryNo"
                )
                .use { ps ->
                    ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                    ps.executeQuery().use { rs ->
                        buildMap {
                            while (rs.next()) {
                                put(
                                    Triple(
                                        (rs.getObject(1) as Number).toInt(),
                                        (rs.getObject(2) as Number).toInt(),
                                        (rs.getObject(3) as Number).toInt(),
                                    ),
                                    booleanArrayOf(
                                        rs.isYes(4),
                                        rs.isYes(5) || rs.isYes(8),
                                        rs.isYes(6),
                                        rs.isYes(7),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
            .getOrDefault(emptyMap())
        return rows.map { key ->
            val flags = sorts[Triple(key.qblockNo, key.planNo, key.mixOpSeq)]
            Db2ZosPlanRow(
                queryNo = queryNo,
                qblockNo = key.qblockNo,
                planNo = key.planNo,
                mixOpSeq = key.mixOpSeq,
                method = key.method,
                creator = key.creator,
                tableName = key.tableName,
                accessType = key.accessType,
                matchCols = key.matchCols,
                accessCreator = key.accessCreator,
                accessName = key.accessName,
                sortUnique = flags?.get(0) == true,
                sortJoin = flags?.get(1) == true,
                sortOrderBy = flags?.get(2) == true,
                sortGroupBy = flags?.get(3) == true,
            )
        }
    }

    private fun Connection.queryZosStatementCost(
        statementTable: String,
        queryNo: Int,
    ): Double? =
    // Best-effort: DSN tables are optional and may not exist for this creator.
    runCatching {
        prepareStatement("SELECT MAX(TOTAL_COST) FROM $statementTable WHERE QUERYNO = $queryNo")
            .use { ps ->
                ps.queryTimeout = DEFAULT_TIMEOUT_MS / 1000
                ps.executeQuery().use { rs ->
                    if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
                }
            }
    }
        .getOrNull()

    internal enum class ExplainTablePresence {
        Present,
        Absent,
        Failed,
    }

    internal sealed interface LuwExplainPrefix {
        data class Found(val prefix: String) : LuwExplainPrefix

        data object Missing : LuwExplainPrefix

        data object Unreadable : LuwExplainPrefix
    }

    internal fun selectLuwExplainPrefix(
        quotedAuthId: String?,
        authTables: ExplainTablePresence,
        systoolsTables: ExplainTablePresence?,
    ): LuwExplainPrefix {
        if (quotedAuthId == null) return LuwExplainPrefix.Unreadable
        return when (authTables) {
            ExplainTablePresence.Present -> LuwExplainPrefix.Found("$quotedAuthId.")
            ExplainTablePresence.Failed -> LuwExplainPrefix.Unreadable
            ExplainTablePresence.Absent ->
                when (systoolsTables) {
                    ExplainTablePresence.Present -> LuwExplainPrefix.Found("SYSTOOLS.")
                    ExplainTablePresence.Absent -> LuwExplainPrefix.Missing
                    ExplainTablePresence.Failed,
                    null -> LuwExplainPrefix.Unreadable
                }
        }
    }

    internal data class CountedForeignKey(val key: MetadataForeignKey, val columnCount: Int)

    internal fun countedForeignKey(rs: java.sql.ResultSet): CountedForeignKey? {
        val columnCount = rs.getObject("column_count") as? Number ?: return null
        return CountedForeignKey(foreignKeyRow(rs), columnCount.toInt())
    }

    // A partial parent match must not become a shorter key.
    internal fun completeDb2ForeignKeys(rows: List<CountedForeignKey>): List<MetadataForeignKey> =
        rows
            .groupBy { row ->
                Triple(
                    row.key.table,
                    row.key.name,
                    row.key.referencedSchema to row.key.referencedTable,
                )
            }
            .values
            .flatMap { group ->
                val columnCount = group.map { it.columnCount }.distinct().singleOrNull()
                if (
                    columnCount == null ||
                        group.size != columnCount ||
                        group.any { it.key.referencedColumn.isBlank() }
                ) {
                    emptyList()
                } else {
                    group.map { it.key }
                }
            }

    // Delimited identifier. Control characters and over-long names are rejected so a
    // CURRENT SQLID or session authorization id cannot break out of the qualifier.
    internal fun quoteDb2Ident(name: String): String? {
        if (name.length > 128 || name.any { it.isISOControl() }) return null
        return "\"${name.replace("\"", "\"\"")}\""
    }

    internal fun Throwable.isUndefinedObject(): Boolean {
        val sqlException = this as? SQLException ?: return false
        return sqlException.sqlState == "42704" ||
            sqlException.errorCode == -204 ||
            sqlException.message?.contains("-204", ignoreCase = true) == true ||
            sqlException.message?.contains("undefined name", ignoreCase = true) == true
    }

    private fun java.sql.ResultSet.isYes(index: Int): Boolean =
        getString(index)?.trim().equals("Y", ignoreCase = true)

    private data class LuwObjectRef(
        val operatorId: Int,
        val objectSchema: String?,
        val objectName: String?,
        val objectType: String?,
    )

    private data class ZosPlanKey(
        val qblockNo: Int,
        val planNo: Int,
        val mixOpSeq: Int,
        val method: Int,
        val creator: String?,
        val tableName: String?,
        val accessType: String,
        val matchCols: Int,
        val accessCreator: String?,
        val accessName: String?,
    )

    // Child tables have no QUERYNO. When the instance key was not loaded, their delete
    // selects that key from EXPLAIN_INSTANCE, so INSTANCE has to be removed last.
    private val LUW_EXPLAIN_TABLES =
        listOf(
            "EXPLAIN_PREDICATE",
            "EXPLAIN_STREAM",
            "EXPLAIN_OBJECT",
            "EXPLAIN_OPERATOR",
            "EXPLAIN_ARGUMENT",
            "EXPLAIN_ACTUALS",
            "EXPLAIN_STATEMENT",
            "EXPLAIN_INSTANCE",
        )

    private data class LuwExplainKey(
        val requester: String,
        val explainTime: java.sql.Timestamp,
        val sourceName: String,
        val sourceSchema: String,
        val sourceVersion: String,
        val explainLevel: String,
        val stmtNo: Int,
        val sectNo: Int,
    )

    private fun java.sql.PreparedStatement.bindLuwKey(key: LuwExplainKey) {
        setString(1, key.requester)
        setTimestamp(2, key.explainTime)
        setString(3, key.sourceName)
        setString(4, key.sourceSchema)
        setString(5, key.sourceVersion)
        setString(6, key.explainLevel)
        setInt(7, key.stmtNo)
        setInt(8, key.sectNo)
    }
}

private const val LUW_KEY_PREDICATE =
    "EXPLAIN_REQUESTER = ? AND EXPLAIN_TIME = ? AND SOURCE_NAME = ? AND SOURCE_SCHEMA = ? " +
        "AND SOURCE_VERSION = ? AND EXPLAIN_LEVEL = ? AND STMTNO = ? AND SECTNO = ?"
