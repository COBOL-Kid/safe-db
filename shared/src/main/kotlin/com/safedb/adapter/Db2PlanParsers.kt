package com.safedb.adapter

import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanBlockingOperation
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanRelationAccess

// One row per LUW EXPLAIN_OPERATOR, with its TABLE object preferred over INDEX objects
// and the operator's predicates concatenated for the equality heuristic below.
internal data class Db2LuwExplainRow(
    val operatorId: Int,
    val parentId: Int?,
    val operatorType: String,
    val objectSchema: String?,
    val objectName: String?,
    val objectType: String?,
    val predicateText: String = "",
    val estimatedRows: Long?,
    val totalCost: Double?,
)

private val db2LuwAccessOperators = setOf("TBSCAN", "IXSCAN", "RIDSCN", "FETCH")

// EXPLAIN_OBJECT.OBJECT_TYPE is CHAR(2). Index and table codes are not the words INDEX and TABLE.
private val db2IndexObjectTypes = setOf("IX", "RX", "XI", "PI", "LI", "LX", "LP")
private val db2TableObjectTypes = setOf("TA", "DP", "CO")
private val db2Inequality = Regex(""">=|<=|<>|!=|>|<""")

internal fun isDb2IndexObject(objectType: String?): Boolean =
    objectType?.trim()?.uppercase() in db2IndexObjectTypes

internal fun isDb2TableObject(objectType: String?): Boolean =
    objectType?.trim()?.uppercase() in db2TableObjectTypes

// A range operator wins over a residual or join '=' on the same predicate text.
private fun db2IndexAccess(predicateText: String): PlanAccessMethod {
    if (db2Inequality.containsMatchIn(predicateText)) return PlanAccessMethod.BoundedRange
    if (db2Inequality.replace(predicateText, " ").contains("=")) {
        return PlanAccessMethod.BoundedLookup
    }
    if (predicateText.isNotBlank()) return PlanAccessMethod.BoundedRange
    return PlanAccessMethod.FullIndexScan
}

internal fun parseDb2LuwExplain(
    rows: List<Db2LuwExplainRow>,
    statementCost: Double? = null,
): NormalizedQueryPlan? {
    if (rows.isEmpty()) return null
    val byId = rows.associateBy(Db2LuwExplainRow::operatorId)
    val children = rows.groupBy(Db2LuwExplainRow::parentId)
    // An IXSCAN names its index. List prefetch inserts SORT and RIDSCN between that scan
    // and the FETCH that names the table, so follow parents to the first table. Siblings
    // stay out of the walk, which keeps a nested-loop join from borrowing the other side.
    fun tableFor(row: Db2LuwExplainRow): Db2LuwExplainRow {
        if (!isDb2IndexObject(row.objectType)) return row
        val seen = mutableSetOf(row.operatorId)
        var current = row
        while (true) {
            val parentId = current.parentId ?: return row
            if (!seen.add(parentId)) return row
            val parent = byId[parentId] ?: return row
            if (isDb2TableObject(parent.objectType)) return parent
            current = parent
        }
    }
    fun subtreeTables(id: Int): Set<String> = buildSet {
        byId[id]?.let { row ->
            if (row.operatorType.uppercase() in db2LuwAccessOperators) {
                tableFor(row).objectName?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        children[id].orEmpty().forEach { addAll(subtreeTables(it.operatorId)) }
    }

    val indexFedIds =
        rows
            .filter {
                it.operatorType.equals("IXSCAN", true) || it.operatorType.equals("RIDSCN", true)
            }
            .mapNotNullTo(mutableSetOf(), Db2LuwExplainRow::parentId)
    val relations = rows.mapNotNull { row ->
        val table = tableFor(row)
        val tableName = table.objectName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val method =
            when (row.operatorType.uppercase()) {
                "TBSCAN" -> PlanAccessMethod.TableScan
                "IXSCAN",
                "RIDSCN" -> db2IndexAccess(row.predicateText)
                "FETCH" if row.operatorId !in indexFedIds -> PlanAccessMethod.Other
                else -> return@mapNotNull null
            }
        PlanRelationAccess(
            schema = table.objectSchema,
            table = tableName,
            alias = tableName,
            method = method,
            estimatedRows = row.estimatedRows,
        )
    }
    val joins =
        rows
            .filter { it.operatorType.uppercase().contains("JOIN") }
            .map { PlanJoinEvidence(subtreeTables(it.operatorId), it.estimatedRows) }
    val operations = rows.mapNotNull { row ->
        val kind =
            when {
                row.operatorType.equals("UNIQUE", true) -> PlanOperationKind.Distinct
                row.operatorType.uppercase().contains("SORT") -> PlanOperationKind.Sort
                row.operatorType.uppercase().contains("GRPBY") ||
                    row.operatorType.uppercase().contains("GROUP") ||
                    row.operatorType.uppercase().contains("AGGR") -> PlanOperationKind.Grouping
                else -> return@mapNotNull null
            }
        PlanBlockingOperation(kind, subtreeTables(row.operatorId), row.estimatedRows)
    }
    if (relations.isEmpty() && joins.isEmpty() && operations.isEmpty()) return null
    return NormalizedQueryPlan(
        relations = relations,
        joins = joins,
        blockingOperations = operations,
        rawOptimizerCost = statementCost ?: rows.firstOrNull { it.operatorId == 1 }?.totalCost,
    )
}

// One row per z/OS PLAN_TABLE entry for our QUERYNO.
internal data class Db2ZosPlanRow(
    val queryNo: Int,
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
    val sortUnique: Boolean = false,
    val sortJoin: Boolean = false,
    val sortOrderBy: Boolean = false,
    val sortGroupBy: Boolean = false,
)

internal fun parseDb2ZosPlan(
    rows: List<Db2ZosPlanRow>,
    statementCost: Double? = null,
): NormalizedQueryPlan? {
    if (rows.isEmpty()) return null
    val relations = rows.mapNotNull { row ->
        val table = row.tableName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val access = row.accessType.trim().uppercase()
        val method =
            when {
                access.isEmpty() || access.startsWith("R") || access.startsWith("V") ->
                    PlanAccessMethod.TableScan
                access.first() in setOf('I', 'N', 'M', 'D', 'P') ->
                    if (row.matchCols > 0) PlanAccessMethod.BoundedRange
                    else PlanAccessMethod.FullIndexScan
                else -> PlanAccessMethod.Other
            }
        PlanRelationAccess(
            schema = row.creator?.takeIf(String::isNotBlank),
            table = table,
            alias = table,
            method = method,
        )
    }
    // METHOD 1/2/3 mark nested-loop, merge-scan, and hybrid joins; METHOD 0 is the first
    // table of the block, so a block joins when it holds several tables with a join method.
    val joins =
        rows
            .groupBy(Db2ZosPlanRow::qblockNo)
            .filter { (_, block) -> block.size > 1 && block.any { it.method in 1..3 } }
            .map { (_, block) ->
                PlanJoinEvidence(
                    block.mapNotNullTo(mutableSetOf()) { it.tableName?.takeIf(String::isNotBlank) },
                    null,
                )
            }
    val operations = rows.flatMap { row ->
        val aliases = setOfNotNull(row.tableName?.takeIf(String::isNotBlank))
        buildList {
            if (row.sortUnique) {
                add(PlanBlockingOperation(PlanOperationKind.Distinct, aliases, null))
            }
            if (row.sortJoin || row.sortOrderBy) {
                add(PlanBlockingOperation(PlanOperationKind.Sort, aliases, null))
            }
            if (row.sortGroupBy) {
                add(PlanBlockingOperation(PlanOperationKind.Grouping, aliases, null))
            }
        }
    }
    if (relations.isEmpty() && joins.isEmpty() && operations.isEmpty()) return null
    return NormalizedQueryPlan(
        relations = relations,
        joins = joins,
        blockingOperations = operations,
        rawOptimizerCost = statementCost,
    )
}
