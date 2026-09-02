package com.safedb.mcp

import com.safedb.connection.DIALECTS
import com.safedb.connection.inferLocation
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.secrets.PasswordFile
import com.safedb.secrets.PasswordFileException
import com.safedb.service.SafeDbService
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal const val MCP_USAGE =
    """Usage: safe-db-mcp
       safe-db-mcp setup
       safe-db-mcp connections add [options]
       safe-db-mcp connections list
       safe-db-mcp connections delete <id>

Add a connection interactively or with flags. Passwords are never flags; use a prompt
or --password-file /absolute/path.

Example:
  safe-db-mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path

Options:
  --name NAME
  --dialect postgres|mysql|mssql|oracle
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
                else -> throw McpCliUsageException()
            }
        }
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
        else ->
            throw McpCliUsageException(
                "Unknown dialect '$raw'; expected postgres, mysql, mssql, or oracle"
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
