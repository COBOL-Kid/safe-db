package com.safedb.mcp

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryRiskGate
import com.safedb.secrets.PasswordFileException
import com.safedb.service.SafeDbService
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
        "connections" -> parseConnectionsCommand(args)
        "settings" -> parseSettingsCommand(args)
        "recipes" -> parseRecipesCommand(args)
        else -> throw McpCliUsageException()
    }
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
            McpCommand.ConnectionsList -> listConnections(service, io)
            is McpCommand.ConnectionsDelete -> deleteConnection(command.id, service, io)
            is McpCommand.ConnectionsAdd -> addConnection(command.flags, service, io, tty)
            is McpCommand.ConnectionsTest -> testConnections(command.id, service, io)
            McpCommand.SettingsShow -> showSettings(service, io)
            is McpCommand.SettingsRiskGate -> setRiskGate(command.gate, service, io)
            is McpCommand.SettingsDefaultConnection ->
                setDefaultConnection(command.connectionId, command.defaultSchema, service, io)
            McpCommand.SettingsClearDefaultConnection -> clearDefaultConnection(service, io)
            is McpCommand.SettingsBlockSchema -> blockSchema(command.schema, service, io)
            is McpCommand.SettingsUnblockSchema -> unblockSchema(command.schema, service, io)
            McpCommand.RecipesList -> listRecipes(service, io)
            is McpCommand.RecipesImport -> importRecipe(command.path, service, io)
            is McpCommand.RecipesExport -> exportRecipe(command.id, service, io)
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

internal fun splitFlag(args: List<String>, index: Int): Pair<Pair<String, String>, Int> {
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

internal suspend fun requireConnection(
    service: SafeDbService,
    id: String,
    io: CliIo,
): ConnectionDef? {
    val def = service.listConnections().firstOrNull { it.id == id }
    if (def == null) {
        io.printErr("safe-db-mcp: connection not found: $id")
        return null
    }
    return def
}

internal suspend fun requireRecipe(
    service: SafeDbService,
    id: String,
    io: CliIo,
): ExploreRecipe? {
    val recipe = service.listExploreRecipes().firstOrNull { it.id == id }
    if (recipe == null) {
        io.printErr("safe-db-mcp: recipe not found: $id")
        return null
    }
    return recipe
}
