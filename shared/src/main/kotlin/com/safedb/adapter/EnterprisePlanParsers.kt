package com.safedb.adapter

import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanBlockingOperation
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanRelationAccess
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal fun parseSqlServerPlan(raw: String): NormalizedQueryPlan? = runCatching {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false,
            )
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
    val relationNodes = document.getElementsByTagNameNS("*", "RelOp")
    val relations = mutableListOf<PlanRelationAccess>()
    val joins = mutableListOf<PlanJoinEvidence>()
    val operations = mutableListOf<PlanBlockingOperation>()
    for (index in 0 until relationNodes.length) {
        val relOp = relationNodes.item(index) as? Element ?: continue
        val physical = relOp.getAttribute("PhysicalOp")
        val logical = relOp.getAttribute("LogicalOp")
        // EstimateRows is per execution; rebinds/rewinds count the extra inner-loop executions.
        val rows =
            relOp.getAttribute("EstimateRows").toDoubleOrNull()?.let { estimate ->
                val rebinds = relOp.getAttribute("EstimateRebinds").toDoubleOrNull() ?: 0.0
                val rewinds = relOp.getAttribute("EstimateRewinds").toDoubleOrNull() ?: 0.0
                (estimate * (1.0 + rebinds + rewinds)).toLong()
            }
        val objects = relOp.getElementsByTagNameNS("*", "Object")
        val aliases = linkedSetOf<String>()
        for (objectIndex in 0 until objects.length) {
            val objectNode = objects.item(objectIndex) as? Element ?: continue
            val alias =
                objectNode.getAttribute("Alias").unquoteSqlServer().ifBlank {
                    objectNode.getAttribute("Table").unquoteSqlServer()
                }
            if (alias.isNotBlank()) aliases += alias
        }
        val firstObject = objects.item(0) as? Element
        if (
            firstObject != null &&
                physical in
                    setOf(
                        "Index Seek",
                        "Index Scan",
                        "Table Scan",
                        "Clustered Index Scan",
                        "Clustered Index Seek",
                    )
        ) {
            val method =
                when (physical) {
                    "Index Seek",
                    "Clustered Index Seek" -> sqlServerSeekMethod(relOp)
                    "Index Scan",
                    "Clustered Index Scan" -> PlanAccessMethod.FullIndexScan
                    "Table Scan" -> PlanAccessMethod.TableScan
                    else -> PlanAccessMethod.Other
                }
            relations +=
                PlanRelationAccess(
                    schema = firstObject.getAttribute("Schema").unquoteSqlServer().ifBlank { null },
                    table = firstObject.getAttribute("Table").unquoteSqlServer().ifBlank { null },
                    alias = firstObject.getAttribute("Alias").unquoteSqlServer().ifBlank { null },
                    method = method,
                    estimatedRows = rows,
                )
        }
        if (
            logical.contains("Join") ||
                physical.contains("Join") ||
                physical in setOf("Nested Loops", "Merge Join")
        ) {
            joins += PlanJoinEvidence(aliases, rows)
        }
        val kind =
            when {
                physical == "Sort" -> PlanOperationKind.Sort
                physical.contains("Aggregate") || logical.contains("Aggregate") ->
                    PlanOperationKind.Grouping
                physical == "Distinct Sort" -> PlanOperationKind.Distinct
                else -> null
            }
        if (kind != null) operations += PlanBlockingOperation(kind, aliases, rows)
    }
    val statements = document.getElementsByTagNameNS("*", "StmtSimple")
    // Bound parameters are sent as DECLARE batches, whose StmtSimple is an ASSIGN; skip it.
    var cost: Double? = null
    for (index in 0 until statements.length) {
        val statement = statements.item(index) as? Element ?: continue
        if (statement.getAttribute("StatementType").equals("ASSIGN", ignoreCase = true)) continue
        cost = statement.getAttribute("StatementSubTreeCost").toDoubleOrNull() ?: continue
        break
    }
    if (relations.isEmpty() && joins.isEmpty() && operations.isEmpty()) return null
    NormalizedQueryPlan(relations.distinct(), joins.distinct(), operations.distinct(), cost)
}
    .getOrNull()

private fun String.unquoteSqlServer(): String = removePrefix("[").removeSuffix("]")

private fun sqlServerSeekMethod(relOp: Element): PlanAccessMethod {
    val prefixes = relOp.getElementsByTagNameNS("*", "Prefix")
    if (prefixes.length == 0) return PlanAccessMethod.BoundedRange
    for (index in 0 until prefixes.length) {
        val prefix = prefixes.item(index) as? Element ?: continue
        if (!prefix.getAttribute("ScanType").equals("EQ", ignoreCase = true)) {
            return PlanAccessMethod.BoundedRange
        }
    }
    return PlanAccessMethod.BoundedLookup
}

internal data class OraclePlanRow(
    val id: Int,
    val parentId: Int?,
    val operation: String,
    val options: String,
    val owner: String?,
    val objectName: String?,
    val alias: String?,
    val rows: Long?,
    val cost: Double?,
)

internal fun normalizeOraclePlan(rows: List<OraclePlanRow>): NormalizedQueryPlan? {
    if (rows.isEmpty()) return null
    val byId = rows.associateBy(OraclePlanRow::id)
    val children = rows.groupBy(OraclePlanRow::parentId)
    fun subtreeAliases(id: Int): Set<String> = buildSet {
        byId[id]?.alias?.let(::add)
        children[id].orEmpty().forEach { addAll(subtreeAliases(it.id)) }
    }
    fun tableAncestor(row: OraclePlanRow): OraclePlanRow? {
        var current = row.parentId?.let(byId::get)
        while (current != null) {
            if (current.operation.equals("TABLE ACCESS", ignoreCase = true)) return current
            current = current.parentId?.let(byId::get)
        }
        return null
    }

    val indexParentIds =
        rows
            .filter { it.operation.equals("INDEX", ignoreCase = true) }
            .mapNotNullTo(mutableSetOf(), OraclePlanRow::parentId)
    val relations = rows.mapNotNull { row ->
        val operation = row.operation.uppercase()
        val options = row.options.uppercase()
        val tableRow = if (operation == "INDEX") tableAncestor(row) ?: row else row
        val method =
            when (operation) {
                "INDEX" ->
                    when {
                        options.contains("UNIQUE SCAN") -> PlanAccessMethod.BoundedLookup
                        options.contains("RANGE SCAN") -> PlanAccessMethod.BoundedRange
                        options.contains("FULL") -> PlanAccessMethod.FullIndexScan
                        else -> return@mapNotNull null
                    }
                "TABLE ACCESS" if options.contains("FULL") -> PlanAccessMethod.TableScan
                "TABLE ACCESS" if row.id !in indexParentIds -> PlanAccessMethod.Other
                else -> return@mapNotNull null
            }
        PlanRelationAccess(
            schema = tableRow.owner,
            table = tableRow.objectName,
            alias = tableRow.alias,
            method = method,
            estimatedRows = row.rows ?: tableRow.rows,
        )
    }
    val joins =
        rows
            .filter {
                it.operation.uppercase().contains("JOIN") ||
                    it.operation.equals("NESTED LOOPS", true)
            }
            .map { PlanJoinEvidence(subtreeAliases(it.id), it.rows) }
    val operations = rows.mapNotNull { row ->
        val kind =
            when {
                row.operation.uppercase().contains("SORT") &&
                    row.options.uppercase().contains("UNIQUE") -> PlanOperationKind.Distinct
                row.operation.uppercase().contains("SORT") -> PlanOperationKind.Sort
                row.operation.uppercase().contains("GROUP") ||
                    row.operation.uppercase().contains("AGGREGATE") -> PlanOperationKind.Grouping
                else -> return@mapNotNull null
            }
        PlanBlockingOperation(kind, subtreeAliases(row.id), row.rows)
    }
    return NormalizedQueryPlan(
        relations = relations,
        joins = joins,
        blockingOperations = operations,
        rawOptimizerCost = rows.firstOrNull { it.id == 0 }?.cost,
    )
}
