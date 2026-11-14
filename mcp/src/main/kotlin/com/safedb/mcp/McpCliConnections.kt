package com.safedb.mcp

import com.safedb.connection.DIALECTS
import com.safedb.connection.inferLocation
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.secrets.ENV_BACKEND
import com.safedb.secrets.PasswordFile
import com.safedb.secrets.RequestedBackend
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbService
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun parseConnectionsCommand(args: Array<String>): McpCommand {
    if (args.size < 2) throw McpCliUsageException()
    return when (args[1]) {
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

internal fun parseAddFlags(args: List<String>): ConnectionAddFlags {
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
                "Passwords cannot be passed on the command line; use --password-file or a prompt",
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

private fun parseConnectionsTest(args: Array<String>): McpCommand =
    when (args.size) {
        2 -> McpCommand.ConnectionsTest(null)
        3 ->
            if (args[2].startsWith("-")) {
                throw McpCliUsageException()
            } else {
                McpCommand.ConnectionsTest(args[2])
            }
        else -> throw McpCliUsageException()
    }

internal suspend fun listConnections(service: SafeDbService, io: CliIo): Int {
    val connections = service.listConnections()
    if (connections.isEmpty()) {
        io.print("No connections.")
    } else {
        connections.forEach { def ->
            io.print("${def.id}  ${def.name}  ${def.dialect}  ${def.database}")
        }
    }
    return 0
}

internal suspend fun deleteConnection(id: String, service: SafeDbService, io: CliIo): Int {
    if (requireConnection(service, id, io) == null) return 1
    service.deleteConnection(id)
    io.print("Deleted $id")
    return 0
}

internal suspend fun testConnections(id: String?, service: SafeDbService, io: CliIo): Int {
    val connections = service.listConnections()
    val targets =
        if (id != null) {
            val def = requireConnection(service, id, io) ?: return 1
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

internal fun ephemeralPasswordStoreRequested(
    envValue: String? = System.getenv(ENV_BACKEND),
): Boolean = SecretsManager.parseRequestedBackendFrom(envValue) == RequestedBackend.Disabled

internal suspend fun addConnection(
    flags: ConnectionAddFlags,
    service: SafeDbService,
    io: CliIo,
    tty: Boolean,
    envBackend: String? = System.getenv(ENV_BACKEND),
): Int {
    if (ephemeralPasswordStoreRequested(envBackend)) {
        throw IllegalStateException(
            "Refusing to save the connection: $ENV_BACKEND=disabled holds passwords in " +
                "process memory only, so this connection could not authenticate after setup " +
                "exits. Unset $ENV_BACKEND (Linux/macOS then uses the owner-only file store) " +
                "and run setup again.",
        )
    }
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
                "Unknown dialect '$raw'; expected postgres, mysql, mssql, oracle, or db2",
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
                "Unknown transport '$raw'; expected disabled, encrypt-only, verify-ca, or verify-identity",
            )
    }

private fun transportFlag(mode: TransportSecurityMode): String =
    when (mode) {
        TransportSecurityMode.Disabled -> "disabled"
        TransportSecurityMode.EncryptOnly -> "encrypt-only"
        TransportSecurityMode.VerifyCa -> "verify-ca"
        TransportSecurityMode.VerifyIdentity -> "verify-identity"
    }
