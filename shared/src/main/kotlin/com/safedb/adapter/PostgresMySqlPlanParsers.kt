package com.safedb.adapter

import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanBlockingOperation
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanRelationAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun parsePostgresPlan(raw: String): NormalizedQueryPlan? = runCatching {
    val document = planJson.parseToJsonElement(raw)
    val envelope = (document as? JsonArray)?.firstOrNull()?.jsonObject ?: document.jsonObject
    val root = envelope.objectValue("Plan") ?: return null
    val relations = mutableListOf<PlanRelationAccess>()
    val joins = mutableListOf<PlanJoinEvidence>()
    val operations = mutableListOf<PlanBlockingOperation>()

    fun visit(
        node: JsonObject,
        outputCap: Long? = null,
        accessCap: Long? = null,
    ): Set<String> {
        val nodeType = node.string("Node Type").orEmpty()
        val rows = node.long("Plan Rows")
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
        val childOutputCap = if (nodeType == "Limit") rows ?: outputCap else outputCap
        val childAccessCap =
            when {
                nodeType == "Limit" -> rows ?: accessCap
                postgresPassesAccessLimit(nodeType, kind) -> accessCap
                else -> null
            }
        val childAliases =
            node.arrayValue("Plans").orEmpty().filterIsInstance<JsonObject>().flatMapTo(
                linkedSetOf(),
            ) {
                visit(it, childOutputCap, childAccessCap)
            }
        val alias = node.string("Alias")
        val aliases = childAliases + listOfNotNull(alias)
        val relation = node.string("Relation Name")
        val accessRows = capPlanRows(rows, accessCap)
        val outputRows = capPlanRows(rows, outputCap)
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
                    estimatedRows = accessRows,
                    specializedTextEvidence =
                        "@@" in condition ||
                            "gin" in node.string("Index Name").orEmpty().lowercase(),
                )
        }
        if (nodeType == "Nested Loop" || nodeType.endsWith(" Join")) {
            joins += PlanJoinEvidence(aliases, outputRows)
        }
        // Blocking operators still do the uncapped work even when a Limit sits above them.
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

// Limit can stop Nested Loop / Append / Gather / scans early. Hash/merge joins, sorts,
// aggregates, and unknown nodes still read their inputs, so a LIMIT above them must not
// shrink scan rows.
private fun postgresPassesAccessLimit(nodeType: String, kind: PlanOperationKind?): Boolean =
    when {
        kind != null -> false
        nodeType.startsWith("Nested Loop") -> true
        nodeType == "Append" || nodeType == "Gather" || nodeType == "Gather Merge" -> true
        nodeType.contains("Scan") -> true
        else -> false
    }

internal fun parseMySqlPlan(raw: String, queryLimit: Long? = null): NormalizedQueryPlan? =
    runCatching {
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
                            tableNode.string("table_alias")
                                ?: mysqlOperationAlias(operation)
                                ?: table
                        if (alias != null) localAliases += alias
                        val accessType = tableNode.string("access_type")?.lowercase()
                        val indexAccessType = tableNode.string("index_access_type")?.lowercase()
                        val method = mysqlAccessMethod(operation, accessType, indexAccessType)
                        relations +=
                            PlanRelationAccess(
                                schema = tableNode.string("schema_name"),
                                table = table,
                                alias = alias,
                                method = method,
                                estimatedRows = mysqlTableRows(tableNode),
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
                                maxRows(
                                    element.long("rows_produced_per_join"),
                                    element.long("estimated_rows"),
                                    nestedLoop?.let(::nestedLoopOutputRows),
                                    nestedLoop?.let(::nestedLoopDrivingRows),
                                    inputs?.let(::mysqlInputsDrivingRows),
                                    maximumRows(relations, aliases),
                                ),
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
                                    ?: nestedLoop?.let(::nestedLoopOutputRows)
                                    ?: maximumRows(relations, aliases),
                            )
                    }
                    aliases
                }
                else -> emptySet()
            }
        }

        visit(document)
        if (relations.isEmpty() && joins.isEmpty() && operations.isEmpty()) {
            if (!isMysqlZeroRowPlan(document)) return null
            return NormalizedQueryPlan(rawOptimizerCost = findJsonCost(document) ?: 0.0)
        }
        capMysqlEarlyStopRows(document, queryLimit, relations, joins)
        NormalizedQueryPlan(
            relations,
            joins.distinct(),
            operations.distinct(),
            findJsonCost(document),
        )
    }
    .getOrNull()

internal fun mysqlLimitFromSql(sql: String): Long? {
    val match = Regex("""(?i)\bLIMIT\s+(\d+)\s*;?\s*$""").find(sql.trim()) ?: return null
    return match.groupValues[1].toLong()
}

private fun isMysqlZeroRowPlan(element: JsonElement): Boolean {
    val messages = mutableListOf<String>()
    fun collect(node: JsonElement) {
        when (node) {
            is JsonArray -> node.forEach(::collect)
            is JsonObject -> {
                node.string("message")?.let(messages::add)
                node.values.forEach(::collect)
            }
            else -> Unit
        }
    }
    collect(element)
    return messages.any { message ->
        val lower = message.lowercase()
        "impossible where" in lower || "no matching row" in lower
    }
}

private fun mysqlAccessMethod(
    operation: String,
    accessType: String?,
    indexAccessType: String?,
): PlanAccessMethod =
    when {
        indexAccessType?.contains("single") == true ||
            indexAccessType?.contains("lookup") == true ||
            operation.startsWith("Single-row index lookup", true) ||
            operation.startsWith("Index lookup", true) -> PlanAccessMethod.BoundedLookup
        indexAccessType?.contains("range") == true ||
            indexAccessType?.contains("fulltext") == true ||
            operation.startsWith("Index range scan", true) ||
            operation.contains("full-text", true) -> PlanAccessMethod.BoundedRange
        indexAccessType?.contains("scan") == true ||
            operation.startsWith("Index scan", true) ||
            operation.startsWith("Covering index scan", true) -> PlanAccessMethod.FullIndexScan
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

private fun mysqlEarlyStopAccess(method: PlanAccessMethod): Boolean =
    when (method) {
        PlanAccessMethod.TableScan,
        PlanAccessMethod.FullIndexScan,
        PlanAccessMethod.BoundedLookup,
        PlanAccessMethod.BoundedRange -> true
        PlanAccessMethod.Unknown,
        PlanAccessMethod.Other -> false
    }

private fun capMysqlEarlyStopRows(
    document: JsonElement,
    queryLimit: Long?,
    relations: MutableList<PlanRelationAccess>,
    joins: MutableList<PlanJoinEvidence>,
) {
    val limit = findMysqlLimit(document) ?: queryLimit ?: return
    if (mysqlBlocksEarlyStop(document)) return
    if (relations.isEmpty() || relations.any { !mysqlEarlyStopAccess(it.method) }) return
    for (index in relations.indices) {
        val relation = relations[index]
        relations[index] = relation.copy(estimatedRows = capPlanRows(relation.estimatedRows, limit))
    }
    for (index in joins.indices) {
        val join = joins[index]
        joins[index] = join.copy(estimatedOutputRows = capPlanRows(join.estimatedOutputRows, limit))
    }
}

private val mysqlLimitOperation = Regex("""(?i)^Limit(?:\s+(\d+))?(?:\s|$)""")

private fun findMysqlLimit(element: JsonElement): Long? =
    when (element) {
        is JsonObject -> {
            element.long("limit")
                ?: mysqlLimitOperation.find(element.string("operation").orEmpty())?.let { match ->
                    match.groupValues[1].toLongOrNull() ?: element.long("estimated_rows")
                }
                ?: element.values.firstNotNullOfOrNull(::findMysqlLimit)
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findMysqlLimit)
        else -> null
    }

private fun mysqlBlocksEarlyStop(element: JsonElement): Boolean =
    when (element) {
        is JsonArray -> element.any(::mysqlBlocksEarlyStop)
        is JsonObject ->
            "grouping_operation" in element ||
                "group_by_subqueries" in element ||
                "duplicates_removal" in element ||
                "filesort" in element ||
                element.boolean("using_temporary_table") == true ||
                element.boolean("using_filesort") == true ||
                element.string("using_join_buffer").orEmpty().contains("hash", true) ||
                mysqlOperationBlocksEarlyStop(element.string("operation").orEmpty()) ||
                mysqlOrderingBlocksEarlyStop(element) ||
                element.values.any(::mysqlBlocksEarlyStop)
        else -> false
    }

private fun mysqlOrderingBlocksEarlyStop(element: JsonObject): Boolean {
    val ordering = element.objectValue("ordering_operation") ?: return false
    return ordering.boolean("using_filesort") != false
}

private fun mysqlOperationBlocksEarlyStop(operation: String): Boolean =
    operation.contains("hash join", true) ||
        operation.contains("filesort", true) ||
        operation.contains("temporary", true) ||
        operation.contains("group", true) ||
        operation.contains("aggregate", true) ||
        (operation.contains("sort", true) && !operation.contains("index", true))

private fun mysqlTableRows(tableNode: JsonObject): Long? =
    tableNode.long("rows_examined_per_scan")
        ?: tableNode.long("rows")
        ?: tableNode.long("estimated_rows")

private fun maxRows(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

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

// MySQL stamps each table node with the cumulative join-prefix output, so the last table of a
// nested_loop carries the join's total output rows; the max-of-relations fallback under-counts.
// eq_ref inners often report 1 produced row per lookup, so also keep the driving table's scan.
private fun nestedLoopOutputRows(nestedLoop: JsonArray): Long? =
    (nestedLoop.lastOrNull() as? JsonObject)?.objectValue("table")?.long("rows_produced_per_join")

private fun nestedLoopDrivingRows(nestedLoop: JsonArray): Long? =
    (nestedLoop.firstOrNull() as? JsonObject)?.objectValue("table")?.let(::mysqlTableRows)

private fun mysqlInputsDrivingRows(inputs: JsonArray): Long? =
    (inputs.firstOrNull() as? JsonObject)?.let { node ->
        mysqlTableRows(node.objectValue("table") ?: node)
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
