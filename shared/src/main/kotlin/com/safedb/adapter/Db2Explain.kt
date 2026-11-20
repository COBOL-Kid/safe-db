package com.safedb.adapter

import com.safedb.model.CompiledQuery
import com.safedb.model.ExplainResult
import com.safedb.model.PlanUnavailableReason
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import kotlin.random.Random

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

internal class Db2TimedJdbc(
    private val conn: Connection,
    private val timeoutSeconds: Int,
) {
    fun prepare(sql: String): PreparedStatement =
        conn.prepareStatement(sql).also { it.queryTimeout = timeoutSeconds }

    fun statement(): Statement = conn.createStatement().also { it.queryTimeout = timeoutSeconds }
}

internal fun Db2Adapter.explainLuw(
    timed: Db2TimedJdbc,
    compiled: CompiledQuery,
): ExplainResult {
    // Resolve the EXPLAIN table schema before writing rows, matching explainZos.
    val prefix =
        when (val resolved = timed.resolveLuwExplainPrefix()) {
            LuwExplainPrefix.Unreadable -> return explainTablesUnreadable()
            LuwExplainPrefix.Missing -> return missingLuwExplainTables()
            is LuwExplainPrefix.Found -> resolved.prefix
        }
    val queryNo = Random.nextInt(100_000, Int.MAX_VALUE)
    try {
        timed.executeExplainPlan(compiled, queryNo)
    } catch (e: SQLException) {
        if (e.isUndefinedObject()) return missingLuwExplainTables()
        throw e
    }
    var cleanupFailure: Throwable? = null
    var statementCost: Double? = null
    val explainKey = runCatching {
        timed.queryLuwExplainKey(prefix, queryNo)
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
                statementCost = timed.queryLuwStatementCost(prefix, explainKey)
                timed.queryLuwExplainRows(prefix, explainKey)
            }
        } finally {
            // Mirror OracleAdapter: a failed cleanup discards the plan evidence.
            // Delete even when the key lookup failed, so an inserted QUERYNO does not remain.
            timed.deleteLuwExplain(prefix, queryNo, explainKey).onFailure {
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

internal fun Db2Adapter.explainZos(
    timed: Db2TimedJdbc,
    compiled: CompiledQuery,
): ExplainResult {
    val sqlId =
        try {
            timed.currentSqlId()
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
    when (timed.explainTablePresence("SELECT 1 FROM $planTable FETCH FIRST 1 ROW ONLY")) {
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
        timed.executeExplainPlan(compiled, queryNo)
    } catch (e: SQLException) {
        if (e.isUndefinedObject()) return missingZosPlanTable()
        throw e
    }
    return try {
        var cleanupFailure: Throwable? = null
        val explained =
            try {
                val statementCost = timed.queryZosStatementCost(statementTable, queryNo)
                statementCost to timed.queryZosPlanRows(planTable, queryNo)
            } finally {
                // Mirror OracleAdapter: a failed cleanup discards the plan evidence.
                runCatching {
                    timed.prepare("DELETE FROM $planTable WHERE QUERYNO = ?").use { ps ->
                        ps.setInt(1, queryNo)
                        ps.execute()
                    }
                }
                    .onFailure { if (!it.isUndefinedObject()) cleanupFailure = it }
                // DSN tables are optional, so their cleanup is best-effort.
                runCatching {
                    timed.prepare("DELETE FROM $statementTable WHERE QUERYNO = ?").use { ps ->
                        ps.setInt(1, queryNo)
                        ps.execute()
                    }
                }
            }
        val (statementCost, explainRows) = explained
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

// Operators, objects, and streams belong to the plan row (EXPLAIN_LEVEL 'P'), not the
// original-text row ('O') that shares the same EXPLAIN_TIME.
internal fun Db2Adapter.luwExplainKeySql(prefix: String): String =
    """
    SELECT EXPLAIN_REQUESTER, EXPLAIN_TIME, SOURCE_NAME, SOURCE_SCHEMA, SOURCE_VERSION,
           EXPLAIN_LEVEL, STMTNO, SECTNO
    FROM ${prefix}EXPLAIN_STATEMENT
    WHERE QUERYNO = ? AND EXPLAIN_LEVEL = 'P' AND EXPLAIN_REQUESTER = SESSION_USER
    ORDER BY EXPLAIN_TIME DESC
    FETCH FIRST 1 ROW ONLY
    """
        .trimIndent()

internal fun Db2Adapter.luwExplainDeleteSql(prefix: String, keyed: Boolean): String =
    if (keyed) {
        "DELETE FROM ${prefix}EXPLAIN_INSTANCE WHERE EXPLAIN_REQUESTER = ? AND " +
            "EXPLAIN_TIME = ? AND SOURCE_NAME = ? AND SOURCE_SCHEMA = ? AND SOURCE_VERSION = ?"
    } else {
        "DELETE FROM ${prefix}EXPLAIN_INSTANCE WHERE (EXPLAIN_REQUESTER, EXPLAIN_TIME, " +
            "SOURCE_NAME, SOURCE_SCHEMA, SOURCE_VERSION) IN (SELECT EXPLAIN_REQUESTER, " +
            "EXPLAIN_TIME, SOURCE_NAME, SOURCE_SCHEMA, SOURCE_VERSION FROM " +
            "${prefix}EXPLAIN_STATEMENT WHERE QUERYNO = ? AND EXPLAIN_REQUESTER = SESSION_USER)"
    }

internal fun Db2Adapter.selectLuwExplainPrefix(
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

// JCC 12 reports no JDBC parameters for EXPLAIN PLAN (getParameterCount is 0). Binding
// the statement's markers throws ERRORCODE=-4461. The markers stay in the text, and Db2
// still explains them.
private fun Db2TimedJdbc.executeExplainPlan(
    compiled: CompiledQuery,
    queryNo: Int,
) {
    prepare("EXPLAIN PLAN SET QUERYNO = $queryNo FOR ${compiled.sql}").use { ps -> ps.execute() }
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
private fun Db2TimedJdbc.resolveLuwExplainPrefix(): LuwExplainPrefix {
    val quotedAuthId =
        try {
            sessionAuthId()?.let(Db2Adapter::quoteDb2Ident)
        } catch (_: SQLException) {
            null
        }
    if (quotedAuthId == null) {
        return Db2Adapter.selectLuwExplainPrefix(
            null,
            ExplainTablePresence.Failed,
            null,
        )
    }
    val authTables =
        explainTablePresence(
            "SELECT 1 FROM $quotedAuthId.EXPLAIN_INSTANCE FETCH FIRST 1 ROW ONLY",
        )
    val systoolsTables =
        if (authTables == ExplainTablePresence.Absent) {
            explainTablePresence(
                "SELECT 1 FROM SYSTOOLS.EXPLAIN_INSTANCE FETCH FIRST 1 ROW ONLY",
            )
        } else {
            null
        }
    return Db2Adapter.selectLuwExplainPrefix(quotedAuthId, authTables, systoolsTables)
}

private fun Db2TimedJdbc.sessionAuthId(): String? =
    statement().use { stmt ->
        stmt.executeQuery("SELECT SESSION_USER FROM SYSIBM.SYSDUMMY1").use { rs ->
            if (rs.next()) rs.getString(1)?.trim()?.takeIf(String::isNotEmpty) else null
        }
    }

private fun Db2TimedJdbc.queryLuwExplainKey(
    prefix: String,
    queryNo: Int,
): LuwExplainKey? =
    prepare(Db2Adapter.luwExplainKeySql(prefix)).use { ps ->
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

// EXPLAIN_INSTANCE is keyed by requester, time, and source identity. QUERYNO and the
// section columns live on EXPLAIN_STATEMENT. Child explain tables reference that statement,
// which references the instance ON DELETE CASCADE in EXPLAIN.DDL, so one instance delete
// removes the plan. The unkeyed form is the fallback when the section key was not loaded.
private fun Db2TimedJdbc.deleteLuwExplain(
    prefix: String,
    queryNo: Int,
    key: LuwExplainKey?,
): Result<Unit> = runCatching {
    prepare(Db2Adapter.luwExplainDeleteSql(prefix, key != null)).use { ps ->
        if (key != null) {
            ps.setString(1, key.requester)
            ps.setTimestamp(2, key.explainTime)
            ps.setString(3, key.sourceName)
            ps.setString(4, key.sourceSchema)
            ps.setString(5, key.sourceVersion)
        } else {
            ps.setInt(1, queryNo)
        }
        ps.execute()
    }
}

private fun Db2TimedJdbc.explainTablePresence(sql: String): ExplainTablePresence =
    try {
        statement().use { stmt -> stmt.executeQuery(sql).use {} }
        ExplainTablePresence.Present
    } catch (e: SQLException) {
        if (e.isUndefinedObject()) ExplainTablePresence.Absent else ExplainTablePresence.Failed
    }

private fun Db2TimedJdbc.queryLuwStatementCost(
    prefix: String,
    key: LuwExplainKey,
): Double? =
// Best-effort: older explain table sets may lack EXPLAIN_STATEMENT entirely.
runCatching {
    prepare(
            "SELECT MAX(TOTAL_COST) FROM ${prefix}EXPLAIN_STATEMENT WHERE $LUW_KEY_PREDICATE",
        )
        .use { ps ->
            ps.bindLuwKey(key)
            ps.executeQuery().use { rs ->
                if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
            }
        }
}
    .getOrNull()

private fun Db2TimedJdbc.queryLuwExplainRows(
    prefix: String,
    key: LuwExplainKey,
): List<Db2LuwExplainRow> {
    val operators =
        prepare(
                "SELECT OPERATOR_ID, OPERATOR_TYPE, TOTAL_COST " +
                    "FROM ${prefix}EXPLAIN_OPERATOR WHERE $LUW_KEY_PREDICATE ORDER BY OPERATOR_ID",
            )
            .use { ps ->
                ps.bindLuwKey(key)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Triple(
                                    (rs.getObject(1) as Number).toInt(),
                                    rs.getString(2).orEmpty(),
                                    (rs.getObject(3) as? Number)?.toDouble(),
                                ),
                            )
                        }
                    }
                }
            }
    if (operators.isEmpty()) return emptyList()
    // EXPLAIN_OBJECT identifies the object, not the operator. EXPLAIN_STREAM carries the
    // operator id: a data object flows from SOURCE_TYPE 'D' (SOURCE_ID -1) to TARGET_ID.
    val objectTypes = mutableMapOf<Pair<String, String>, String>()
    prepare(
            "SELECT OBJECT_SCHEMA, OBJECT_NAME, OBJECT_TYPE " +
                "FROM ${prefix}EXPLAIN_OBJECT WHERE $LUW_KEY_PREDICATE",
        )
        .use { ps ->
            ps.bindLuwKey(key)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val objectKey =
                        rs.getString(1).orEmpty().trim() to rs.getString(2).orEmpty().trim()
                    objectTypes[objectKey] =
                        preferredDb2ObjectType(objectTypes[objectKey], rs.getString(3))
                }
            }
        }
    // Operator streams have no object. SOURCE_ID is the child and TARGET_ID the parent,
    // matching the last_explained reference view.
    val parents = mutableMapOf<Int, Int>()
    val producedRows = mutableMapOf<Int, Long>()
    val objectRefs = mutableListOf<LuwObjectRef>()
    prepare(
            "SELECT SOURCE_TYPE, SOURCE_ID, TARGET_TYPE, TARGET_ID, STREAM_COUNT, " +
                "OBJECT_SCHEMA, OBJECT_NAME FROM ${prefix}EXPLAIN_STREAM " +
                "WHERE $LUW_KEY_PREDICATE",
        )
        .use { ps ->
            ps.bindLuwKey(key)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val sourceType = rs.getString(1)?.trim()
                    val source = (rs.getObject(2) as Number).toInt()
                    val targetType = rs.getString(3)?.trim()
                    val target = (rs.getObject(4) as Number).toInt()
                    val objectName = rs.getString(7)?.trim()?.takeIf(String::isNotEmpty)
                    if (objectName == null) {
                        if (sourceType == "O" && targetType == "O") {
                            parents.putIfAbsent(source, target)
                        }
                    } else {
                        val operatorId =
                            when (sourceType) {
                                "D" if targetType == "O" -> target
                                "O" if targetType == "D" -> source
                                else -> null
                            }
                        if (operatorId != null) {
                            val objectSchema = rs.getString(6)?.trim()
                            objectRefs +=
                                LuwObjectRef(
                                    operatorId,
                                    objectSchema,
                                    objectName,
                                    objectTypes[objectSchema.orEmpty() to objectName],
                                )
                        }
                    }
                    val count = (rs.getObject(5) as? Number)?.toDouble()?.toLong()
                    if (sourceType == "O" && count != null) {
                        producedRows[source] = maxOf(producedRows[source] ?: 0, count)
                    }
                }
            }
        }
    val objects = objectRefs.groupBy(LuwObjectRef::operatorId)
    val predicates =
        prepare(
                "SELECT OPERATOR_ID, PREDICATE_TEXT " +
                    "FROM ${prefix}EXPLAIN_PREDICATE WHERE $LUW_KEY_PREDICATE",
            )
            .use { ps ->
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

private fun Db2TimedJdbc.currentSqlId(): String? =
    statement().use { stmt ->
        stmt.executeQuery("SELECT CURRENT SQLID FROM SYSIBM.SYSDUMMY1").use { rs ->
            if (rs.next()) rs.getString(1)?.trim()?.takeIf(String::isNotBlank) else null
        }
    }

private fun Db2TimedJdbc.queryZosPlanRows(
    planTable: String,
    queryNo: Int,
): List<Db2ZosPlanRow> {
    val rows =
        prepare(
                "SELECT QBLOCKNO, PLANNO, MIXOPSEQ, METHOD, CREATOR, TNAME, ACCESSTYPE, " +
                    "MATCHCOLS, ACCESSCREATOR, ACCESSNAME " +
                    "FROM $planTable WHERE QUERYNO = $queryNo ORDER BY QBLOCKNO, PLANNO, MIXOPSEQ",
            )
            .use { ps ->
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
                                ),
                            )
                        }
                    }
                }
            }
    if (rows.isEmpty()) return emptyList()
    // Sort flags vary across PLAN_TABLE formats, so they stay out of the core read.
    val sorts = runCatching {
        prepare(
                "SELECT QBLOCKNO, PLANNO, MIXOPSEQ, SORTC_UNIQ, SORTC_JOIN, " +
                    "SORTC_ORDERBY, SORTC_GROUPBY, SORTN_JOIN " +
                    "FROM $planTable WHERE QUERYNO = $queryNo",
            )
            .use { ps ->
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

private fun Db2TimedJdbc.queryZosStatementCost(
    statementTable: String,
    queryNo: Int,
): Double? =
// Best-effort: DSN tables are optional and may not exist for this creator.
runCatching {
    prepare("SELECT MAX(TOTAL_COST) FROM $statementTable WHERE QUERYNO = $queryNo").use { ps ->
        ps.executeQuery().use { rs ->
            if (rs.next()) (rs.getObject(1) as? Number)?.toDouble() else null
        }
    }
}
    .getOrNull()

private fun ResultSet.isYes(index: Int): Boolean =
    getString(index)?.trim().equals("Y", ignoreCase = true)

// One object name can be both a table and another code in the same plan. Keep the table.
private fun preferredDb2ObjectType(current: String?, candidate: String?): String {
    val next = candidate?.trim().orEmpty()
    if (current == null) return next
    if (isDb2TableObject(next) && !isDb2TableObject(current)) return next
    if (isDb2IndexObject(next) && !isDb2TableObject(current) && !isDb2IndexObject(current)) {
        return next
    }
    return current
}

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

private data class LuwExplainKey(
    val requester: String,
    val explainTime: Timestamp,
    val sourceName: String,
    val sourceSchema: String,
    val sourceVersion: String,
    val explainLevel: String,
    val stmtNo: Int,
    val sectNo: Int,
)

private fun PreparedStatement.bindLuwKey(key: LuwExplainKey) {
    setString(1, key.requester)
    setTimestamp(2, key.explainTime)
    setString(3, key.sourceName)
    setString(4, key.sourceSchema)
    setString(5, key.sourceVersion)
    setString(6, key.explainLevel)
    setInt(7, key.stmtNo)
    setInt(8, key.sectNo)
}

private const val LUW_KEY_PREDICATE =
    "EXPLAIN_REQUESTER = ? AND EXPLAIN_TIME = ? AND SOURCE_NAME = ? AND SOURCE_SCHEMA = ? " +
        "AND SOURCE_VERSION = ? AND EXPLAIN_LEVEL = ? AND STMTNO = ? AND SECTNO = ?"
