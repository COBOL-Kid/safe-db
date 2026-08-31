package com.safedb.mcp

import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpCliTest {
    @Test
    fun emptyArgsAreStdioAndSetupIsAdd() {
        assertEquals(McpCommand.Stdio, parseMcpArgs(emptyArray()))
        val setup = parseMcpArgs(arrayOf("setup", "--dialect", "mysql"))
        val add = parseMcpArgs(arrayOf("connections", "add", "--dialect", "mysql"))
        assertTrue(setup is McpCommand.ConnectionsAdd)
        assertTrue(add is McpCommand.ConnectionsAdd)
        assertEquals("mysql", setup.flags.dialect)
        assertEquals("mysql", add.flags.dialect)
    }

    @Test
    fun rejectsPasswordFlag() {
        val error =
            assertFailsWith<McpCliUsageException> {
                parseMcpArgs(arrayOf("setup", "--password", "secret"))
            }
        assertTrue(error.message!!.contains("password-file"))
        assertFalse(error.message!!.contains("secret"))
    }

    @Test
    fun addWithFlagsTestsThenSaves() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "hunter2\n")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "connections",
                    "add",
                    "--name",
                    "Tailscale mysql",
                    "--dialect",
                    "mysql",
                    "--host",
                    "db.example",
                    "--port",
                    "3306",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                    "--transport",
                    "disabled",
                    "--password-file",
                    passwordFile.toString(),
                )
            )

        assertEquals(0, executeMcpCommand(command, service, io, tty = false))
        assertEquals(1, service.connections.size)
        val def = service.connections.single()
        assertEquals("Tailscale mysql", def.name)
        assertEquals(Dialect.MySql, def.dialect)
        assertEquals("db.example", def.host)
        assertEquals(3306, def.port)
        assertEquals("app", def.database)
        assertEquals("readonly", def.username)
        assertEquals(TransportSecurityMode.Disabled, def.transportSecurity.mode)
        assertEquals("hunter2", service.passwords[def.id])
        assertEquals(def to "hunter2", service.tested)
        assertTrue(io.stdout.toString().contains("Added connection ${def.id}"))
        assertFalse(io.stdout.toString().contains("hunter2"))
    }

    @Test
    fun addDoesNotSaveWhenTestFails() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "secret")
        val service = RecordingSafeDbService().apply { testError = "connection refused" }
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--dialect",
                    "postgres",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                    "--transport",
                    "disabled",
                    "--password-file",
                    passwordFile.toString(),
                )
            )

        assertEquals(1, executeMcpCommand(command, service, io, tty = false))
        assertTrue(service.connections.isEmpty())
        assertTrue(io.stderr.toString().contains("connection refused"))
    }

    @Test
    fun nonTtyAddWithoutPasswordFileExits2() = runBlocking {
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--dialect",
                    "postgres",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                )
            )
        assertEquals(2, executeMcpCommand(command, RecordingSafeDbService(), io, tty = false))
        assertTrue(io.stderr.toString().contains("--password-file"))
    }

    @Test
    fun interactiveEofCancelsWithoutSaving() = runBlocking {
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        assertEquals(1, executeMcpCommand(parseMcpArgs(arrayOf("setup")), service, io, tty = true))
        assertTrue(service.connections.isEmpty())
        assertEquals(null, service.tested)
        assertEquals("safe-db-mcp: canceled\n", io.stderr.toString())
        assertFalse(io.stdout.toString().contains("Password", ignoreCase = true))
        assertFalse(io.stderr.toString().contains("Password", ignoreCase = true))

        val passwordIo = BufferCliIo()
        val passwordService = RecordingSafeDbService()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--name",
                    "Demo",
                    "--dialect",
                    "postgres",
                    "--host",
                    "localhost",
                    "--port",
                    "5432",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                    "--transport",
                    "disabled",
                )
            )
        assertEquals(1, executeMcpCommand(command, passwordService, passwordIo, tty = true))
        assertTrue(passwordService.connections.isEmpty())
        assertEquals(null, passwordService.tested)
        assertEquals("safe-db-mcp: canceled\n", passwordIo.stderr.toString())
    }

    @Test
    fun invalidDialectExits2() = runBlocking {
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--dialect",
                    "sqlite",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                )
            )
        assertEquals(2, executeMcpCommand(command, service, io, tty = false))
        assertTrue(service.connections.isEmpty())
        assertTrue(io.stderr.toString().contains("Unknown dialect 'sqlite'"))
    }

    @Test
    fun listAndDeleteServiceFailuresExit1() = runBlocking {
        val listIo = BufferCliIo()
        val listService =
            object : RecordingSafeDbService() {
                override suspend fun listConnections() = error("store read failed")
            }
        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsList, listService, listIo, tty = false),
        )
        assertTrue(listIo.stderr.toString().contains("safe-db-mcp: store read failed"))

        val deleteIo = BufferCliIo()
        val deleteService =
            object : RecordingSafeDbService() {
                override suspend fun deleteConnection(id: String) {
                    error("store write failed")
                }
            }
        deleteService.connections += sampleMcpConnection()
        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsDelete("c1"), deleteService, deleteIo, tty = false),
        )
        assertTrue(deleteIo.stderr.toString().contains("safe-db-mcp: store write failed"))
    }

    @Test
    fun listAndDeleteRoundTrip() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()

        assertEquals(0, executeMcpCommand(McpCommand.ConnectionsList, service, io, tty = false))
        assertTrue(io.stdout.toString().contains("c1"))
        assertTrue(io.stdout.toString().contains("Demo"))
        assertFalse(io.stdout.toString().contains("readonly"))

        assertEquals(
            0,
            executeMcpCommand(McpCommand.ConnectionsDelete("c1"), service, io, tty = false),
        )
        assertTrue(service.connections.isEmpty())
        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsDelete("c1"), service, io, tty = false),
        )
    }

    @Test
    fun addPersistsThroughFileStore() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "stored-secret")
        SecretsManager.initFileStore(directory.resolve("credentials"))
        val inner =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(directory),
                queryStore = QueryStore.new(directory),
                settingsStore = SettingsStore.new(directory),
            )
        val service =
            object : RecordingSafeDbService() {
                override suspend fun createConnection(
                    def: com.safedb.model.ConnectionDef,
                    password: String,
                ) = inner.createConnection(def, password)

                override suspend fun listConnections() = inner.listConnections()
            }
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--name",
                    "File store",
                    "--dialect",
                    "postgres",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                    "--transport",
                    "disabled",
                    "--password-file",
                    passwordFile.toString(),
                )
            )

        try {
            assertEquals(0, executeMcpCommand(command, service, io, tty = false))
            val saved = inner.listConnections().single()
            assertEquals("File store", saved.name)
            assertEquals("stored-secret", SecretsManager.passwordForDefinition(saved).getOrThrow())
            assertFalse(
                Files.readString(directory.resolve("connections.json")).contains("stored-secret")
            )
        } finally {
            SecretsManager.initStore("disabled")
        }
    }
}
