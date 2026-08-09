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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource

private val planJson = Json { ignoreUnknownKeys = true }

internal fun parsePostgresPlan(raw: String): NormalizedQueryPlan? = runCatching {
    val document = planJson.parseToJsonElement(raw)
    val envelope = (document as? JsonArray)?.firstOrNull()?.jsonObject ?: document.jsonObject
    val root = envelope.objectValue("Plan") ?: return null
    val relations = mutableListOf<PlanRelationAccess>()
    val joins = mutableListOf<PlanJoinEvidence>()
    val operations = mutableListOf<PlanBlockingOperation>()

    fun visit(node: JsonObject): Set<String> {
        val childAliases =
            node.arrayValue("Plans").orEmpty().filterIsInstance<JsonObject>().flatMapTo(
                linkedSetOf()
            ) {
                visit(it)
            }
        val alias = node.string("Alias")
        val aliases = childAliases + listOfNotNull(alias)
        val nodeType = node.string("Node Type").orEmpty()
        val rows = node.long("Plan Rows")
        val relation = node.string("Relation Name")
        if (relation != null) {
            val condition =
                listOfNotNull(
                        node.string("Index Cond"),
                        node.string("Recheck Cond"),
                        node.string("Filter"),
                    )
                    .joinToString(" ")
            val method =
                when {
                    nodeType == "Seq Scan" -> PlanAccessMethod.TableScan
                    nodeType == "Bitmap Heap Scan" || nodeType == "Bitmap Index Scan" ->
                        PlanAccessMethod.BoundedRange
                    nodeType.contains("Index Only Scan") || nodeType.contains("Index Scan") ->
                        if (condition.contains(" = ")) PlanAccessMethod.BoundedLookup
                        else PlanAccessMethod.BoundedRange
                    nodeType.contains("Scan") -> PlanAccessMethod.Other
                    else -> PlanAccessMethod.Unknown
                }
            relations +=
                PlanRelationAccess(
                    schema = node.string("Schema"),
                    table = relation,
                    alias = alias,
                    method = method,
                    estimatedRows = rows,
                    specializedTextEvidence =
                        "@@" in condition ||
                            "gin" in node.string("Index Name").orEmpty().lowercase(),
                )
        }
        if (nodeType == "Nested Loop" || nodeType.endsWith(" Join")) {
            joins += PlanJoinEvidence(aliases, rows)
        }
        val kind =
            when (nodeType) {
                "Sort",
                "Incremental Sort" -> PlanOperationKind.Sort
                "Aggregate",
                "Group",
                "GroupAggregate",
                "HashAggregate" -> PlanOperationKind.Grouping
                "Unique" -> PlanOperationKind.Distinct
                else -> null
            }
        if (kind != null) operations += PlanBlockingOperation(kind, aliases, rows)
        return aliases
    }

    visit(root)
    NormalizedQueryPlan(
        relations,
        joins,
        operations,
        envelope.double("Total Cost") ?: root.double("Total Cost"),
    )
}
    .getOrNull()

internal fun parseMySqlPlan(raw: String): NormalizedQueryPlan? = runCatching {
    val document = planJson.parseToJsonElement(raw)
    val relations = mutableListOf<PlanRelationAccess>()
    val joins = mutableListOf<PlanJoinEvidence>()
    val operations = mutableListOf<PlanBlockingOperation>()

    fun visit(
        element: JsonElement,
        inheritedOperation: PlanOperationKind? = null,
    ): Set<String> {
        return when (element) {
            is JsonArray -> element.flatMapTo(linkedSetOf()) { visit(it, inheritedOperation) }
            is JsonObject -> {
                val tableNode =
                    element.objectValue("table") ?: element.takeIf { "table_name" in it }
                val localAliases = linkedSetOf<String>()
                if (tableNode != null) {
                    val table = tableNode.string("table_name")
                    val operation = tableNode.string("operation").orEmpty()
                    val alias =
                        tableNode.string("table_alias") ?: mysqlOperationAlias(operation) ?: table
                    if (alias != null) localAliases += alias
                    val accessType = tableNode.string("access_type")?.lowercase()
                    val indexAccessType = tableNode.string("index_access_type")?.lowercase()
                    val method =
                        when {
                            indexAccessType?.contains("single") == true ||
                                indexAccessType?.contains("lookup") == true ||
                                operation.startsWith("Single-row index lookup", true) ||
                                operation.startsWith("Index lookup", true) ->
                                PlanAccessMethod.BoundedLookup
                            indexAccessType?.contains("range") == true ||
                                indexAccessType?.contains("fulltext") == true ||
                                operation.startsWith("Index range scan", true) ||
                                operation.contains("full-text", true) ->
                                PlanAccessMethod.BoundedRange
                            indexAccessType?.contains("scan") == true ||
                                operation.startsWith("Index scan", true) ||
                                operation.startsWith("Covering index scan", true) ->
                                PlanAccessMethod.FullIndexScan
                            operation.startsWith("Table scan", true) -> PlanAccessMethod.TableScan
                            accessType == "table" -> PlanAccessMethod.TableScan
                            else ->
                                when (accessType) {
                                    "system",
                                    "const",
                                    "eq_ref",
                                    "ref" -> PlanAccessMethod.BoundedLookup
                                    "range",
                                    "index_merge",
                                    "fulltext" -> PlanAccessMethod.BoundedRange
                                    "index" -> PlanAccessMethod.FullIndexScan
                                    "all" -> PlanAccessMethod.TableScan
                                    null -> PlanAccessMethod.Unknown
                                    else -> PlanAccessMethod.Other
                                }
                        }
                    relations +=
                        PlanRelationAccess(
                            schema = tableNode.string("schema_name"),
                            table = table,
                            alias = alias,
                            method = method,
                            estimatedRows =
                                tableNode.long("rows_examined_per_scan")
                                    ?: tableNode.long("rows")
                                    ?: tableNode.long("estimated_rows"),
                            specializedTextEvidence =
                                accessType == "fulltext" ||
                                    indexAccessType?.contains("fulltext") == true ||
                                    operation.contains("full-text", true),
                        )
                }
                val descendantAliases =
                    element.entries.flatMapTo(linkedSetOf()) { (key, value) ->
                        if (key == "table") emptySet()
                        else
                            visit(
                                value,
                                operationKindForMysqlKey(key) ?: inheritedOperation,
                            )
                    }
                val aliases = localAliases + descendantAliases
                val nestedLoop = element.arrayValue("nested_loop")
                val inputs = element.arrayValue("inputs")
                val operationText = element.string("operation").orEmpty()
                if (
                    nestedLoop != null ||
                        (inputs != null &&
                            inputs.size > 1 &&
                            (operationText.contains("join", true) ||
                                operationText.contains("nested loop", true)))
                ) {
                    joins +=
                        PlanJoinEvidence(
                            aliases,
                            element.long("rows_produced_per_join")
                                ?: element.long("estimated_rows")
                                ?: maximumRows(relations, aliases),
                        )
                }
                val ownKind =
                    element.keys.firstNotNullOfOrNull(::operationKindForMysqlKey)
                        ?: operationKindForMysqlText(operationText)
                        ?: inheritedOperation
                if (ownKind != null && aliases.isNotEmpty()) {
                    operations +=
                        PlanBlockingOperation(
                            ownKind,
                            aliases,
                            element.long("rows_produced_per_join")
                                ?: element.long("estimated_rows")
                                ?: maximumRows(relations, aliases),
                        )
                }
                aliases
            }
            else -> emptySet()
        }
    }

    visit(document)
    if (relations.isEmpty() && joins.isEmpty() && operations.isEmpty()) return null
    NormalizedQueryPlan(
        relations,
        joins.distinct(),
        operations.distinct(),
        findJsonCost(document),
    )
}
    .getOrNull()

private fun operationKindForMysqlKey(key: String): PlanOperationKind? =
    when (key) {
        "ordering_operation",
        "filesort" -> PlanOperationKind.Sort
        "grouping_operation",
        "group_by_subqueries" -> PlanOperationKind.Grouping
        "duplicates_removal" -> PlanOperationKind.Distinct
        else -> null
    }

private fun mysqlOperationAlias(operation: String): String? =
    Regex("""(?i)\bon\s+`?([A-Za-z0-9_$]+)`?""").find(operation)?.groupValues?.get(1)

private fun operationKindForMysqlText(operation: String): PlanOperationKind? =
    when {
        operation.contains("sort", true) -> PlanOperationKind.Sort
        operation.contains("group", true) || operation.contains("aggregate", true) ->
            PlanOperationKind.Grouping
        operation.contains("duplicate", true) || operation.contains("distinct", true) ->
            PlanOperationKind.Distinct
        else -> null
    }

private fun maximumRows(relations: List<PlanRelationAccess>, aliases: Set<String>): Long? =
    relations
        .filter { it.alias in aliases }
        .mapNotNull(PlanRelationAccess::estimatedRows)
        .maxOrNull()

private fun findJsonCost(element: JsonElement): Double? =
    when (element) {
        is JsonObject ->
            element.double("query_cost")
                ?: element.double("estimated_total_cost")
                ?: element.values.firstNotNullOfOrNull(::findJsonCost)
        is JsonArray -> element.firstNotNullOfOrNull(::findJsonCost)
        else -> null
    }

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
        val rows = relOp.getAttribute("EstimateRows").toDoubleOrNull()?.toLong()
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
    val statement = document.getElementsByTagNameNS("*", "StmtSimple").item(0) as? Element
    val cost = statement?.getAttribute("StatementSubTreeCost")?.toDoubleOrNull()
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
        if (!prefix.getAttribute("ScanType").equals("EQ", ignoreCase = true))
            return PlanAccessMethod.BoundedRange
    }
    return PlanAccessMethod.BoundedLookup
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

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
