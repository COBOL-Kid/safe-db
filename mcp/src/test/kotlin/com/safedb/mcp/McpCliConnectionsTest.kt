package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.secrets.ENV_BACKEND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpCliConnectionsTest {
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
    fun addRefusesWhenKeychainBackendIsDisabled() = runBlocking {
        val service = RecordingSafeDbService()
        val io = BufferCliIo()
        val flags =
            ConnectionAddFlags(
                dialect = "postgres",
                database = "app",
                username = "readonly",
                passwordFile = "/tmp/unused",
            )
        val error =
            assertFailsWith<IllegalStateException> {
                addConnection(flags, service, io, tty = false, envBackend = "disabled")
            }
        assertTrue(error.message!!.contains("$ENV_BACKEND=disabled"))
        assertTrue(service.connections.isEmpty())
        assertEquals(null, service.tested)
    }

    @Test
    fun malformedConnectionsTestCommandsAreRejected() {
        val invalid =
            listOf(
                arrayOf("connections", "test", "--force"),
                arrayOf("connections", "test", "c1", "extra"),
            )

        invalid.forEach { args ->
            assertFailsWith<McpCliUsageException>("Expected rejection for ${args.toList()}") {
                parseMcpArgs(args)
            }
        }
    }
}
