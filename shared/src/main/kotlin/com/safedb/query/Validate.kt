package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnSel
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.classifyColumn

const val LARGE_LIMIT_WARNING_THRESHOLD = 1000
const val MAX_LIMIT = 10_000
const val DEFAULT_LIMIT = 100
const val MAX_FILTER_DEPTH = 5
const val MAX_IN_LIST_SIZE = 1000
const val MAX_TEXT_LITERAL_LEN = 10_000

internal val BLOCKED_SCHEMAS = listOf(
    "pg_catalog",
    "information_schema",
    "pg_toast",
    "mysql",
    "performance_schema",
    "sys",
    "guest",
    "INFORMATION_SCHEMA",
    "SYS",
    "SYSTEM",
    "OUTLN",
    "DBSNMP",
    "XDB",
    "CTXSYS",
    "MDSYS",
    "OLAPSYS",
    "WMSYS",
    "ORDSYS",
    "EXFSYS",
    "ANONYMOUS",
    "APEX_PUBLIC_USER",
    "FLOWS_FILES",
    "APEX_030200",
    "APEX_040000",
    "APEX_040200",
    "AUDSYS",
    "GSMADMIN_INTERNAL",
    "SYSMAN",
    "DBSFWUSER",
    "APPQOSSYS",
    "ORACLE_OCM",
    "XS\$NULL",
    "DVSYS",
    "LBACSYS",
)

data class ValidationOutcome(
    val warnings: List<String>,
    val limit: Int,
)

data class ValidatedColumn(
    val tableAlias: String,
    val column: String,
    val resultAlias: String,
)

class ValidatedQuery internal constructor(
    private val spec: QuerySpec,
    private val columns: List<ValidatedColumn>,
) {
    fun spec(): QuerySpec = spec
    fun columns(): List<ValidatedColumn> = columns
}

fun validateQuery(
    spec: QuerySpec,
    schema: Schema,
    customBlocked: List<String>,
): Outcome<Pair<ValidatedQuery, ValidationOutcome>> {
    val (normalizedSpec, outcome) = when (val result = validate(spec, schema, customBlocked)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> return Outcome.err(result.message)
    }

    val selections = if (normalizedSpec.columns.isEmpty()) {
        normalizedSpec.tables.flatMap { tableRef ->
            findTable(schema, tableRef.schema, tableRef.name)?.columns.orEmpty().map { column ->
                ColumnSel(tableAlias = tableRef.alias, column = column.name)
            }
        }
    } else {
        normalizedSpec.columns
    }

    val aliases = mutableSetOf<String>()
    val validatedColumns = selections.map { selection ->
        val base = "${selection.tableAlias}__${selection.column}"
        var resultAlias = base
        var suffix = 2
        while (!aliases.add(resultAlias)) {
            resultAlias = "${base}__$suffix"
            suffix++
        }
        ValidatedColumn(
            tableAlias = selection.tableAlias,
            column = selection.column,
            resultAlias = resultAlias,
        )
    }

    return Outcome.ok(
        ValidatedQuery(normalizedSpec, validatedColumns) to outcome,
    )
}

fun literalKindForColumn(dataType: String): LiteralKind = when (classifyColumn(dataType)) {
    ColumnCategory.Integer -> LiteralKind.Int
    ColumnCategory.Decimal -> LiteralKind.Decimal
    ColumnCategory.Bool -> LiteralKind.Bool
    ColumnCategory.Date -> LiteralKind.Date
    ColumnCategory.DateTime -> LiteralKind.DateTime
    ColumnCategory.Text, ColumnCategory.Binary, ColumnCategory.Json, ColumnCategory.Other -> LiteralKind.Text
}

fun opsForColumn(dataType: String): List<FilterOp> = when (classifyColumn(dataType)) {
    ColumnCategory.Text -> listOf(
        FilterOp.Eq, FilterOp.Ne, FilterOp.Like, FilterOp.NotLike, FilterOp.Ilike,
        FilterOp.In, FilterOp.NotIn, FilterOp.IsNull, FilterOp.IsNotNull,
        FilterOp.IsEmpty, FilterOp.IsNotEmpty,
    )
    ColumnCategory.Integer, ColumnCategory.Decimal -> listOf(
        FilterOp.Eq, FilterOp.Ne, FilterOp.Gt, FilterOp.Gte, FilterOp.Lt, FilterOp.Lte,
        FilterOp.In, FilterOp.NotIn, FilterOp.Between, FilterOp.IsNull, FilterOp.IsNotNull,
    )
    ColumnCategory.Bool -> listOf(
        FilterOp.Eq, FilterOp.Ne, FilterOp.IsNull, FilterOp.IsNotNull,
    )
    ColumnCategory.Date, ColumnCategory.DateTime -> listOf(
        FilterOp.Eq, FilterOp.Ne, FilterOp.Gt, FilterOp.Gte, FilterOp.Lt, FilterOp.Lte,
        FilterOp.Between, FilterOp.IsNull, FilterOp.IsNotNull,
    )
    ColumnCategory.Binary, ColumnCategory.Json, ColumnCategory.Other -> listOf(
        FilterOp.Eq, FilterOp.Ne, FilterOp.IsNull, FilterOp.IsNotNull,
    )
}

fun validate(
    spec: QuerySpec,
    schema: Schema,
    customBlocked: List<String>,
): Outcome<Pair<QuerySpec, ValidationOutcome>> {
    val warnings = mutableListOf<String>()

    if (spec.schemaVersion != CURRENT_SCHEMA_VERSION) {
        return Outcome.err(
            "Query schema version ${spec.schemaVersion} is unsupported; expected $CURRENT_SCHEMA_VERSION",
        )
    }

    val nodeIds = mutableSetOf<String>()
    when (val result = collectNodeIds(spec.filters, nodeIds)) {
        is Outcome.Ok -> Unit
        is Outcome.Err -> return Outcome.err(result.message)
    }

    for (overrideId in spec.connectorOverrides.keys) {
        if (!nodeIds.contains(overrideId)) {
            return Outcome.err("Connector override references unknown filter node id '$overrideId'")
        }
    }

    if (spec.tables.isEmpty()) {
        return Outcome.err("At least one table is required")
    }

    val tableAliases = mutableSetOf<String>()
    for (table in spec.tables) {
        if (isBlocked(table.schema, customBlocked)) {
            return Outcome.err("Schema '${table.schema}' is blocked (system/catalog schema)")
        }

        if (findTable(schema, table.schema, table.name) == null) {
            return Outcome.err("Table '${table.schema}.${table.name}' not found in schema")
        }

        if (!tableAliases.add(table.alias)) {
            return Outcome.err("Duplicate table alias '${table.alias}'")
        }

        for (col in spec.columns) {
            if (col.tableAlias == table.alias) {
                val exists = findTable(schema, table.schema, table.name)
                    ?.columns
                    ?.any { it.name == col.column }
                    ?: false
                if (!exists) {
                    return Outcome.err("Column '${table.alias}.${col.column}' does not exist")
                }
            }
        }
    }

    for (col in spec.columns) {
        if (!tableAliases.contains(col.tableAlias)) {
            return Outcome.err("Column selection references unknown table alias '${col.tableAlias}'")
        }
    }

    for (join in spec.joins) {
        if (!tableAliases.contains(join.leftAlias)) {
            return Outcome.err("Join references unknown table alias '${join.leftAlias}'")
        }
        if (!tableAliases.contains(join.rightAlias)) {
            return Outcome.err("Join references unknown table alias '${join.rightAlias}'")
        }

        val leftTable = findTableByAlias(schema, spec, join.leftAlias)
            ?: return Outcome.err("Cannot resolve table for alias '${join.leftAlias}'")
        val rightTable = findTableByAlias(schema, spec, join.rightAlias)
            ?: return Outcome.err("Cannot resolve table for alias '${join.rightAlias}'")

        val leftCol = leftTable.columns.find { it.name == join.leftColumn }
            ?: return Outcome.err("Join column '${join.leftAlias}.${join.leftColumn}' does not exist")
        val rightCol = rightTable.columns.find { it.name == join.rightColumn }
            ?: return Outcome.err("Join column '${join.rightAlias}.${join.rightColumn}' does not exist")

        if (!leftCol.joinEligible) {
            return Outcome.err(
                "Join column '${join.leftAlias}.${join.leftColumn}' is not the leading key of an equality-capable index",
            )
        }
        if (!rightCol.joinEligible) {
            return Outcome.err(
                "Join column '${join.rightAlias}.${join.rightColumn}' is not the leading key of an equality-capable index",
            )
        }
        if (leftCol.category != rightCol.category) {
            return Outcome.err(
                "Join columns '${join.leftAlias}.${join.leftColumn}' and '${join.rightAlias}.${join.rightColumn}' have incompatible types",
            )
        }
    }

    when (val result = validateNode(
        node = FilterNode.Group(spec.filters),
        schema = schema,
        spec = spec,
        tableAliases = tableAliases,
        depth = 0,
        warnings = warnings,
    )) {
        is Outcome.Ok -> Unit
        is Outcome.Err -> return Outcome.err(result.message)
    }

    if (spec.tables.size > 1 && !checkJoinConnectivity(spec)) {
        return Outcome.err("Not all tables are connected by joins — add joins linking every table")
    }

    var limit = spec.limit
    if (limit == 0) {
        limit = DEFAULT_LIMIT
        warnings.add("Limit was 0; defaulted to $DEFAULT_LIMIT")
    }
    if (limit > MAX_LIMIT) {
        warnings.add("Limit $limit exceeds maximum $MAX_LIMIT; capped to $MAX_LIMIT")
        limit = MAX_LIMIT
    }
    if (limit > LARGE_LIMIT_WARNING_THRESHOLD) {
        warnings.add(
            "Large result limits are useful for reporting, but filters, selected columns, and indexed predicates keep queries faster and easier to reuse.",
        )
    }

    if (spec.columns.isEmpty()) {
        warnings.add("No columns selected — query will select all columns")
    }

    val normalizedSpec = spec.copy(limit = limit)

    return Outcome.ok(
        normalizedSpec to ValidationOutcome(warnings = warnings.toList(), limit = limit),
    )
}

internal fun findTable(schema: Schema, tableSchema: String, tableName: String) =
    schema.tables.find { it.schema == tableSchema && it.name == tableName }

internal fun findTableByAlias(schema: Schema, spec: QuerySpec, alias: String) =
    spec.tables.find { it.alias == alias }?.let { tableRef ->
        findTable(schema, tableRef.schema, tableRef.name)
    }
