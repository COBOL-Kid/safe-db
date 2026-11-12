package com.safedb.mcp

import com.safedb.model.QueryRiskGate
import com.safedb.model.Settings
import com.safedb.model.normalizeSettings
import com.safedb.service.SafeDbService

internal fun parseSettingsCommand(args: Array<String>): McpCommand {
    if (args.size == 1 || (args.size == 2 && args[1] == "show")) return McpCommand.SettingsShow
    return when (args[1]) {
        "risk-gate" -> {
            if (args.size != 3) throw McpCliUsageException()
            McpCommand.SettingsRiskGate(parseRiskGate(args[2]))
        }
        "default-connection" -> parseDefaultConnection(args)
        "clear-default-connection" -> {
            if (args.size != 2) throw McpCliUsageException()
            McpCommand.SettingsClearDefaultConnection
        }
        "block-schema" -> McpCommand.SettingsBlockSchema(parseSchemaArg(args))
        "unblock-schema" -> McpCommand.SettingsUnblockSchema(parseSchemaArg(args))
        else -> throw McpCliUsageException()
    }
}

private fun parseSchemaArg(args: Array<String>): String {
    if (args.size != 3) throw McpCliUsageException()
    return normalizeSchema(args[2])
}

private fun normalizeSchema(raw: String): String {
    if (raw.isBlank() || raw.startsWith("-")) throw McpCliUsageException()
    return raw.trim().lowercase()
}

private fun parseDefaultConnection(args: Array<String>): McpCommand.SettingsDefaultConnection {
    if (args.size < 3 || args[2].startsWith("-")) throw McpCliUsageException()
    var defaultSchema: String? = null
    var index = 3
    val flags = args.toList()
    while (index < args.size) {
        val (nameAndValue, consumed) = splitFlag(flags, index)
        val (flag, value) = nameAndValue
        when (flag) {
            "--default-schema" -> defaultSchema = value
            else -> throw McpCliUsageException()
        }
        index += consumed
    }
    return McpCommand.SettingsDefaultConnection(args[2], defaultSchema)
}

private fun parseRiskGate(raw: String): QueryRiskGate =
    when (raw.trim().lowercase()) {
        "cautious" -> QueryRiskGate.Cautious
        "standard" -> QueryRiskGate.Standard
        "flexible" -> QueryRiskGate.Flexible
        "disabled" -> QueryRiskGate.Disabled
        else ->
            throw McpCliUsageException(
                "Unknown risk gate '$raw'; expected cautious, standard, flexible, or disabled"
            )
    }

internal suspend fun showSettings(service: SafeDbService, io: CliIo): Int {
    val settings = normalizeSettings(service.getSettings())
    io.print("query_risk_gate: ${settings.queryRiskGate}")
    io.print("default_connection_id: ${settings.defaultConnectionId ?: "(none)"}")
    io.print("default_schema: ${settings.defaultSchema ?: "(none)"}")
    io.print("blocked_schemas: ${blockedSchemasText(settings.blockedSchemas)}")
    return 0
}

internal suspend fun setRiskGate(gate: QueryRiskGate, service: SafeDbService, io: CliIo): Int {
    val updated = updateSettings(service) { it.copy(queryRiskGate = gate) }
    io.print("query_risk_gate: ${updated.queryRiskGate}")
    return 0
}

internal suspend fun setDefaultConnection(
    connectionId: String,
    defaultSchema: String?,
    service: SafeDbService,
    io: CliIo,
): Int {
    if (requireConnection(service, connectionId, io) == null) return 1
    val updated =
        updateSettings(service) {
            it.copy(
                defaultConnectionId = connectionId,
                defaultSchema = defaultSchema,
            )
        }
    io.print("default_connection_id: ${updated.defaultConnectionId}")
    io.print("default_schema: ${updated.defaultSchema ?: "(none)"}")
    return 0
}

internal suspend fun clearDefaultConnection(service: SafeDbService, io: CliIo): Int {
    updateSettings(service) { it.copy(defaultConnectionId = null, defaultSchema = null) }
    io.print("Cleared default connection")
    return 0
}

internal suspend fun blockSchema(schema: String, service: SafeDbService, io: CliIo): Int {
    val normalized = normalizeSchema(schema)
    val updated =
        updateSettings(service) { it.copy(blockedSchemas = it.blockedSchemas + normalized) }
    io.print("blocked_schemas: ${blockedSchemasText(updated.blockedSchemas)}")
    return 0
}

internal suspend fun unblockSchema(schema: String, service: SafeDbService, io: CliIo): Int {
    val normalized = normalizeSchema(schema)
    val updated =
        updateSettings(service) { it.copy(blockedSchemas = it.blockedSchemas - normalized) }
    io.print("blocked_schemas: ${blockedSchemasText(updated.blockedSchemas)}")
    return 0
}

private suspend fun updateSettings(
    service: SafeDbService,
    transform: (Settings) -> Settings,
): Settings {
    val updated = normalizeSettings(transform(normalizeSettings(service.getSettings())))
    service.saveSettings(updated)
    return updated
}

private fun blockedSchemasText(schemas: List<String>): String =
    if (schemas.isEmpty()) "(none)" else schemas.joinToString(", ")
