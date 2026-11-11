package com.safedb.mcp

import com.safedb.adapter.ConnectionFailedException
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.displayName
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
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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
internal const val ERROR_CONNECTION = "connection"
internal const val ERROR_INTERNAL = "internal"

internal const val MCP_SERVER_INSTRUCTIONS =
    "Add connections with the safe-db-mcp CLI, not tools. Start with list_connections; " +
        "pass id as connection_id. For unqualified SQL, pass default_schema from " +
        "list_tables.schema (MySQL: usually the database name) or qualify as schema.table. " +
        "Page results " +
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

internal sealed interface Arg<out T> {
    data class Ok<T>(val value: T) : Arg<T>

    data class Err(val result: CallToolResult) : Arg<Nothing>
}

internal inline fun <T> Arg<T>.getOrElse(onErr: (CallToolResult) -> Nothing): T =
    when (this) {
        is Arg.Ok -> value
        is Arg.Err -> onErr(result)
    }

internal fun requireKnownArguments(request: CallToolRequest, vararg allowed: String): Arg<Unit> {
    val extra = request.arguments?.keys.orEmpty().filter { it !in allowed }.sorted()
    if (extra.isEmpty()) return Arg.Ok(Unit)
    return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "unknown argument '${extra.first()}'"))
}

internal fun requiredString(request: CallToolRequest, name: String): Arg<String> {
    val element = request.arguments?.get(name)
    if (element == null || element is JsonNull) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name is required"))
    }
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be a string"))
    }
    val text = primitive.content.trim()
    if (text.isEmpty()) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name is required"))
    }
    return Arg.Ok(text)
}

internal fun optionalString(request: CallToolRequest, name: String): Arg<String?> {
    val element = request.arguments?.get(name) ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be a string"))
    }
    return Arg.Ok(primitive.content.trim().takeIf { it.isNotEmpty() })
}

internal fun optionalBoolean(request: CallToolRequest, name: String): Arg<Boolean?> {
    val element = request.arguments?.get(name) ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.booleanOrNull
    if (value == null) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be a boolean"))
    }
    return Arg.Ok(value)
}

internal fun optionalInt(request: CallToolRequest, name: String): Arg<Int?> {
    val element = request.arguments?.get(name) ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.intOrNull
    if (value == null) {
        return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be an integer"))
    }
    return Arg.Ok(value)
}

internal fun optionalObject(request: CallToolRequest, name: String): Arg<JsonObject?> {
    val element = request.arguments?.get(name) ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val obj =
        element as? JsonObject
            ?: return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be an object"))
    return Arg.Ok(obj)
}

internal fun optionalArray(request: CallToolRequest, name: String): Arg<JsonArray?> {
    val element = request.arguments?.get(name) ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val array =
        element as? JsonArray
            ?: return Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "$name must be an array"))
    return Arg.Ok(array)
}

internal sealed interface DestructiveConfirmationParse<out T> {
    data object Missing : DestructiveConfirmationParse<Nothing>

    data class Ready<T>(val confirmation: T) : DestructiveConfirmationParse<T>

    data class Invalid(val result: CallToolResult) : DestructiveConfirmationParse<Nothing>
}

internal fun <T> parseDestructiveConfirmation(
    request: CallToolRequest,
    deserializer: DeserializationStrategy<T>,
): DestructiveConfirmationParse<T> {
    val obj =
        when (val parsed = optionalObject(request, "confirmation")) {
            is Arg.Err -> return DestructiveConfirmationParse.Invalid(parsed.result)
            is Arg.Ok -> parsed.value ?: return DestructiveConfirmationParse.Missing
        }
    return try {
        DestructiveConfirmationParse.Ready(toolJson.decodeFromJsonElement(deserializer, obj))
    } catch (_: SerializationException) {
        DestructiveConfirmationParse.Invalid(
            toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid")
        )
    }
}

internal class IssuedTokens {
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(id: String): String {
        val token = UUID.randomUUID().toString()
        tokens[id] = token
        return token
    }

    fun matches(id: String, token: String): Boolean = tokens[id] == token

    fun consume(id: String) {
        tokens.remove(id)
    }
}

internal suspend fun runMcpTool(
    fallback: String,
    block: suspend () -> CallToolResult,
): CallToolResult =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConnectionFailedException) {
        toolError(ERROR_CONNECTION, error.message ?: fallback)
    } catch (error: Exception) {
        toolError(ERROR_INTERNAL, error.message ?: fallback)
    }

internal suspend fun Server.createSafeDbSession(transport: Transport): ServerSession {
    val session = createSession(transport)
    installSafeDbToolDispatch(session)
    return session
}

internal fun Server.installSafeDbToolDispatch(session: ServerSession) {
    session.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
        val registered = tools[request.name]
        if (registered == null) {
            toolError(ERROR_NOT_FOUND, "Unknown tool '${request.name}'")
        } else {
            registered.handler(clientConnection(session.sessionId), request)
        }
    }
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
                        ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = false),
                            resources =
                                ServerCapabilities.Resources(
                                    subscribe = false,
                                    listChanged = false,
                                ),
                            prompts = ServerCapabilities.Prompts(listChanged = false),
                        )
                ),
            instructions = MCP_SERVER_INSTRUCTIONS,
        )
    registerConnectionTools(server, service, schemaCache)
    registerSchemaTools(server, service, schemaCache)
    registerQueryTools(server, service, store)
    registerExploreTools(server, store)
    registerRecipeTools(server, service, store)
    registerWorkspaceTools(server, service, store, nowMs)
    registerExportTools(server, store, resultsDir)
    registerMcpResourcesAndPrompts(server, service, schemaCache, store)
    return server
}

internal fun registerConnectionTools(
    server: Server,
    service: SafeDbService,
    schemaCache: SchemaCache,
) {
    val deleteTokens = IssuedTokens()
    server.addTool(
        name = "list_connections",
        description =
            "List saved database connections as id, name, dialect, and database. " +
                "Passwords and connection URLs are not returned. Add connections with " +
                "`safe-db-mcp setup` or `safe-db-mcp connections add`; do not pass a password " +
                "or URL to any tool.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("list_connections failed") {
            requireKnownArguments(request).getOrElse {
                return@runMcpTool it
            }
            val payload = toolJson.encodeToString(service.listConnections().map { it.toSummary() })
            CallToolResult(content = listOf(TextContent(text = payload)))
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

internal fun ConnectionDef.toSummary(): ConnectionSummary =
    ConnectionSummary(id = id, name = name, dialect = dialect, database = database)

private suspend fun handleDeleteConnection(
    service: SafeDbService,
    schemaCache: SchemaCache,
    tokens: IssuedTokens,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "confirmation").getOrElse {
        return it
    }
    val id =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val connection =
        service.listConnections().find { it.id == id }
            ?: return toolError(ERROR_NOT_FOUND, "Connection not found")
    when (val parsed = parseDestructiveConfirmation(request, McpDeleteConfirmation.serializer())) {
        DestructiveConfirmationParse.Missing ->
            return deleteConfirmationRequired(connection, tokens)
        is DestructiveConfirmationParse.Invalid -> return parsed.result
        is DestructiveConfirmationParse.Ready -> {
            if (
                parsed.confirmation.connectionId != id ||
                    !tokens.matches(id, parsed.confirmation.token)
            ) {
                return deleteConfirmationRequired(connection, tokens)
            }
        }
    }
    return runMcpTool("delete failed") {
        service.deleteConnection(id)
        tokens.consume(id)
        schemaCache.invalidate(id)
        CallToolResult(
            content = listOf(TextContent(text = toolJson.encodeToString(DeletedConnection(id))))
        )
    }
}

private fun deleteConfirmationRequired(
    connection: ConnectionDef,
    tokens: IssuedTokens,
): CallToolResult {
    val confirmation = McpDeleteConfirmation(connection.id, tokens.issue(connection.id))
    return toolError(
        ERROR_CONFIRMATION_REQUIRED,
        "Deleting connection '${connection.name}' (${connection.dialect.displayName()}, ${connection.database}) " +
            "removes it from this MCP store. Show the user, then retry with the returned " +
            "confirmation object; do not auto-confirm.",
        reasons =
            listOf(
                "This deletes saved connection ${connection.name} (${connection.dialect.displayName()} / ${connection.database})."
            ),
        confirmation = toolJson.encodeToJsonElement(confirmation).jsonObject,
    )
}
