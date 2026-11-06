package com.safedb.mcp

import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.qualifiedName
import com.safedb.query.isSchemaBlocked
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registerSchemaTools(server: Server, service: SafeDbService, cache: SchemaCache) {
    server.addTool(
        name = "list_tables",
        description =
            "List visible tables for a saved connection as schema, name, qualified_name, " +
                "size_class, and column_count. Uses a cached catalog (5 minute TTL); pass " +
                "refresh=true to introspect again. Honor blocked_schemas: blocked and system " +
                "catalogs are omitted. Use describe_table for columns, indexes, and foreign " +
                "keys of one table. Do not expect the full catalog in one payload. " +
                "Add connections with `safe-db-mcp setup` or `safe-db-mcp connections add`.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Connection id from list_connections")
                            },
                        )
                        put(
                            "refresh",
                            buildJsonObject {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true, ignore the cached catalog and introspect again",
                                )
                            },
                        )
                    },
                required = listOf("connection_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("list_tables failed") { handleListTables(service, cache, request) }
    }

    server.addTool(
        name = "describe_table",
        description =
            "Describe one visible table: columns (name, data_type, nullable), indexes, and " +
                "foreign keys. Pass schema and table from list_tables. Uses the same cached " +
                "catalog as list_tables (5 minute TTL); pass refresh=true to introspect again. " +
                "Unknown or blocked tables return an error. Do not dump the full catalog.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Connection id from list_connections")
                            },
                        )
                        put(
                            "schema",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Schema name from list_tables")
                            },
                        )
                        put(
                            "table",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Table name from list_tables")
                            },
                        )
                        put(
                            "refresh",
                            buildJsonObject {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true, ignore the cached catalog and introspect again",
                                )
                            },
                        )
                    },
                required = listOf("connection_id", "schema", "table"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("describe_table failed") { handleDescribeTable(service, cache, request) }
    }
}

private suspend fun handleListTables(
    service: SafeDbService,
    cache: SchemaCache,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "refresh").getOrElse {
        return it
    }
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val refresh =
        optionalBoolean(request, "refresh").getOrElse {
            return it
        } == true
    if (service.listConnections().none { it.id == connectionId }) {
        return toolError(ERROR_NOT_FOUND, "Connection not found")
    }
    val schema = cache.get(connectionId, refresh = refresh)
    val payload = toolJson.encodeToString(visibleTables(service, schema).map { it.toSummary() })
    return CallToolResult(content = listOf(TextContent(text = payload)))
}

private suspend fun handleDescribeTable(
    service: SafeDbService,
    cache: SchemaCache,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "schema", "table", "refresh").getOrElse {
        return it
    }
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val schemaName =
        requiredString(request, "schema").getOrElse {
            return it
        }
    val tableName =
        requiredString(request, "table").getOrElse {
            return it
        }
    val refresh =
        optionalBoolean(request, "refresh").getOrElse {
            return it
        } == true
    if (service.listConnections().none { it.id == connectionId }) {
        return toolError(ERROR_NOT_FOUND, "Connection not found")
    }
    val schema = cache.get(connectionId, refresh = refresh)
    val table =
        visibleTables(service, schema).find { it.schema == schemaName && it.name == tableName }
            ?: return toolError(ERROR_NOT_FOUND, "Table not found")
    return CallToolResult(
        content = listOf(TextContent(text = toolJson.encodeToString(table.toDetail())))
    )
}

@Serializable
internal data class TableSummary(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    @SerialName("size_class") val sizeClass: TableSizeClass,
    @SerialName("column_count") val columnCount: Int,
)

@Serializable
internal data class TableDetail(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    val columns: List<TableColumn>,
    val indexes: List<TableIndex>,
    @SerialName("foreign_keys") val foreignKeys: List<TableForeignKey>,
)

@Serializable
internal data class TableColumn(
    val name: String,
    @SerialName("data_type") val dataType: String,
    val nullable: Boolean,
)

@Serializable
internal data class TableIndex(
    val name: String,
    val columns: List<String>,
    @SerialName("included_columns") val includedColumns: List<String>,
    val kind: String,
    @SerialName("is_unique") val isUnique: Boolean,
    @SerialName("is_primary") val isPrimary: Boolean,
)

@Serializable
internal data class TableForeignKey(
    val name: String,
    val columns: List<String>,
    @SerialName("referenced_schema") val referencedSchema: String,
    @SerialName("referenced_table") val referencedTable: String,
    @SerialName("referenced_columns") val referencedColumns: List<String>,
)

private suspend fun visibleTables(service: SafeDbService, schema: Schema): List<TableInfo> {
    val blocked = service.getSettings().blockedSchemas
    return schema.tables
        .filter { !isSchemaBlocked(it.schema, blocked) }
        .sortedWith(
            compareBy<TableInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.schema }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
}

private fun TableInfo.toSummary(): TableSummary =
    TableSummary(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        sizeClass = tableSize.sizeClass,
        columnCount = columns.size,
    )

private fun TableInfo.toDetail(): TableDetail =
    TableDetail(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        columns = columns.map { TableColumn(it.name, it.dataType, it.nullable) },
        indexes =
            indexes.map {
                TableIndex(
                    name = it.name,
                    columns = it.columns,
                    includedColumns = it.includedColumns,
                    kind = it.kind,
                    isUnique = it.isUnique,
                    isPrimary = it.isPrimary,
                )
            },
        foreignKeys =
            foreignKeys.map {
                TableForeignKey(
                    name = it.name,
                    columns = it.columns,
                    referencedSchema = it.referencedSchema,
                    referencedTable = it.referencedTable,
                    referencedColumns = it.referencedColumns,
                )
            },
    )
