package com.safedb.mcp

import com.safedb.service.SafeDbService
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.nio.file.Path

internal const val MCP_VERSION_RESOURCE = "mcp-version.txt"

private object McpVersionLoader

internal fun mcpVersion(): String {
    val stream =
        McpVersionLoader::class.java.getResourceAsStream("/$MCP_VERSION_RESOURCE")
            ?: error("missing /$MCP_VERSION_RESOURCE")
    return stream.use { it.readBytes().toString(Charsets.UTF_8).trim() }
}

internal fun createMcpService(dataDir: Path): SafeDbService =
    SafeDbServiceImpl(
        configStore = ConfigStore.new(dataDir),
        queryStore = QueryStore.new(dataDir),
        settingsStore = SettingsStore.new(dataDir),
        recipeStore = RecipeStore.new(dataDir),
    )

internal fun createSafeDbMcpServer(@Suppress("UNUSED_PARAMETER") service: SafeDbService): Server =
    Server(
        serverInfo = Implementation(name = "safe-db", version = mcpVersion()),
        options =
            ServerOptions(
                capabilities =
                    ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
            ),
    )
