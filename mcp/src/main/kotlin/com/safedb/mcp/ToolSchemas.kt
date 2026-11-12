package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun stringProp(description: String? = null): JsonObject = typedProp("string", description)

internal fun intProp(description: String? = null): JsonObject = typedProp("integer", description)

internal fun boolProp(description: String? = null): JsonObject = typedProp("boolean", description)

internal fun arrayProp(description: String? = null): JsonObject = typedProp("array", description)

internal fun objectProp(description: String? = null): JsonObject = typedProp("object", description)

internal fun connectionIdProperty(optional: Boolean = false): JsonObject =
    stringProp(
        if (optional) "Optional connection id from list_connections"
        else "Connection id from list_connections"
    )

internal fun sqlProperty(
    description: String =
        "A single SELECT. Result columns are named table__column. " +
            "No aggregates or functions, OFFSET, subqueries, CTEs, " +
            "UNION, LEFT/CROSS or comma joins, AS aliases, or " +
            "leading NOT. Joins need indexed or FK columns."
): JsonObject = stringProp(description)

internal fun defaultSchemaProperty(): JsonObject =
    stringProp(
        "Schema used when a table name has no schema prefix. Copy " +
            "list_tables.tables[].schema for this connection (MySQL: usually " +
            "the database name). Omit when every table is already schema.table."
    )

internal fun confirmationProperty(): JsonObject =
    objectProp(
        "Echo the confirmation object from a confirmation_required " +
            "error after showing the reasons to the user. Do not " +
            "invent this object."
    )

internal fun refreshProperty(): JsonObject =
    boolProp("If true, ignore the cached catalog and introspect again")

internal fun resultIdProperty(): JsonObject =
    stringProp("result_id from a successful run_query receipt")

internal fun boundedLimitProperty(
    defaultLimit: Int,
    maxLimit: Int,
    noun: String,
    extraNote: String = "",
): JsonObject =
    intProp(
        "Max $noun to return (1–$maxLimit). Default $defaultLimit. Values over " +
            "$maxLimit cap at $maxLimit. 0 or negative is invalid_arguments." +
            extraNote
    )

internal fun objectOutputSchema(
    required: List<String>,
    properties: JsonObjectBuilder.() -> Unit,
): ToolSchema = ToolSchema(properties = buildJsonObject(properties), required = required)

internal fun queryReceiptOutputSchema(): ToolSchema =
    objectOutputSchema(
        required =
            listOf(
                "dialect",
                "columns",
                "row_count",
                "warnings",
                "risk",
                "preview",
                "preview_truncated",
                "result_id",
            )
    ) {
        put("dialect", stringProp())
        put("columns", arrayProp())
        put("row_count", intProp())
        put("warnings", arrayProp())
        put("risk", objectProp())
        put("preview", arrayProp())
        put("preview_truncated", boolProp())
        put("result_id", stringProp())
        put("sample_capped", boolProp())
        put("sample_cap_reason", stringProp())
        put("sample_unbounded", boolProp())
    }

private fun typedProp(type: String, description: String?): JsonObject = buildJsonObject {
    put("type", type)
    if (description != null) put("description", description)
}
