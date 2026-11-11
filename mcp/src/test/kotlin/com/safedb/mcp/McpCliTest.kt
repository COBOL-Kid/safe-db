package com.safedb.mcp

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryRiskGate
import com.safedb.model.SafeDbJson
import com.safedb.model.Settings
import com.safedb.model.TransportSecurityMode
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpCliTest {
    @Test
    fun allHelpAliasesSelectHelp() {
        listOf("--help", "-h", "help").forEach { alias ->
            assertEquals(McpCommand.Help, parseMcpArgs(arrayOf(alias)))
        }
    }

    @Test
    fun emptyArgsAreStdioAndSetupIsAdd() {
        assertEquals(McpCommand.Stdio, parseMcpArgs(emptyArray()))
        val setup = parseMcpArgs(arrayOf("setup", "--dialect", "mysql"))
        val add = parseMcpArgs(arrayOf("connections", "add", "--dialect", "mysql"))
        assertTrue(setup is McpCommand.ConnectionsAdd)
        assertTrue(add is McpCommand.ConnectionsAdd)
        assertEquals("mysql", setup.flags.dialect)
        assertEquals("mysql", add.flags.dialect)
        assertTrue(
            MCP_USAGE.contains(
                "safe-db-mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path"
            )
        )
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
    fun malformedCommandsAndUnknownFlagsAreRejected() {
        val invalid =
            listOf(
                arrayOf("unknown"),
                arrayOf("connections"),
                arrayOf("connections", "unknown"),
                arrayOf("connections", "list", "extra"),
                arrayOf("connections", "delete"),
                arrayOf("connections", "delete", "--force"),
                arrayOf("setup", "--unknown", "value"),
                arrayOf("setup", "--name"),
                arrayOf("setup", "--name="),
            )

        invalid.forEach { args ->
            assertFailsWith<McpCliUsageException>("Expected rejection for ${args.toList()}") {
                parseMcpArgs(args)
            }
        }
    }

    @Test
    fun invalidPortFlagsAreRejected() {
        listOf("0", "65536", "not-a-port").forEach { port ->
            val error =
                assertFailsWith<McpCliUsageException> {
                    parseMcpArgs(arrayOf("setup", "--port", port))
                }
            assertEquals("Invalid --port", error.message)
        }
    }

    @Test
    fun invalidTransportExits2WithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "secret")
        val command =
            parseMcpArgs(
                validNonInteractiveAddArgs(passwordFile) + arrayOf("--transport", "opportunistic")
            )
        val service = RecordingSafeDbService()
        val io = BufferCliIo()

        assertEquals(2, executeMcpCommand(command, service, io, tty = false))
        assertTrue(service.connections.isEmpty())
        assertTrue(io.stderr.toString().contains("Unknown transport 'opportunistic'"))
        assertFalse(io.stderr.toString().contains("secret"))
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
    fun interactiveAddWithFlagsTestsThenSavesWithoutLeakingPassword() = runBlocking {
        val service = RecordingSafeDbService()
        val io = BufferCliIo(passwords = ArrayDeque(listOf("tty-secret")))
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--name",
                    "Interactive mysql",
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
                )
            )

        assertEquals(0, executeMcpCommand(command, service, io, tty = true))
        assertEquals(1, service.connections.size)
        val def = service.connections.single()
        assertEquals("Interactive mysql", def.name)
        assertEquals(def to "tty-secret", service.tested)
        assertEquals("tty-secret", service.passwords[def.id])
        assertTrue(io.stdout.toString().contains("Added connection ${def.id}"))
        assertFalse(io.stdout.toString().contains("tty-secret"))
        assertFalse(io.stderr.toString().contains("tty-secret"))
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
    fun oracleSecureTransportRequiresWallet() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "oracle-secret")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val secure =
            parseMcpArgs(
                validNonInteractiveAddArgs(passwordFile, dialect = "oracle") +
                    arrayOf("--transport", "verify-identity")
            )

        assertEquals(2, executeMcpCommand(secure, service, io, tty = false))
        assertTrue(service.connections.isEmpty())
        assertTrue(io.stderr.toString().contains("--oracle-wallet"))
        assertFalse(io.stderr.toString().contains("oracle-secret"))

        val disabledIo = BufferCliIo()
        val disabled =
            parseMcpArgs(
                validNonInteractiveAddArgs(passwordFile, dialect = "oracle") +
                    arrayOf("--transport", "disabled")
            )
        assertEquals(0, executeMcpCommand(disabled, service, disabledIo, tty = false))
        assertEquals(null, service.connections.single().transportSecurity.oracleWalletLocation)
    }

    @Test
    fun oracleSecureTransportSavesWalletLocation() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "oracle-secret")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                validNonInteractiveAddArgs(passwordFile, dialect = "oracle") +
                    arrayOf("--transport", "verify-identity", "--oracle-wallet", "/abs/path")
            )

        assertEquals(0, executeMcpCommand(command, service, io, tty = false))
        val saved = service.connections.single()
        assertEquals("/abs/path", saved.transportSecurity.oracleWalletLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, saved.transportSecurity.mode)
        assertFalse(io.stdout.toString().contains("oracle-secret"))
        assertFalse(io.stderr.toString().contains("oracle-secret"))
    }

    @Test
    fun db2AddDefaultsToPort50000WithoutWallet() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "db2-secret")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command = parseMcpArgs(validNonInteractiveAddArgs(passwordFile, dialect = "db2"))

        assertEquals(0, executeMcpCommand(command, service, io, tty = false))
        val saved = service.connections.single()
        assertEquals(Dialect.Db2, saved.dialect)
        assertEquals(50000, saved.port)
        assertEquals(null, saved.transportSecurity.oracleWalletLocation)
        assertFalse(io.stdout.toString().contains("db2-secret"))

        val secureIo = BufferCliIo()
        val secure =
            parseMcpArgs(
                validNonInteractiveAddArgs(passwordFile, dialect = "db2") +
                    arrayOf("--transport", "verify-identity")
            )
        assertEquals(0, executeMcpCommand(secure, RecordingSafeDbService(), secureIo, tty = false))
    }

    @Test
    fun nonInteractiveAddUsesDocumentedDefaults() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "default-secret")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--dialect",
                    "mysql",
                    "--database",
                    "inventory",
                    "--username",
                    "reader",
                    "--password-file",
                    passwordFile.toString(),
                )
            )

        assertEquals(0, executeMcpCommand(command, service, io, tty = false))
        val saved = service.connections.single()
        assertEquals("inventory", saved.name)
        assertEquals("localhost", saved.host)
        assertEquals(3306, saved.port)
        assertEquals(TransportSecurityMode.Disabled, saved.transportSecurity.mode)
        assertFalse(io.stdout.toString().contains("default-secret"))
    }

    @Test
    fun nonInteractiveRemoteAddDefaultsToVerifyIdentity() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val passwordFile = writeOwnerOnlyPasswordFile(directory, "remote-secret")
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val command =
            parseMcpArgs(
                arrayOf(
                    "setup",
                    "--host",
                    "db.example.com",
                    "--dialect",
                    "postgres",
                    "--database",
                    "app",
                    "--username",
                    "readonly",
                    "--password-file",
                    passwordFile.toString(),
                )
            )

        assertEquals(0, executeMcpCommand(command, service, io, tty = false))
        val saved = service.connections.single()
        assertEquals("db.example.com", saved.host)
        assertEquals(TransportSecurityMode.VerifyIdentity, saved.transportSecurity.mode)
        assertFalse(io.stdout.toString().contains("remote-secret"))
        assertFalse(io.stderr.toString().contains("remote-secret"))
    }

    @Test
    fun unsafePasswordFileErrorsDoNotLeakSecrets() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val multiline =
            writeOwnerOnlyPasswordFile(directory, "top-secret-value\nunexpected-second-line")
        val multilineIo = BufferCliIo()
        val multilineCommand = parseMcpArgs(validNonInteractiveAddArgs(multiline))

        assertEquals(
            2,
            executeMcpCommand(
                multilineCommand,
                RecordingSafeDbService(),
                multilineIo,
                tty = false,
            ),
        )
        assertTrue(multilineIo.stderr.toString().contains("exactly one line"))
        assertFalse(multilineIo.stderr.toString().contains("top-secret-value"))

        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            val exposed = directory.resolve("exposed-password.txt")
            Files.writeString(exposed, "permission-secret")
            Files.setPosixFilePermissions(exposed, PosixFilePermissions.fromString("rw-r--r--"))
            val permissionsIo = BufferCliIo()
            val permissionsCommand = parseMcpArgs(validNonInteractiveAddArgs(exposed))

            assertEquals(
                2,
                executeMcpCommand(
                    permissionsCommand,
                    RecordingSafeDbService(),
                    permissionsIo,
                    tty = false,
                ),
            )
            assertTrue(permissionsIo.stderr.toString().contains("owner-only"))
            assertFalse(permissionsIo.stderr.toString().contains("permission-secret"))
        }
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
            executeMcpCommand(
                McpCommand.ConnectionsDelete("c1"),
                deleteService,
                deleteIo,
                tty = false,
            ),
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

    @Test
    fun connectionsTestParsesOptionalId() {
        assertEquals(McpCommand.ConnectionsTest(null), parseMcpArgs(arrayOf("connections", "test")))
        assertEquals(
            McpCommand.ConnectionsTest("c1"),
            parseMcpArgs(arrayOf("connections", "test", "c1")),
        )
        assertTrue(MCP_USAGE.contains("safe-db-mcp connections test [<id>]"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp settings [show]"))
        assertTrue(
            MCP_USAGE.contains(
                "safe-db-mcp settings risk-gate <cautious|standard|flexible|disabled>"
            )
        )
        assertTrue(
            MCP_USAGE.contains(
                "safe-db-mcp settings default-connection <id> [--default-schema <schema>]"
            )
        )
        assertTrue(MCP_USAGE.contains("safe-db-mcp settings clear-default-connection"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp settings block-schema <schema>"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp settings unblock-schema <schema>"))
    }

    @Test
    fun connectionsTestSingleConnectionPassesWithoutPassword() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()

        assertEquals(
            0,
            executeMcpCommand(McpCommand.ConnectionsTest("c1"), service, io, tty = false),
        )
        assertEquals("c1", service.tested?.first?.id)
        assertEquals(null, service.tested?.second)
        assertEquals("OK c1  Demo  Postgres  app\n", io.stdout.toString())
        assertEquals("", io.stderr.toString())
    }

    @Test
    fun connectionsTestSingleConnectionFailureExits1() = runBlocking {
        val service = RecordingSafeDbService().apply { testError = "connection refused" }
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsTest("c1"), service, io, tty = false),
        )
        assertEquals("FAIL c1  Demo: connection refused\n", io.stderr.toString())
        assertEquals("", io.stdout.toString())
    }

    @Test
    fun connectionsTestUnknownIdExits1() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsTest("missing"), service, io, tty = false),
        )
        assertEquals("safe-db-mcp: connection not found: missing\n", io.stderr.toString())
        assertEquals(null, service.tested)
    }

    @Test
    fun connectionsTestWithoutIdCoversAllConnections() = runBlocking {
        val emptyIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.ConnectionsTest(null),
                RecordingSafeDbService(),
                emptyIo,
                tty = false,
            ),
        )
        assertEquals("No connections.\n", emptyIo.stdout.toString())

        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        service.connections += sampleMcpConnection(id = "c2", name = "Reporting", database = "dw")
        val okIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(McpCommand.ConnectionsTest(null), service, okIo, tty = false),
        )
        assertEquals(
            "OK c1  Demo  Postgres  app\nOK c2  Reporting  Postgres  dw\n",
            okIo.stdout.toString(),
        )
        assertEquals("", okIo.stderr.toString())
    }

    @Test
    fun connectionsTestWithoutIdReportsPartialFailure() = runBlocking {
        val service =
            object : RecordingSafeDbService() {
                override suspend fun testConnection(def: ConnectionDef, password: String?): String {
                    if (def.id == "c2") error("timed out")
                    return "ok"
                }
            }
        service.connections += sampleMcpConnection()
        service.connections += sampleMcpConnection(id = "c2", name = "Reporting")
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(McpCommand.ConnectionsTest(null), service, io, tty = false),
        )
        assertEquals("OK c1  Demo  Postgres  app\n", io.stdout.toString())
        assertEquals("FAIL c2  Reporting: timed out\n", io.stderr.toString())
    }

    @Test
    fun settingsShowPrintsDefaultsAndSavedValues() = runBlocking {
        assertEquals(McpCommand.SettingsShow, parseMcpArgs(arrayOf("settings")))
        assertEquals(McpCommand.SettingsShow, parseMcpArgs(arrayOf("settings", "show")))

        val defaultIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsShow,
                RecordingSafeDbService(),
                defaultIo,
                tty = false,
            ),
        )
        assertEquals(
            "query_risk_gate: Standard\n" +
                "default_connection_id: (none)\n" +
                "default_schema: (none)\n" +
                "blocked_schemas: (none)\n",
            defaultIo.stdout.toString(),
        )

        val service = RecordingSafeDbService()
        service.settings =
            Settings(
                blockedSchemas = listOf("pg_catalog", "audit"),
                queryRiskGate = QueryRiskGate.Flexible,
                defaultConnectionId = "c1",
                defaultSchema = "public",
            )
        val io = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(McpCommand.SettingsShow, service, io, tty = false),
        )
        assertEquals(
            "query_risk_gate: Flexible\n" +
                "default_connection_id: c1\n" +
                "default_schema: public\n" +
                "blocked_schemas: pg_catalog, audit\n",
            io.stdout.toString(),
        )
    }

    @Test
    fun settingsRiskGateParsesEveryGateAndSaves() = runBlocking {
        assertEquals(
            McpCommand.SettingsRiskGate(QueryRiskGate.Cautious),
            parseMcpArgs(arrayOf("settings", "risk-gate", "cautious")),
        )
        assertEquals(
            McpCommand.SettingsRiskGate(QueryRiskGate.Standard),
            parseMcpArgs(arrayOf("settings", "risk-gate", "STANDARD")),
        )
        assertEquals(
            McpCommand.SettingsRiskGate(QueryRiskGate.Flexible),
            parseMcpArgs(arrayOf("settings", "risk-gate", "Flexible")),
        )
        assertEquals(
            McpCommand.SettingsRiskGate(QueryRiskGate.Disabled),
            parseMcpArgs(arrayOf("settings", "risk-gate", "disabled")),
        )

        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsRiskGate(QueryRiskGate.Flexible),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals(QueryRiskGate.Flexible, service.settings.queryRiskGate)
        assertEquals("query_risk_gate: Flexible\n", io.stdout.toString())
    }

    @Test
    fun settingsRiskGateRejectsUnknownGate() {
        val error =
            assertFailsWith<McpCliUsageException> {
                parseMcpArgs(arrayOf("settings", "risk-gate", "reckless"))
            }
        assertEquals(
            "Unknown risk gate 'reckless'; expected cautious, standard, flexible, or disabled",
            error.message,
        )
    }

    @Test
    fun settingsDefaultConnectionParsesAndSaves() = runBlocking {
        assertEquals(
            McpCommand.SettingsDefaultConnection("c1"),
            parseMcpArgs(arrayOf("settings", "default-connection", "c1")),
        )
        assertEquals(
            McpCommand.SettingsDefaultConnection("c1", "reporting"),
            parseMcpArgs(
                arrayOf("settings", "default-connection", "c1", "--default-schema", "reporting")
            ),
        )
        assertEquals(
            McpCommand.SettingsDefaultConnection("c1", "reporting"),
            parseMcpArgs(
                arrayOf("settings", "default-connection", "c1", "--default-schema=reporting")
            ),
        )

        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                parseMcpArgs(
                    arrayOf(
                        "settings",
                        "default-connection",
                        "c1",
                        "--default-schema",
                        "reporting",
                    )
                ),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals("c1", service.settings.defaultConnectionId)
        assertEquals("reporting", service.settings.defaultSchema)
        assertEquals(
            "default_connection_id: c1\ndefault_schema: reporting\n",
            io.stdout.toString(),
        )

        val plainIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsDefaultConnection("c1"),
                service,
                plainIo,
                tty = false,
            ),
        )
        assertEquals(null, service.settings.defaultSchema)
        assertEquals(
            "default_connection_id: c1\ndefault_schema: (none)\n",
            plainIo.stdout.toString(),
        )
    }

    @Test
    fun settingsDefaultConnectionRejectsUnknownConnection() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(
                McpCommand.SettingsDefaultConnection("missing", "reporting"),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals("safe-db-mcp: connection not found: missing\n", io.stderr.toString())
        assertEquals(null, service.settings.defaultConnectionId)
        assertEquals(null, service.settings.defaultSchema)
    }

    @Test
    fun settingsClearDefaultConnectionClearsBothFields() = runBlocking {
        val service = RecordingSafeDbService()
        service.settings = Settings(defaultConnectionId = "c1", defaultSchema = "public")
        val io = BufferCliIo()

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsClearDefaultConnection,
                service,
                io,
                tty = false,
            ),
        )
        assertEquals(null, service.settings.defaultConnectionId)
        assertEquals(null, service.settings.defaultSchema)
        assertEquals("Cleared default connection\n", io.stdout.toString())
    }

    @Test
    fun settingsBlockAndUnblockSchemaNormalizeAndSave() = runBlocking {
        assertEquals(
            McpCommand.SettingsBlockSchema("pg_catalog"),
            parseMcpArgs(arrayOf("settings", "block-schema", "pg_catalog")),
        )
        assertEquals(
            McpCommand.SettingsUnblockSchema("pg_catalog"),
            parseMcpArgs(arrayOf("settings", "unblock-schema", "pg_catalog")),
        )

        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                parseMcpArgs(arrayOf("settings", "block-schema", " PG_Catalog ")),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals(listOf("pg_catalog"), service.settings.blockedSchemas)
        assertEquals("blocked_schemas: pg_catalog\n", io.stdout.toString())

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsBlockSchema("audit"),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals(listOf("pg_catalog", "audit"), service.settings.blockedSchemas)

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsUnblockSchema("Audit"),
                service,
                io,
                tty = false,
            ),
        )
        assertEquals(listOf("pg_catalog"), service.settings.blockedSchemas)
        assertTrue(io.stdout.toString().contains("blocked_schemas: pg_catalog\n"))

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.SettingsUnblockSchema("pg_catalog"),
                service,
                io,
                tty = false,
            ),
        )
        assertTrue(service.settings.blockedSchemas.isEmpty())
        assertTrue(io.stdout.toString().endsWith("blocked_schemas: (none)\n"))
    }

    @Test
    fun malformedConnectionsTestAndSettingsCommandsAreRejected() {
        val invalid =
            listOf(
                arrayOf("connections", "test", "--force"),
                arrayOf("connections", "test", "c1", "extra"),
                arrayOf("settings", "show", "extra"),
                arrayOf("settings", "risk-gate"),
                arrayOf("settings", "risk-gate", "standard", "extra"),
                arrayOf("settings", "default-connection"),
                arrayOf("settings", "default-connection", "--flag"),
                arrayOf("settings", "default-connection", "c1", "--unknown", "value"),
                arrayOf("settings", "default-connection", "c1", "--default-schema"),
                arrayOf("settings", "default-connection", "c1", "--default-schema="),
                arrayOf("settings", "clear-default-connection", "extra"),
                arrayOf("settings", "block-schema"),
                arrayOf("settings", "block-schema", "--flag"),
                arrayOf("settings", "block-schema", " "),
                arrayOf("settings", "unblock-schema"),
                arrayOf("settings", "unblock-schema", "--flag"),
                arrayOf("settings", "unblock-schema", " "),
                arrayOf("settings", "unknown"),
            )

        invalid.forEach { args ->
            assertFailsWith<McpCliUsageException>("Expected rejection for ${args.toList()}") {
                parseMcpArgs(args)
            }
        }
    }

    @Test
    fun recipesCommandsParse() {
        assertEquals(McpCommand.RecipesList, parseMcpArgs(arrayOf("recipes", "list")))
        assertEquals(
            McpCommand.RecipesImport("/tmp/recipe.json"),
            parseMcpArgs(arrayOf("recipes", "import", "/tmp/recipe.json")),
        )
        assertEquals(
            McpCommand.RecipesExport("r1"),
            parseMcpArgs(arrayOf("recipes", "export", "r1")),
        )
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes list"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes import /absolute/path/recipe.json"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes export <recipe_id>"))
    }

    @Test
    fun malformedRecipesCommandsAreRejected() {
        val invalid =
            listOf(
                arrayOf("recipes"),
                arrayOf("recipes", "unknown"),
                arrayOf("recipes", "list", "extra"),
                arrayOf("recipes", "import"),
                arrayOf("recipes", "import", "--force"),
                arrayOf("recipes", "export"),
                arrayOf("recipes", "export", "--force"),
            )

        invalid.forEach { args ->
            assertFailsWith<McpCliUsageException>("Expected rejection for ${args.toList()}") {
                parseMcpArgs(args)
            }
        }
    }

    @Test
    fun recipesImportListAndExportRoundTrip() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val recipeFile = directory.resolve("recipe.json")
        Files.writeString(recipeFile, MINIMAL_RECIPE_JSON)
        val service = realRecipeService(directory)

        val importIo = BufferCliIo()
        val importCommand = parseMcpArgs(arrayOf("recipes", "import", recipeFile.toString()))
        assertEquals(0, executeMcpCommand(importCommand, service, importIo, tty = false))

        val imported = service.listExploreRecipes().single()
        assertEquals("By region", imported.name)
        assertNotEquals("ignored", imported.id)
        assertEquals("Imported ${imported.id}  By region\n", importIo.stdout.toString())
        assertEquals("", importIo.stderr.toString())

        val listIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(McpCommand.RecipesList, service, listIo, tty = false),
        )
        assertEquals(
            "${imported.id}  By region  Pivot  0 fields  ${imported.updatedAt}\n",
            listIo.stdout.toString(),
        )

        val exportIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.RecipesExport(imported.id),
                service,
                exportIo,
                tty = false,
            ),
        )
        val exported = exportIo.stdout.toString().trim()
        assertTrue(exported.contains("\"name\": \"By region\""))
        val decoded = SafeDbJson.lenient.decodeFromString(ExploreRecipe.serializer(), exported)
        assertEquals(imported.id, decoded.id)
        assertEquals("By region", decoded.name)
        assertEquals("", exportIo.stderr.toString())
    }

    @Test
    fun recipesListWithoutRecipesPrintsNone() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val io = BufferCliIo()

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.RecipesList,
                realRecipeService(directory),
                io,
                tty = false,
            ),
        )
        assertEquals("No recipes.\n", io.stdout.toString())
    }

    @Test
    fun recipesExportUnknownIdExits1() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(McpCommand.RecipesExport("missing"), service, io, tty = false),
        )
        assertEquals("safe-db-mcp: recipe not found: missing\n", io.stderr.toString())
        assertEquals("", io.stdout.toString())
    }

    @Test
    fun recipesInvalidImportExitsNonZeroWithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val recipeFile = directory.resolve("recipe.json")
        Files.writeString(recipeFile, "{ not a recipe")
        val service = realRecipeService(directory)
        val io = BufferCliIo()
        val command = parseMcpArgs(arrayOf("recipes", "import", recipeFile.toString()))

        assertEquals(1, executeMcpCommand(command, service, io, tty = false))
        assertTrue(service.listExploreRecipes().isEmpty())
        assertTrue(io.stderr.toString().startsWith("safe-db-mcp: "))
        assertEquals("", io.stdout.toString())
    }

    @Test
    fun recipesRelativeImportPathIsRejectedWithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)
        val io = BufferCliIo()

        assertEquals(
            2,
            executeMcpCommand(McpCommand.RecipesImport("recipe.json"), service, io, tty = false),
        )
        assertTrue(io.stderr.toString().contains("must be absolute"))
        assertTrue(service.listExploreRecipes().isEmpty())
    }

    @Test
    fun recipesImportRejectsMissingAndOversizedFiles() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)

        val missingIo = BufferCliIo()
        assertEquals(
            1,
            executeMcpCommand(
                McpCommand.RecipesImport(directory.resolve("missing.json").toString()),
                service,
                missingIo,
                tty = false,
            ),
        )
        assertTrue(missingIo.stderr.toString().contains("Recipe file not found"))

        val oversized = directory.resolve("oversized.json")
        Files.write(oversized, ByteArray(1024 * 1024 + 1))
        val oversizedIo = BufferCliIo()
        assertEquals(
            1,
            executeMcpCommand(
                McpCommand.RecipesImport(oversized.toString()),
                service,
                oversizedIo,
                tty = false,
            ),
        )
        assertTrue(oversizedIo.stderr.toString().contains("larger than 1 MiB"))
        assertTrue(service.listExploreRecipes().isEmpty())
    }
}

private fun realRecipeService(directory: Path): SafeDbServiceImpl =
    SafeDbServiceImpl(
        configStore = ConfigStore.new(directory),
        queryStore = QueryStore.new(directory),
        settingsStore = SettingsStore.new(directory),
        recipeStore = RecipeStore.new(directory),
    )

private const val MINIMAL_RECIPE_JSON =
    """{
      "schemaVersion": 1,
      "id": "ignored",
      "name": "By region",
      "createdAt": "1",
      "updatedAt": "1",
      "defaultMode": "Pivot",
      "pivot": { "schemaVersion": 1 }
    }"""

private fun validNonInteractiveAddArgs(
    passwordFile: java.nio.file.Path,
    dialect: String = "postgres",
): Array<String> =
    arrayOf(
        "setup",
        "--dialect",
        dialect,
        "--database",
        "app",
        "--username",
        "readonly",
        "--password-file",
        passwordFile.toString(),
    )
