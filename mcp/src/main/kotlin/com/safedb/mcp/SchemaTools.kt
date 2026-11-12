package com.safedb.mcp

import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName
import com.safedb.schema.findDirectJoinEdges
import com.safedb.schema.findJoinPaths
import com.safedb.schema.searchCatalogTables
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
                "table. Do not expect the full catalog in one payload. The result is an object " +
                "{tables: [...]} (not a top-level array); it includes JSON text and " +
                "structuredContent with the same object. " +
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
    val tables =
        visibleCatalog(service, cache, connectionId, refresh).getOrElse {
            return it
        }
    return objectToolResult(TableListResult(tables.map { it.toSummary() }))
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
