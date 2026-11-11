package com.safedb.mcp

import com.safedb.model.QueryRiskGate
import com.safedb.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpCliSettingsTest {
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
    fun malformedSettingsCommandsAreRejected() {
        val invalid =
            listOf(
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
}
