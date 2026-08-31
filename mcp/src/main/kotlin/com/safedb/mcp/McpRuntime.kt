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
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val MCP_VERSION_RESOURCE = "mcp-version.txt"

private object McpVersionLoader

private val toolJson = Json { encodeDefaults = true }

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

internal fun createSafeDbMcpServer(service: SafeDbService): Server {
    val server =
        Server(
            serverInfo = Implementation(name = "safe-db", version = mcpVersion()),
            options =
                ServerOptions(
                    capabilities =
                        ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                ),
        )
    registerConnectionTools(server, service)
    return server
}

internal fun registerConnectionTools(server: Server, service: SafeDbService) {
    server.addTool(
        name = "list_connections",
        description =
            "List saved database connections as id, name, dialect, and database. " +
                "Passwords and connection URLs are not returned. Add connections with " +
                "`safe-db-mcp setup` or `safe-db-mcp connections add`; do not pass a password " +
                "or URL to any tool.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) {
        val payload = toolJson.encodeToString(service.listConnections().map { it.toSummary() })
        CallToolResult(content = listOf(TextContent(text = payload)))
    }

    server.addTool(
        name = "delete_connection",
        description =
            "Delete a saved connection by id from list_connections. Does not accept a password. " +
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
                    },
                required = listOf("connection_id"),
            ),
    ) { request ->
        val id = request.arguments?.get("connection_id")?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrEmpty()) {
            CallToolResult(
                content = listOf(TextContent(text = "connection_id is required")),
                isError = true,
            )
        } else if (service.listConnections().none { it.id == id }) {
            CallToolResult(
                content = listOf(TextContent(text = "Connection not found")),
                isError = true,
            )
        } else {
            try {
                service.deleteConnection(id)
                CallToolResult(
                    content =
                        listOf(TextContent(text = toolJson.encodeToString(DeletedConnection(id))))
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = error.message ?: "delete failed")),
                    isError = true,
                )
            }
        }
    }
}

@Serializable
internal data class ConnectionSummary(
    val id: String,
    val name: String,
    val dialect: Dialect,
    val database: String,
)

@Serializable private data class DeletedConnection(val deleted: String)

private fun ConnectionDef.toSummary(): ConnectionSummary =
    ConnectionSummary(id = id, name = name, dialect = dialect, database = database)
