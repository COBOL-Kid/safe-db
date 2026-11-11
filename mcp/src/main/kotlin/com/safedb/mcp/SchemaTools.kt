package com.safedb.mcp

import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.qualifiedName
import com.safedb.schema.JoinEdge
import com.safedb.schema.findDirectJoinEdges
import com.safedb.schema.findJoinPaths
import com.safedb.schema.incomingForeignKeysFor
import com.safedb.schema.searchCatalogTables
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SEARCH_CATALOG_DEFAULT_LIMIT = 25
internal const val SEARCH_CATALOG_MAX_LIMIT = 50
internal const val FIND_JOIN_PATHS_DEFAULT_HOPS = 2
internal const val FIND_JOIN_PATHS_MAX_HOPS = 3

internal fun registerSchemaTools(server: Server, service: SafeDbService, cache: SchemaCache) {
    server.addTool(
        name = "list_tables",
        description =
            "List visible tables for a saved connection as schema, name, qualified_name, " +
                "size_class, and column_count. Uses a cached catalog (5 minute TTL); pass " +
                "refresh=true to introspect again. Honor blocked_schemas: blocked and system " +
                "catalogs are omitted, and Db2 omits EXPLAIN_* and ADVISE_* catalog tables. Use " +
                "describe_table for columns (including " +
                "join_eligible), indexes, foreign keys, and incoming_foreign_keys of one " +
                "table. Do not expect the full catalog in one payload. " +
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
            "Describe one visible table: columns (name, data_type, nullable, join_eligible), " +
                "indexes, foreign keys, and incoming_foreign_keys (source table and columns, " +
                "cardinality, optionality). Pass schema and table exactly as list_tables returns " +
                "them; Oracle and Db2 names are often uppercase. Lookup is exact first, then " +
                "case-insensitive. Uses the same cached catalog as list_tables (5 minute TTL); " +
                "pass refresh=true to introspect again. Unknown or blocked tables return an " +
                "error; a case-only ambiguity lists both names. Do not dump the " +
                "full catalog. The result includes JSON text and structuredContent with the " +
                "same object.",
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
        outputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("schema", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("qualified_name", buildJsonObject { put("type", "string") })
                        put("columns", buildJsonObject { put("type", "array") })
                        put("indexes", buildJsonObject { put("type", "array") })
                        put("foreign_keys", buildJsonObject { put("type", "array") })
                        put("incoming_foreign_keys", buildJsonObject { put("type", "array") })
                    },
                required =
                    listOf(
                        "schema",
                        "name",
                        "qualified_name",
                        "columns",
                        "indexes",
                        "foreign_keys",
                        "incoming_foreign_keys",
                    ),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("describe_table failed") { handleDescribeTable(service, cache, request) }
    }

    server.addTool(
        name = "search_catalog",
        description =
            "Search the cached catalog for tables by name, schema, column name or type, index " +
                "name or key column, or foreign key name, column, or referenced table. " +
                "Case-insensitive substring match. Returns match_count (all matches before " +
                "limit), truncated, and per-table matched_columns, matched_indexes, and " +
                "matched_foreign_keys. limit defaults to $SEARCH_CATALOG_DEFAULT_LIMIT and caps " +
                "at $SEARCH_CATALOG_MAX_LIMIT; 0 or negative is invalid_arguments. Honors " +
                "blocked_schemas and uses the same cached catalog as list_tables (5 minute " +
                "TTL); pass refresh=true to introspect again.",
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
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Case-insensitive substring to match against table metadata.",
                                )
                            },
                        )
                        put(
                            "schema",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional schema filter")
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Max matches to return (1–$SEARCH_CATALOG_MAX_LIMIT). " +
                                        "Default $SEARCH_CATALOG_DEFAULT_LIMIT. Values over " +
                                        "$SEARCH_CATALOG_MAX_LIMIT cap at $SEARCH_CATALOG_MAX_LIMIT. " +
                                        "0 or negative is invalid_arguments.",
                                )
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
                required = listOf("connection_id", "query"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("search_catalog failed") { handleSearchCatalog(service, cache, request) }
    }

    server.addTool(
        name = "find_join_paths",
        description =
            "Find join relationships for a visible table from the cached catalog. Without " +
                "to_table, returns direct neighbors with foreign_key or indexed_columns edges, " +
                "column pairs, an on_clause, and FK cardinality and optionality. Pass " +
                "foreign_key_only=true to exclude indexed-column candidates. With to_table, " +
                "returns up to 10 paths of at most max_hops (default " +
                "$FIND_JOIN_PATHS_DEFAULT_HOPS, max $FIND_JOIN_PATHS_MAX_HOPS). Copy from_table and " +
                "to_table from list_tables (uppercase on Oracle and Db2); they accept " +
                "schema.table or table, and unqualified names use default_schema, " +
                "the saved default schema for this connection, or a unique visible table. " +
                "Honors blocked_schemas and uses the same cached catalog (5 minute TTL); pass " +
                "refresh=true to introspect again. The result includes JSON text and " +
                "structuredContent with the same object.",
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
                            "from_table",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "schema.table or table from list_tables")
                            },
                        )
                        put(
                            "to_table",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional schema.table or table. Omit for direct neighbors.",
                                )
                            },
                        )
                        put(
                            "default_schema",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Schema used when a table name has no schema prefix. Copy " +
                                        "list_tables.schema for this connection.",
                                )
                            },
                        )
                        put(
                            "max_hops",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum path length (1–$FIND_JOIN_PATHS_MAX_HOPS). " +
                                        "Default $FIND_JOIN_PATHS_DEFAULT_HOPS.",
                                )
                            },
                        )
                        put(
                            "foreign_key_only",
                            buildJsonObject {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true, exclude indexed-column candidates and return only " +
                                        "foreign-key edges. Default false.",
                                )
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
                required = listOf("connection_id", "from_table"),
            ),
        outputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("from_table", buildJsonObject { put("type", "string") })
                        put("to_table", buildJsonObject { put("type", "string") })
                        put("neighbors", buildJsonObject { put("type", "array") })
                        put("paths", buildJsonObject { put("type", "array") })
                    },
                required = listOf("from_table"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("find_join_paths failed") { handleFindJoinPaths(service, cache, request) }
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
    val payload =
        toolJson.encodeToString(
            visibleCatalog(service, cache, connectionId, refresh)
                .getOrElse {
                    return it
                }
                .map { it.toSummary() }
        )
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
    val tables =
        visibleCatalog(service, cache, connectionId, refresh).getOrElse {
            return it
        }
    val table =
        resolveTable(schemaName, tableName, tables).getOrElse {
            return it
        }
    return objectToolResult(table.toDetail(tables))
}

private suspend fun handleSearchCatalog(
    service: SafeDbService,
    cache: SchemaCache,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "query", "schema", "limit", "refresh")
        .getOrElse {
            return it
        }
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val query =
        requiredString(request, "query").getOrElse {
            return it
        }
    val schemaFilter =
        optionalString(request, "schema").getOrElse {
            return it
        }
    val limitArg =
        optionalInt(request, "limit").getOrElse {
            return it
        }
    val refresh =
        optionalBoolean(request, "refresh").getOrElse {
            return it
        } == true
    if (limitArg != null && limitArg <= 0) {
        return toolError(ERROR_INVALID_ARGUMENTS, "limit must be 1 or more")
    }
    val limit = (limitArg ?: SEARCH_CATALOG_DEFAULT_LIMIT).coerceAtMost(SEARCH_CATALOG_MAX_LIMIT)
    val matches =
        searchCatalogTables(
            visibleCatalog(service, cache, connectionId, refresh).getOrElse {
                return it
            },
            query,
            schemaFilter,
        )
    val capped = matches.take(limit)
    val result =
        CatalogSearchResult(
            query = query,
            matchCount = matches.size,
            truncated = matches.size > capped.size,
            matches = capped.map { it.toSummary() },
        )
    return objectToolResult(result)
}

private suspend fun handleFindJoinPaths(
    service: SafeDbService,
    cache: SchemaCache,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(
            request,
            "connection_id",
            "from_table",
            "to_table",
            "default_schema",
            "max_hops",
            "foreign_key_only",
            "refresh",
        )
        .getOrElse {
            return it
        }
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val fromReference =
        requiredString(request, "from_table").getOrElse {
            return it
        }
    val toReference =
        optionalString(request, "to_table").getOrElse {
            return it
        }
    val maxHopsArg =
        optionalInt(request, "max_hops").getOrElse {
            return it
        }
    val refresh =
        optionalBoolean(request, "refresh").getOrElse {
            return it
        } == true
    val foreignKeyOnly =
        optionalBoolean(request, "foreign_key_only").getOrElse {
            return it
        } == true
    if (maxHopsArg != null && (maxHopsArg < 1 || maxHopsArg > FIND_JOIN_PATHS_MAX_HOPS)) {
        return toolError(
            ERROR_INVALID_ARGUMENTS,
            "max_hops must be between 1 and $FIND_JOIN_PATHS_MAX_HOPS",
        )
    }
    val tables =
        visibleCatalog(service, cache, connectionId, refresh).getOrElse {
            return it
        }
    val settings = service.getSettings()
    val defaultSchema =
        optionalString(request, "default_schema").getOrElse {
            return it
        } ?: settingsDefaultSchema(settings, connectionId)
    val fromTable =
        resolveTableReference(fromReference, defaultSchema, tables).getOrElse {
            return it
        }
    val maxHops = (maxHopsArg ?: FIND_JOIN_PATHS_DEFAULT_HOPS)

    if (toReference == null) {
        val edges =
            findDirectJoinEdges(
                tables,
                fromTable.schema,
                fromTable.name,
                includeIndexedColumns = !foreignKeyOnly,
            )
        val result =
            JoinPathsResult(
                fromTable = fromTable.qualifiedName(),
                neighbors = edges.map { it.toStepSummary() },
            )
        return objectToolResult(result)
    }

    val toTable =
        resolveTableReference(toReference, defaultSchema, tables).getOrElse {
            return it
        }
    val paths =
        findJoinPaths(
            tables,
            fromTable.schema,
            fromTable.name,
            toTable.schema,
            toTable.name,
            maxHops = maxHops,
            includeIndexedColumns = !foreignKeyOnly,
        )
    val result =
        JoinPathsResult(
            fromTable = fromTable.qualifiedName(),
            toTable = toTable.qualifiedName(),
            paths =
                paths.map { path ->
                    JoinPathSummary(
                        hops = path.steps.size,
                        steps = path.steps.map { it.toStepSummary() },
                    )
                },
        )
    return objectToolResult(result)
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
    @SerialName("incoming_foreign_keys")
    val incomingForeignKeys: List<TableIncomingForeignKey> = emptyList(),
)

@Serializable
internal data class TableColumn(
    val name: String,
    @SerialName("data_type") val dataType: String,
    val nullable: Boolean,
    @SerialName("join_eligible") val joinEligible: Boolean = false,
)

@Serializable
internal data class TableIncomingForeignKey(
    val name: String,
    @SerialName("source_schema") val sourceSchema: String,
    @SerialName("source_table") val sourceTable: String,
    @SerialName("source_columns") val sourceColumns: List<String>,
    @SerialName("referenced_columns") val referencedColumns: List<String>,
    val cardinality: String,
    val optionality: String,
)

@Serializable
internal data class CatalogSearchResult(
    val query: String,
    @SerialName("match_count") val matchCount: Int,
    val truncated: Boolean,
    val matches: List<CatalogMatchSummary>,
)

@Serializable
internal data class CatalogMatchSummary(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    @SerialName("size_class") val sizeClass: TableSizeClass,
    @SerialName("matched_columns") val matchedColumns: List<String>,
    @SerialName("matched_indexes") val matchedIndexes: List<String>,
    @SerialName("matched_foreign_keys") val matchedForeignKeys: List<String>,
)

@Serializable
internal data class JoinPathsResult(
    @SerialName("from_table") val fromTable: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("to_table") val toTable: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val neighbors: List<JoinStepSummary>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val paths: List<JoinPathSummary>? = null,
)

@Serializable
internal data class JoinPathSummary(
    val hops: Int,
    val steps: List<JoinStepSummary>,
)

@Serializable
internal data class JoinStepSummary(
    @SerialName("from_schema") val fromSchema: String,
    @SerialName("from_table") val fromTable: String,
    @SerialName("to_schema") val toSchema: String,
    @SerialName("to_table") val toTable: String,
    val kind: String,
    val columns: List<JoinColumnPairSummary>,
    @SerialName("on_clause") val onClause: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("foreign_key_name")
    val foreignKeyName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cardinality: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val optionality: String? = null,
)

@Serializable
internal data class JoinColumnPairSummary(
    @SerialName("left_column") val leftColumn: String,
    @SerialName("right_column") val rightColumn: String,
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

private suspend fun visibleCatalog(
    service: SafeDbService,
    cache: SchemaCache,
    connectionId: String,
    refresh: Boolean,
): Arg<List<TableInfo>> {
    val connection =
        service.listConnections().find { it.id == connectionId }
            ?: return Arg.Err(toolError(ERROR_NOT_FOUND, "Connection not found"))
    val tables = cache.get(connectionId, refresh).tables
    return Arg.Ok(visibleTables(tables, service.getSettings().blockedSchemas, connection.dialect))
}

private fun TableInfo.toSummary(): TableSummary =
    TableSummary(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        sizeClass = tableSize.sizeClass,
        columnCount = columns.size,
    )

private fun TableInfo.toDetail(tables: List<TableInfo>): TableDetail =
    TableDetail(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        columns =
            columns.map {
                TableColumn(
                    name = it.name,
                    dataType = it.dataType,
                    nullable = it.nullable,
                    joinEligible = it.joinEligible,
                )
            },
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
        incomingForeignKeys =
            incomingForeignKeysFor(tables, schema, name).map {
                TableIncomingForeignKey(
                    name = it.name,
                    sourceSchema = it.sourceSchema,
                    sourceTable = it.sourceTable,
                    sourceColumns = it.sourceColumns,
                    referencedColumns = it.referencedColumns,
                    cardinality = it.cardinality.name,
                    optionality = it.optionality.name,
                )
            },
    )

private fun com.safedb.schema.CatalogTableMatch.toSummary(): CatalogMatchSummary =
    CatalogMatchSummary(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName,
        sizeClass = sizeClass,
        matchedColumns = matchedColumns,
        matchedIndexes = matchedIndexes,
        matchedForeignKeys = matchedForeignKeys,
    )

private fun JoinEdge.toStepSummary(): JoinStepSummary =
    JoinStepSummary(
        fromSchema = fromSchema,
        fromTable = fromTable,
        toSchema = toSchema,
        toTable = toTable,
        kind =
            when (kind) {
                com.safedb.schema.JoinCandidateKind.ForeignKey -> "foreign_key"
                com.safedb.schema.JoinCandidateKind.IndexedColumns -> "indexed_columns"
            },
        columns =
            columns.map {
                JoinColumnPairSummary(leftColumn = it.leftColumn, rightColumn = it.rightColumn)
            },
        onClause =
            columns.joinToString(separator = " AND ") {
                "$fromSchema.$fromTable.${it.leftColumn} = " +
                    "$toSchema.$toTable.${it.rightColumn}"
            },
        foreignKeyName = foreignKeyName,
        cardinality = cardinality?.name,
        optionality = optionality?.name,
    )
