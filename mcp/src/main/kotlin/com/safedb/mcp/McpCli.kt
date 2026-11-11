package com.safedb.mcp

import com.safedb.connection.DIALECTS
import com.safedb.connection.inferLocation
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryRiskGate
import com.safedb.model.Settings
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.normalizeSettings
import com.safedb.secrets.PasswordFile
import com.safedb.secrets.PasswordFileException
import com.safedb.service.SafeDbService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal const val MCP_USAGE =
    """Usage: safe-db-mcp
       safe-db-mcp setup
       safe-db-mcp connections add [options]
       safe-db-mcp connections list
       safe-db-mcp connections delete <id>
       safe-db-mcp connections test [<id>]
       safe-db-mcp settings [show]
       safe-db-mcp settings risk-gate <cautious|standard|flexible|disabled>
       safe-db-mcp settings default-connection <id> [--default-schema <schema>]
       safe-db-mcp settings clear-default-connection
       safe-db-mcp settings block-schema <schema>
       safe-db-mcp settings unblock-schema <schema>
       safe-db-mcp recipes list
       safe-db-mcp recipes import /absolute/path/recipe.json
       safe-db-mcp recipes export <recipe_id>

Add a connection interactively or with flags. Passwords are never flags; use a prompt
or --password-file /absolute/path.

Example:
  safe-db-mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path

Options:
  --name NAME
  --dialect postgres|mysql|mssql|oracle|db2
  --host HOST
  --port PORT
  --database NAME
  --username NAME
  --password-file /absolute/path
  --transport disabled|encrypt-only|verify-ca|verify-identity
  --oracle-wallet /absolute/path"""

internal class McpCliUsageException(message: String = MCP_USAGE) : Exception(message)

internal sealed interface McpCommand {
    data object Stdio : McpCommand

    data object Help : McpCommand

    data object ConnectionsList : McpCommand

    data class ConnectionsDelete(val id: String) : McpCommand

    data class ConnectionsAdd(val flags: ConnectionAddFlags) : McpCommand

    data class ConnectionsTest(val id: String? = null) : McpCommand

    data object SettingsShow : McpCommand

    data class SettingsRiskGate(val gate: QueryRiskGate) : McpCommand

    data class SettingsDefaultConnection(
        val connectionId: String,
        val defaultSchema: String? = null,
    ) : McpCommand

    data object SettingsClearDefaultConnection : McpCommand

    data class SettingsBlockSchema(val schema: String) : McpCommand

    data class SettingsUnblockSchema(val schema: String) : McpCommand

    data object RecipesList : McpCommand

    data class RecipesImport(val path: String) : McpCommand

    data class RecipesExport(val id: String) : McpCommand
}

internal data class ConnectionAddFlags(
    val name: String? = null,
    val dialect: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val database: String? = null,
    val username: String? = null,
    val passwordFile: String? = null,
    val transport: String? = null,
    val oracleWallet: String? = null,
)

internal interface CliIo {
    fun print(line: String)

    fun printErr(line: String)

    fun readLine(prompt: String): String?

    fun readPassword(prompt: String): String?
}

internal object SystemCliIo : CliIo {
    override fun print(line: String) = println(line)

    override fun printErr(line: String) = System.err.println(line)

    override fun readLine(prompt: String): String? {
        print(prompt)
        return readlnOrNull()
    }

    override fun readPassword(prompt: String): String? {
        val console = System.console()
        if (console != null) {
            return console.readPassword(prompt)?.concatToString()
        }
        print(prompt)
        return readlnOrNull()
    }
}

internal fun parseMcpArgs(args: Array<String>): McpCommand {
    if (args.isEmpty()) return McpCommand.Stdio
    if (args.size == 1 && (args[0] == "--help" || args[0] == "-h" || args[0] == "help")) {
        return McpCommand.Help
    }
    return when (args[0]) {
        "setup" -> McpCommand.ConnectionsAdd(parseAddFlags(args.drop(1)))
        "connections" -> {
            if (args.size < 2) throw McpCliUsageException()
            when (args[1]) {
                "add" -> McpCommand.ConnectionsAdd(parseAddFlags(args.drop(2)))
                "list" -> {
                    if (args.size != 2) throw McpCliUsageException()
                    McpCommand.ConnectionsList
                }
                "delete" -> {
                    if (args.size != 3 || args[2].startsWith("-")) throw McpCliUsageException()
                    McpCommand.ConnectionsDelete(args[2])
                }
                "test" -> parseConnectionsTest(args)
                else -> throw McpCliUsageException()
            }
        }
        "settings" -> parseSettingsCommand(args)
        "recipes" -> parseRecipesCommand(args)
        else -> throw McpCliUsageException()
    }
}

private fun parseRecipesCommand(args: Array<String>): McpCommand {
    if (args.size < 2) throw McpCliUsageException()
    return when (args[1]) {
        "list" -> {
            if (args.size != 2) throw McpCliUsageException()
            McpCommand.RecipesList
        }
        "import" -> {
            if (args.size != 3 || args[2].startsWith("-")) throw McpCliUsageException()
            McpCommand.RecipesImport(args[2])
        }
        "export" -> {
            if (args.size != 3 || args[2].startsWith("-")) throw McpCliUsageException()
            McpCommand.RecipesExport(args[2])
        }
        else -> throw McpCliUsageException()
    }
}

private fun parseConnectionsTest(args: Array<String>): McpCommand =
    when {
        args.size == 2 -> McpCommand.ConnectionsTest(null)
        args.size == 3 && !args[2].startsWith("-") -> McpCommand.ConnectionsTest(args[2])
        else -> throw McpCliUsageException()
    }

private fun parseSettingsCommand(args: Array<String>): McpCommand {
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
        "block-schema" -> {
            if (args.size != 3 || args[2].isBlank() || args[2].startsWith("-")) {
                throw McpCliUsageException()
            }
            McpCommand.SettingsBlockSchema(args[2])
        }
        "unblock-schema" -> {
            if (args.size != 3 || args[2].isBlank() || args[2].startsWith("-")) {
                throw McpCliUsageException()
            }
            McpCommand.SettingsUnblockSchema(args[2])
        }
        else -> throw McpCliUsageException()
    }
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

internal suspend fun executeMcpCommand(
    command: McpCommand,
    service: SafeDbService,
    io: CliIo,
    tty: Boolean,
): Int =
    try {
        when (command) {
            McpCommand.Stdio,
            McpCommand.Help -> error("stdio and help are handled before executeMcpCommand")
            McpCommand.ConnectionsList -> {
                val connections = service.listConnections()
                if (connections.isEmpty()) {
                    io.print("No connections.")
                } else {
                    connections.forEach { def ->
                        io.print("${def.id}  ${def.name}  ${def.dialect}  ${def.database}")
                    }
                }
                0
            }
            is McpCommand.ConnectionsDelete -> {
                if (service.listConnections().none { it.id == command.id }) {
                    io.printErr("safe-db-mcp: connection not found: ${command.id}")
                    1
                } else {
                    service.deleteConnection(command.id)
                    io.print("Deleted ${command.id}")
                    0
                }
            }
            is McpCommand.ConnectionsAdd -> addConnection(command.flags, service, io, tty)
            is McpCommand.ConnectionsTest -> testConnections(command.id, service, io)
            McpCommand.SettingsShow -> {
                val settings = normalizeSettings(service.getSettings())
                io.print("query_risk_gate: ${settings.queryRiskGate}")
                io.print("default_connection_id: ${settings.defaultConnectionId ?: "(none)"}")
                io.print("default_schema: ${settings.defaultSchema ?: "(none)"}")
                io.print("blocked_schemas: ${blockedSchemasText(settings.blockedSchemas)}")
                0
            }
            is McpCommand.SettingsRiskGate -> {
                val updated = updateSettings(service) { it.copy(queryRiskGate = command.gate) }
                io.print("query_risk_gate: ${updated.queryRiskGate}")
                0
            }
            is McpCommand.SettingsDefaultConnection -> {
                if (service.listConnections().none { it.id == command.connectionId }) {
                    io.printErr("safe-db-mcp: connection not found: ${command.connectionId}")
                    1
                } else {
                    val updated =
                        updateSettings(service) {
                            it.copy(
                                defaultConnectionId = command.connectionId,
                                defaultSchema = command.defaultSchema,
                            )
                        }
                    io.print("default_connection_id: ${updated.defaultConnectionId}")
                    io.print("default_schema: ${updated.defaultSchema ?: "(none)"}")
                    0
                }
            }
            McpCommand.SettingsClearDefaultConnection -> {
                updateSettings(service) {
                    it.copy(defaultConnectionId = null, defaultSchema = null)
                }
                io.print("Cleared default connection")
                0
            }
            is McpCommand.SettingsBlockSchema -> {
                val updated =
                    updateSettings(service) {
                        it.copy(blockedSchemas = it.blockedSchemas + command.schema)
                    }
                io.print("blocked_schemas: ${blockedSchemasText(updated.blockedSchemas)}")
                0
            }
            is McpCommand.SettingsUnblockSchema -> {
                val updated =
                    updateSettings(service) {
                        it.copy(
                            blockedSchemas = it.blockedSchemas - command.schema.trim().lowercase()
                        )
                    }
                io.print("blocked_schemas: ${blockedSchemasText(updated.blockedSchemas)}")
                0
            }
            McpCommand.RecipesList -> {
                val recipes = service.listExploreRecipes()
                if (recipes.isEmpty()) {
                    io.print("No recipes.")
                } else {
                    recipes.forEach { recipe ->
                        io.print(
                            "${recipe.id}  ${recipe.name}  ${recipe.defaultMode}  " +
                                "${recipe.requiredFields.size} fields  ${recipe.updatedAt}"
                        )
                    }
                }
                0
            }
            is McpCommand.RecipesImport -> importRecipe(command.path, service, io)
            is McpCommand.RecipesExport -> {
                val recipe = service.listExploreRecipes().firstOrNull { it.id == command.id }
                if (recipe == null) {
                    io.printErr("safe-db-mcp: recipe not found: ${command.id}")
                    1
                } else {
                    io.print(service.exportExploreRecipe(recipe))
                    0
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: McpCliUsageException) {
        io.printErr("safe-db-mcp: ${error.message}")
        2
    } catch (error: PasswordFileException) {
        io.printErr("safe-db-mcp: ${error.message}")
        2
    } catch (error: Exception) {
        io.printErr("safe-db-mcp: ${error.message}")
        1
    }

private const val MAX_RECIPE_IMPORT_BYTES = 1024 * 1024

private suspend fun importRecipe(path: String, service: SafeDbService, io: CliIo): Int {
    val file = Path.of(path)
    if (!file.isAbsolute) {
        throw McpCliUsageException("Recipe path must be absolute: $path")
    }
    if (!Files.isRegularFile(file)) {
        throw IllegalArgumentException("Recipe file not found: $path")
    }
    if (Files.size(file) > MAX_RECIPE_IMPORT_BYTES) {
        throw IllegalArgumentException("Recipe file is larger than 1 MiB: $path")
    }
    val json = Files.readString(file, Charsets.UTF_8)
    val recipe = service.importExploreRecipe(json, Instant.now().epochSecond.toString())
    io.print("Imported ${recipe.id}  ${recipe.name}")
    return 0
}

private suspend fun testConnections(id: String?, service: SafeDbService, io: CliIo): Int {
    val connections = service.listConnections()
    val targets =
        if (id != null) {
            val def = connections.firstOrNull { it.id == id }
            if (def == null) {
                io.printErr("safe-db-mcp: connection not found: $id")
                return 1
            }
            listOf(def)
        } else {
            connections
        }
    if (targets.isEmpty()) {
        io.print("No connections.")
        return 0
    }
    var failed = false
    for (def in targets) {
        try {
            service.testConnection(def, null)
            io.print("OK ${def.id}  ${def.name}  ${def.dialect}  ${def.database}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed = true
            io.printErr("FAIL ${def.id}  ${def.name}: ${error.message}")
        }
    }
    return if (failed) 1 else 0
}

private suspend fun updateSettings(
    service: SafeDbService,
    transform: (Settings) -> Settings,
): Settings {
    val updated = normalizeSettings(transform(service.getSettings()))
    service.saveSettings(updated)
    return updated
}

private fun blockedSchemasText(schemas: List<String>): String =
    if (schemas.isEmpty()) "(none)" else schemas.joinToString(", ")

private fun parseAddFlags(args: List<String>): ConnectionAddFlags {
    var name: String? = null
    var dialect: String? = null
    var host: String? = null
    var port: Int? = null
    var database: String? = null
    var username: String? = null
    var passwordFile: String? = null
    var transport: String? = null
    var oracleWallet: String? = null
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (arg == "--password" || arg.startsWith("--password=") || arg == "-p") {
            throw McpCliUsageException(
                "Passwords cannot be passed on the command line; use --password-file or a prompt"
            )
        }
        val (nameAndValue, consumed) = splitFlag(args, index)
        val (flag, value) = nameAndValue
        when (flag) {
            "--name" -> name = value
            "--dialect" -> dialect = value
            "--host" -> host = value
            "--port" ->
                port =
                    value.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw McpCliUsageException("Invalid --port")
            "--database" -> database = value
            "--username" -> username = value
            "--password-file" -> passwordFile = value
            "--transport" -> transport = value
            "--oracle-wallet" -> oracleWallet = value
            else -> throw McpCliUsageException()
        }
        index += consumed
    }
    return ConnectionAddFlags(
        name = name,
        dialect = dialect,
        host = host,
        port = port,
        database = database,
        username = username,
        passwordFile = passwordFile,
        transport = transport,
        oracleWallet = oracleWallet,
    )
}

private fun splitFlag(args: List<String>, index: Int): Pair<Pair<String, String>, Int> {
    val arg = args[index]
    val equals = arg.indexOf('=')
    if (equals > 0) {
        val flag = arg.substring(0, equals)
        val value = arg.substring(equals + 1)
        if (value.isEmpty()) throw McpCliUsageException("Missing value for $flag")
        return (flag to value) to 1
    }
    if (index + 1 >= args.size || args[index + 1].startsWith("-")) {
        throw McpCliUsageException("Missing value for $arg")
    }
    return (arg to args[index + 1]) to 2
}

private suspend fun addConnection(
    flags: ConnectionAddFlags,
    service: SafeDbService,
    io: CliIo,
    tty: Boolean,
): Int {
    val dialect = parseDialect(requiredFlag(flags.dialect, "--dialect", "Dialect", tty, io))
    val host = requiredFlag(flags.host, "--host", "Host", tty, io, defaultValue = "localhost")
    val defaultPort = DIALECTS.first { it.value == dialect }.defaultPort
    val port =
        flags.port
            ?: promptWithDefault("Port", defaultPort.toString(), tty, io).toIntOrNull()?.takeIf {
                it in 1..65535
            }
            ?: throw McpCliUsageException("Invalid port")
    val database = requiredFlag(flags.database, "--database", "Database", tty, io)
    val username = requiredFlag(flags.username, "--username", "Username", tty, io)
    val name =
        flags.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (tty) promptWithDefault("Name", database, true, io) else database
    val transport = resolveTransport(flags.transport, host, tty, io)
    val wallet =
        if (dialect == Dialect.Oracle && transport != TransportSecurityMode.Disabled) {
            requiredFlag(flags.oracleWallet, "--oracle-wallet", "Oracle wallet", tty, io)
        } else {
            flags.oracleWallet
        }
    val password = resolvePassword(flags.passwordFile, tty, io)
    val def =
        ConnectionDef(
            id = UUID.randomUUID().toString(),
            name = name,
            dialect = dialect,
            host = host,
            port = port,
            database = database,
            username = username,
            transportSecurity =
                TransportSecurity(
                    mode = transport,
                    oracleWalletLocation = wallet?.trim()?.takeIf { it.isNotEmpty() },
                ),
        )
    def.validate().getOrThrow()
    service.testConnection(def, password)
    val created = service.createConnection(def, password)
    io.print("Added connection ${created.id}")
    io.print("name: ${created.name}")
    io.print("dialect: ${created.dialect}")
    io.print("database: ${created.database}")
    return 0
}

private fun resolvePassword(passwordFile: String?, tty: Boolean, io: CliIo): String {
    if (passwordFile != null) {
        return PasswordFile.read(passwordFile, "Password file", requireOwnerOnly = true)
    }
    if (!tty) {
        throw McpCliUsageException("Missing --password-file")
    }
    return io.readPassword("Password: ") ?: throw IllegalStateException("canceled")
}

private fun requiredFlag(
    value: String?,
    flag: String,
    prompt: String,
    tty: Boolean,
    io: CliIo,
    defaultValue: String? = null,
): String {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmed != null) return trimmed
    if (defaultValue != null && !tty) return defaultValue
    if (!tty) throw McpCliUsageException("Missing $flag")
    return promptWithDefault(prompt, defaultValue, true, io)
}

private fun promptWithDefault(
    prompt: String,
    defaultValue: String?,
    tty: Boolean,
    io: CliIo,
): String {
    if (!tty) return defaultValue ?: throw McpCliUsageException("Missing $prompt")
    val suffix = if (defaultValue != null) " [$defaultValue]" else ""
    while (true) {
        val line =
            io.readLine("$prompt$suffix: ")?.trim() ?: throw IllegalStateException("canceled")
        if (line.isNotEmpty()) return line
        if (defaultValue != null) return defaultValue
    }
}

private fun parseDialect(raw: String): Dialect =
    when (raw.trim().lowercase()) {
        "postgres" -> Dialect.Postgres
        "mysql" -> Dialect.MySql
        "mssql" -> Dialect.Mssql
        "oracle" -> Dialect.Oracle
        "db2" -> Dialect.Db2
        else ->
            throw McpCliUsageException(
                "Unknown dialect '$raw'; expected postgres, mysql, mssql, oracle, or db2"
            )
    }

private fun resolveTransport(
    raw: String?,
    host: String,
    tty: Boolean,
    io: CliIo,
): TransportSecurityMode {
    if (raw != null) return parseTransport(raw)
    val recommended = transportPresetForLocation(inferLocation(host)).mode
    if (!tty) return recommended
    val entered = promptWithDefault("Transport", transportFlag(recommended), true, io)
    return parseTransport(entered)
}

private fun parseTransport(raw: String): TransportSecurityMode =
    when (raw.trim().lowercase()) {
        "disabled" -> TransportSecurityMode.Disabled
        "encrypt-only" -> TransportSecurityMode.EncryptOnly
        "verify-ca" -> TransportSecurityMode.VerifyCa
        "verify-identity" -> TransportSecurityMode.VerifyIdentity
        else ->
            throw McpCliUsageException(
                "Unknown transport '$raw'; expected disabled, encrypt-only, verify-ca, or verify-identity"
            )
    }

private fun transportFlag(mode: TransportSecurityMode): String =
    when (mode) {
        TransportSecurityMode.Disabled -> "disabled"
        TransportSecurityMode.EncryptOnly -> "encrypt-only"
        TransportSecurityMode.VerifyCa -> "verify-ca"
        TransportSecurityMode.VerifyIdentity -> "verify-identity"
    }
