package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.ENV_BACKEND
import com.safedb.secrets.RequestedBackend
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbService
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val MCP_VERSION_RESOURCE = "mcp-version.txt"

private object McpVersionLoader

internal val toolJson = Json { encodeDefaults = true }

internal const val ERROR_INVALID_ARGUMENTS = "invalid_arguments"
internal const val ERROR_NOT_FOUND = "not_found"
internal const val ERROR_PARSE = "parse"
internal const val ERROR_VALIDATION = "validation"
internal const val ERROR_COMPILATION = "compilation"
internal const val ERROR_EXECUTION = "execution"
internal const val ERROR_RISK_GATE = "risk_gate"
internal const val ERROR_CONFIRMATION_REQUIRED = "confirmation_required"
internal const val ERROR_INTERNAL = "internal"

internal const val MCP_SERVER_INSTRUCTIONS =
    "Add connections with the safe-db-mcp CLI, not tools. Start with list_connections; " +
        "pass id as connection_id. For unqualified SQL, pass default_schema from list_tables " +
        "(on MySQL often the connection database) or qualify as schema.table. Page results " +
        "with result_id. delete_connection returns confirmation_required; show the user, then " +
        "retry with the returned confirmation object. Never send passwords."

@Serializable
internal data class McpToolError(
    val error: String,
    val message: String,
    val warnings: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val risk: QueryRiskSummary? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reasons: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val confirmation: JsonObject? = null,
)

internal fun toolError(
    code: String,
    message: String,
    warnings: List<String> = emptyList(),
    risk: QueryRiskSummary? = null,
    reasons: List<String>? = null,
    confirmation: JsonObject? = null,
): CallToolResult =
    CallToolResult(
        content =
            listOf(
                TextContent(
                    text =
                        toolJson.encodeToString(
                            McpToolError(
                                error = code,
                                message = message,
                                warnings = warnings,
                                risk = risk,
                                reasons = reasons,
                                confirmation = confirmation,
                            )
                        )
                )
            ),
        isError = true,
    )

internal fun requiredText(request: CallToolRequest, name: String): String? =
    optionalText(request, name)

internal fun optionalText(request: CallToolRequest, name: String): String? =
    request.arguments?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun optionalInt(request: CallToolRequest, name: String): Int? {
    val element = request.arguments?.get(name) ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    primitive.intOrNull?.let {
        return it
    }
    return primitive.content.trim().toIntOrNull()
}

internal fun mcpVersion(): String {
    val stream =
        McpVersionLoader::class.java.getResourceAsStream("/$MCP_VERSION_RESOURCE")
            ?: error("missing /$MCP_VERSION_RESOURCE")
    return stream.use { it.readBytes().toString(Charsets.UTF_8).trim() }
}

internal fun initMcpSecrets(
    platform: DesktopPlatform,
    dataDir: Path,
    envValue: String? = System.getenv(ENV_BACKEND),
) {
    when (SecretsManager.parseRequestedBackendFrom(envValue)) {
        RequestedBackend.Disabled -> SecretsManager.initStore("disabled", platform)
        RequestedBackend.Auto,
        RequestedBackend.Protected ->
            when (platform) {
                // Windows shares the desktop Credential Manager; Mac/Linux MCP keeps passwords
                // in dataDir and does not open Keychain.
                DesktopPlatform.Windows -> SecretsManager.initStore(envValue, platform)
                DesktopPlatform.MacOs,
                DesktopPlatform.Linux ->
                    SecretsManager.initFileStore(dataDir.resolve("credentials"))
            }
    }
}

internal fun createMcpRuntime(
    dataDir: Path,
    platform: DesktopPlatform = DesktopPlatform.current(),
    envValue: String? = System.getenv(ENV_BACKEND),
): SafeDbService {
    initMcpSecrets(platform, dataDir, envValue)
    return createMcpService(dataDir)
}

internal fun createMcpService(dataDir: Path): SafeDbService =
    SafeDbServiceImpl(
        configStore = ConfigStore.new(dataDir),
        queryStore = QueryStore.new(dataDir),
        settingsStore = SettingsStore.new(dataDir),
        recipeStore = RecipeStore.new(dataDir),
    )

internal fun createSafeDbMcpServer(
    service: SafeDbService,
    nowMs: () -> Long = { System.currentTimeMillis() },
    schemaCacheTtlMs: Long = SCHEMA_CACHE_TTL_MS,
    resultsDir: Path,
    resultStoreTtlMs: Long = RESULT_STORE_TTL_MS,
    resultStoreMaxEntries: Int = RESULT_STORE_MAX_ENTRIES,
    resultStoreMaxBytes: Long = RESULT_STORE_MAX_BYTES,
    resultStore: ResultStore? = null,
): Server {
    val schemaCache =
        SchemaCache(load = service::getSchema, nowMs = nowMs, ttlMs = schemaCacheTtlMs)
    val store =
        resultStore
            ?: ResultStore(
                resultsDir = resultsDir,
                nowMs = nowMs,
                ttlMs = resultStoreTtlMs,
                maxEntries = resultStoreMaxEntries,
                maxBytes = resultStoreMaxBytes,
            )
    val server =
        Server(
            serverInfo = Implementation(name = "safe-db", version = mcpVersion()),
            options =
                ServerOptions(
                    capabilities =
                        ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
                ),
            instructions = MCP_SERVER_INSTRUCTIONS,
        )
    registerConnectionTools(server, service, schemaCache)
    registerSchemaTools(server, service, schemaCache)
    registerQueryTools(server, service, store)
    return server
}

internal fun registerConnectionTools(
    server: Server,
    service: SafeDbService,
    schemaCache: SchemaCache,
) {
    val deleteTokens = DeleteConfirmationTokens()
    server.addTool(
        name = "list_connections",
        description =
            "List saved database connections as id, name, dialect, and database. " +
                "Passwords and connection URLs are not returned. Add connections with " +
                "`safe-db-mcp setup` or `safe-db-mcp connections add`; do not pass a password " +
                "or URL to any tool.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) {
        try {
            val payload = toolJson.encodeToString(service.listConnections().map { it.toSummary() })
            CallToolResult(content = listOf(TextContent(text = payload)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toolError(ERROR_INTERNAL, error.message ?: "list_connections failed")
        }
    }

    server.addTool(
        name = "delete_connection",
        description =
            "Delete a saved connection by id from list_connections. First call returns " +
                "confirmation_required; show the reasons to the user, then retry with the " +
                "returned confirmation object. Do not auto-confirm. Do not invent the object. " +
                "Does not accept a password. Add connections with `safe-db-mcp setup` or " +
                "`safe-db-mcp connections add`.",
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
                            "confirmation",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Echo the confirmation object from a confirmation_required " +
                                        "error after showing the reasons to the user. Do not " +
                                        "invent this object.",
                                )
                            },
                        )
                    },
                required = listOf("connection_id"),
            ),
        toolAnnotations = ToolAnnotations(destructiveHint = true),
    ) { request ->
        handleDeleteConnection(service, schemaCache, deleteTokens, request)
    }
}

@Serializable
internal data class ConnectionSummary(
    val id: String,
    val name: String,
    val dialect: Dialect,
    val database: String,
)

@Serializable
internal data class McpDeleteConfirmation(
    @SerialName("connection_id") val connectionId: String,
    val token: String,
)

@Serializable private data class DeletedConnection(val deleted: String)

private fun ConnectionDef.toSummary(): ConnectionSummary =
    ConnectionSummary(id = id, name = name, dialect = dialect, database = database)

private suspend fun handleDeleteConnection(
    service: SafeDbService,
    schemaCache: SchemaCache,
    tokens: DeleteConfirmationTokens,
    request: CallToolRequest,
): CallToolResult {
    val id =
        requiredText(request, "connection_id")
            ?: return toolError(ERROR_INVALID_ARGUMENTS, "connection_id is required")
    val connection =
        service.listConnections().find { it.id == id }
            ?: return toolError(ERROR_NOT_FOUND, "Connection not found")
    when (val parsed = parseDeleteConfirmation(request)) {
        DeleteConfirmationParse.Missing -> return deleteConfirmationRequired(connection, tokens)
        is DeleteConfirmationParse.Invalid -> return parsed.result
        is DeleteConfirmationParse.Ready -> {
            if (
                parsed.confirmation.connectionId != id ||
                    !tokens.matches(id, parsed.confirmation.token)
            ) {
                return deleteConfirmationRequired(connection, tokens)
            }
        }
    }
    return try {
        service.deleteConnection(id)
        tokens.consume(id)
        schemaCache.invalidate(id)
        CallToolResult(
            content = listOf(TextContent(text = toolJson.encodeToString(DeletedConnection(id))))
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        toolError(ERROR_INTERNAL, error.message ?: "delete failed")
    }
}

private fun deleteConfirmationRequired(
    connection: ConnectionDef,
    tokens: DeleteConfirmationTokens,
): CallToolResult {
    val confirmation = tokens.issue(connection.id)
    return toolError(
        ERROR_CONFIRMATION_REQUIRED,
        "Deleting connection '${connection.name}' (${connection.dialect}, ${connection.database}) " +
            "removes it from this MCP store. Show the user, then retry with the returned " +
            "confirmation object; do not auto-confirm.",
        reasons =
            listOf(
                "This deletes saved connection ${connection.name} (${connection.dialect} / ${connection.database})."
            ),
        confirmation = toolJson.encodeToJsonElement(confirmation).jsonObject,
    )
}

private fun parseDeleteConfirmation(request: CallToolRequest): DeleteConfirmationParse {
    val raw =
        request.arguments?.get("confirmation").takeUnless { it == null || it is JsonNull }
            ?: return DeleteConfirmationParse.Missing
    val obj =
        raw as? JsonObject
            ?: return DeleteConfirmationParse.Invalid(
                toolError(ERROR_PARSE, "confirmation is invalid")
            )
    return try {
        DeleteConfirmationParse.Ready(
            toolJson.decodeFromJsonElement(McpDeleteConfirmation.serializer(), obj)
        )
    } catch (_: SerializationException) {
        DeleteConfirmationParse.Invalid(toolError(ERROR_PARSE, "confirmation is invalid"))
    }
}

private sealed interface DeleteConfirmationParse {
    data object Missing : DeleteConfirmationParse

    data class Ready(val confirmation: McpDeleteConfirmation) : DeleteConfirmationParse

    data class Invalid(val result: CallToolResult) : DeleteConfirmationParse
}

private class DeleteConfirmationTokens {
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(connectionId: String): McpDeleteConfirmation {
        val token = UUID.randomUUID().toString()
        tokens[connectionId] = token
        return McpDeleteConfirmation(connectionId, token)
    }

    fun matches(connectionId: String, token: String): Boolean = tokens[connectionId] == token

    fun consume(connectionId: String) {
        tokens.remove(connectionId)
    }
}
