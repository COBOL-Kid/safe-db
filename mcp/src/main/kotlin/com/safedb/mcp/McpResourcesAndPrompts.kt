package com.safedb.mcp

import com.safedb.model.TableInfo
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val CONNECTIONS_RESOURCE_URI = "safedb://connections"

internal const val CONNECTION_TABLES_RESOURCE_TEMPLATE =
    "safedb://connections/{connection_id}/tables"

internal const val RESULT_SUMMARY_RESOURCE_TEMPLATE = "safedb://results/{result_id}/summary"

internal const val EXPLORE_DATABASE_PROMPT = "explore-database"

internal const val ANALYZE_RESULT_PROMPT = "analyze-result"

private const val APPLICATION_JSON = "application/json"

private const val RESOURCE_NOT_FOUND_CODE = -32002

internal fun registerMcpResourcesAndPrompts(
    server: Server,
    service: SafeDbService,
    schemaCache: SchemaCache,
    resultStore: ResultStore,
) {
    server.addResource(
        uri = CONNECTIONS_RESOURCE_URI,
        name = "connections",
        description = "Saved safe-db connections (no secrets)",
        mimeType = APPLICATION_JSON,
    ) { _ ->
        ReadResourceResult(
            contents =
                listOf(
                    jsonResource(
                        CONNECTIONS_RESOURCE_URI,
                        toolJson.encodeToString(service.listConnections().map { it.toSummary() }),
                    )
                )
        )
    }

    server.addResourceTemplate(
        uriTemplate = CONNECTION_TABLES_RESOURCE_TEMPLATE,
        name = "connection-tables",
        description = "Visible tables for a saved safe-db connection",
        mimeType = APPLICATION_JSON,
    ) { _, variables ->
        val connectionId =
            variables["connection_id"]?.takeIf { it.isNotBlank() }
                ?: throw McpException(RESOURCE_NOT_FOUND_CODE, "Connection not found")
        val connection =
            service.listConnections().find { it.id == connectionId }
                ?: throw McpException(RESOURCE_NOT_FOUND_CODE, "Connection not found")
        val schema = schemaCache.get(connectionId)
        val tables =
            visibleTables(schema.tables, service.getSettings().blockedSchemas, connection.dialect)
                .map { it.toResourceSummary() }
        ReadResourceResult(
            contents =
                listOf(
                    jsonResource(
                        CONNECTION_TABLES_RESOURCE_TEMPLATE.replace(
                            "{connection_id}",
                            connectionId,
                        ),
                        toolJson.encodeToString(tables),
                    )
                )
        )
    }

    server.addResourceTemplate(
        uriTemplate = RESULT_SUMMARY_RESOURCE_TEMPLATE,
        name = "result-summary",
        description = "Column summary of a fetched safe-db result sample",
        mimeType = APPLICATION_JSON,
    ) { _, variables ->
        val resultId =
            variables["result_id"]?.takeIf { it.isNotBlank() }
                ?: throw McpException(RESOURCE_NOT_FOUND_CODE, "Result not found")
        val entry =
            resultStore.getEntry(resultId)
                ?: throw McpException(RESOURCE_NOT_FOUND_CODE, "Result not found")
        val summary =
            ResultSummary(
                resultId = resultId,
                rowCount = entry.result.rowCount,
                sampleCapped = entry.result.truncated,
                columns = summarizeColumns(entry.result),
            )
        ReadResourceResult(
            contents =
                listOf(
                    jsonResource(
                        RESULT_SUMMARY_RESOURCE_TEMPLATE.replace("{result_id}", resultId),
                        toolJson.encodeToString(summary),
                    )
                )
        )
    }

    server.addPrompt(
        name = EXPLORE_DATABASE_PROMPT,
        description =
            "Guided workflow to inspect a connection's schema, discover join paths, assess " +
                "risk, and run a safe SELECT",
        arguments =
            listOf(
                PromptArgument(
                    name = "connection_id",
                    description = "Optional connection id from list_connections",
                    required = false,
                )
            ),
    ) { request ->
        val connectionId = request.arguments?.get("connection_id")?.takeIf { it.isNotBlank() }
        GetPromptResult(
            description = "Explore a safe-db connection safely",
            messages =
                listOf(
                    PromptMessage(
                        Role.User,
                        TextContent(
                            text =
                                buildString {
                                    if (connectionId != null) {
                                        append("Explore safe-db connection '$connectionId'. ")
                                    } else {
                                        append(
                                            "Explore a safe-db connection. Start with " +
                                                "list_connections to choose a connection_id. "
                                        )
                                    }
                                    append(
                                        "Call list_tables with connection_id and note the " +
                                            "schema names. Use describe_table for columns, " +
                                            "indexes, and foreign keys. Use find_join_paths to " +
                                            "discover indexed or foreign-key join paths. Use " +
                                            "search_catalog when the table name is unknown. " +
                                            "Then assess_query the intended SELECT and inspect " +
                                            "the risk summary and confirmation reasons. " +
                                            "Finally run_query with a bounded SELECT and page " +
                                            "the sample with get_result_rows or " +
                                            "summarize_result. Never send a password or " +
                                            "connection URL."
                                    )
                                }
                        ),
                    )
                ),
        )
    }

    server.addPrompt(
        name = ANALYZE_RESULT_PROMPT,
        description =
            "Guided workflow to summarize, pivot, filter, or export a fetched result_id " +
                "sample in memory",
        arguments =
            listOf(
                PromptArgument(
                    name = "result_id",
                    description = "result_id from a successful run_query receipt",
                    required = true,
                )
            ),
    ) { request ->
        val resultId = request.arguments?.get("result_id")?.takeIf { it.isNotBlank() }
        GetPromptResult(
            description = "Analyze a fetched result sample in memory",
            messages =
                listOf(
                    PromptMessage(
                        Role.User,
                        TextContent(
                            text =
                                buildString {
                                    if (resultId != null) {
                                        append("Analyze safe-db result '$resultId'. ")
                                    } else {
                                        append(
                                            "Analyze a safe-db result. Start with run_query to " +
                                                "obtain a result_id. "
                                        )
                                    }
                                    append(
                                        "Use summarize_result for column null counts, ranges, " +
                                            "and distinct values. Use pivot_result to group and " +
                                            "aggregate the sample, or filter_result_rows to " +
                                            "filter, sort, and page it. Use apply_recipe with a " +
                                            "recipe_id from list_recipes to apply a saved " +
                                            "Explore layout. Use export_result to write the " +
                                            "sample to CSV. Work from the in-memory sample and " +
                                            "do not re-run the original query unless more rows " +
                                            "are required."
                                    )
                                }
                        ),
                    )
                ),
        )
    }
}

private fun jsonResource(uri: String, text: String): TextResourceContents =
    TextResourceContents(text = text, uri = uri, mimeType = APPLICATION_JSON)

private fun TableInfo.toResourceSummary(): ResourceTableSummary =
    ResourceTableSummary(
        schema = schema,
        name = name,
        qualifiedName = "$schema.$name",
        columnCount = columns.size,
    )

@Serializable
internal data class ResourceTableSummary(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    @SerialName("column_count") val columnCount: Int,
)
