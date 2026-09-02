package com.safedb.mcp

import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import com.safedb.testsupport.IntegrationAssumptions
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class McpMySqlIntegrationTest {
    @Test
    fun mysqlToolsUseSavedConnectionAndKeepCredentialsPrivate() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val dataDir = Files.createTempDirectory("safedb-mcp-mysql-integration")
        val resultsDir = dataDir.resolve("results")
        val connection =
            IntegrationAssumptions.mysqlConnectionDef(
                id = "mcp-integration-mysql",
                name = "MCP Integration MySQL",
            )
        val password = IntegrationAssumptions.mysqlPassword

        SecretsManager.lockCredentials()
        SecretsManager.initStore("disabled")
        try {
            val service =
                SafeDbServiceImpl(
                    configStore = ConfigStore.new(dataDir),
                    queryStore = QueryStore.new(dataDir),
                    settingsStore = SettingsStore.new(dataDir),
                )
            service.createConnection(connection, password)
            val server = createSafeDbMcpServer(service, resultsDir = resultsDir)

            withIntegrationMcpClient(server) { client ->
                val listed = client.callTool("list_tables", mapOf("connection_id" to connection.id))
                assertNotEquals(true, listed.isError)
                val listedText = listed.text()
                assertPayloadIsPrivate(listedText, connection, password)
                val tables = Json.parseToJsonElement(listedText).jsonArray
                assertNotNull(
                    tables.find {
                        val table = it.jsonObject
                        table.getValue("schema").jsonPrimitive.content == connection.database &&
                            table.getValue("name").jsonPrimitive.content == "customers"
                    }
                )
                assertNotNull(
                    tables.find {
                        val table = it.jsonObject
                        table.getValue("schema").jsonPrimitive.content == connection.database &&
                            table.getValue("name").jsonPrimitive.content == "orders"
                    }
                )

                val described =
                    client.callTool(
                        "describe_table",
                        mapOf(
                            "connection_id" to connection.id,
                            "schema" to connection.database,
                            "table" to "orders",
                        ),
                    )
                assertNotEquals(true, described.isError)
                val describedText = described.text()
                assertPayloadIsPrivate(describedText, connection, password)
                val order = Json.parseToJsonElement(describedText).jsonObject
                assertEquals("orders", order.getValue("name").jsonPrimitive.content)
                val orderColumns =
                    order
                        .getValue("columns")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                        .toSet()
                assertTrue(setOf("id", "customer_id", "status").all { it in orderColumns })

                val receiptResult =
                    client.callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to connection.id,
                            "sql" to "SELECT id, email FROM customers LIMIT 3",
                            "default_schema" to connection.database,
                        ),
                    )
                assertNotEquals(true, receiptResult.isError, receiptResult.text())
                val receiptText = receiptResult.text()
                assertPayloadIsPrivate(receiptText, connection, password)
                val receipt = Json.parseToJsonElement(receiptText).jsonObject
                assertEquals("3", receipt.getValue("row_count").jsonPrimitive.content)
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                assertTrue(resultId.isNotBlank())

                val pageResult =
                    client.callTool(
                        "get_result_rows",
                        mapOf("result_id" to resultId, "offset" to 1, "limit" to 1),
                    )
                assertNotEquals(true, pageResult.isError)
                val pageText = pageResult.text()
                assertPayloadIsPrivate(pageText, connection, password)
                val page = Json.parseToJsonElement(pageText).jsonObject
                assertEquals(resultId, page.getValue("result_id").jsonPrimitive.content)
                assertEquals("1", page.getValue("offset").jsonPrimitive.content)
                assertEquals(1, page.getValue("rows").jsonArray.size)

                val summaryResult =
                    client.callTool("summarize_result", mapOf("result_id" to resultId))
                assertNotEquals(true, summaryResult.isError)
                val summaryText = summaryResult.text()
                assertPayloadIsPrivate(summaryText, connection, password)
                val summary = Json.parseToJsonElement(summaryText).jsonObject
                assertEquals(resultId, summary.getValue("result_id").jsonPrimitive.content)
                assertEquals("3", summary.getValue("row_count").jsonPrimitive.content)
                val summarizedColumns =
                    summary
                        .getValue("columns")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                        .toSet()
                assertEquals(setOf("customers__id", "customers__email"), summarizedColumns)
            }
        } finally {
            SecretsManager.lockCredentials()
            SecretsManager.initStore("disabled")
            SecretsManager.resetStoreReadCountForTest()
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun packagedJarMysqlToolsUseSavedConnectionAndKeepCredentialsPrivate() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val connection =
            IntegrationAssumptions.mysqlConnectionDef(
                id = "mcp-packaged-mysql",
                name = "MCP Packaged MySQL",
            )
        val password = IntegrationAssumptions.mysqlPassword
        try {
            withPackagedMcpClient(
                // Default "disabled" would hide secrets that seedMcpConnectionForPackagedJar
                // just wrote.
                keychainBackend = null,
                prepare = { tempRoot, home ->
                    seedMcpConnectionForPackagedJar(
                        isolatedMcpDataDir(tempRoot, home),
                        connection,
                        password,
                    )
                },
            ) { client ->
                val listed = client.callTool("list_tables", mapOf("connection_id" to connection.id))
                assertNotEquals(true, listed.isError)
                val listedText = listed.text()
                assertPayloadIsPrivate(listedText, connection, password)
                val tables = Json.parseToJsonElement(listedText).jsonArray
                assertNotNull(
                    tables.find {
                        val table = it.jsonObject
                        table.getValue("schema").jsonPrimitive.content == connection.database &&
                            table.getValue("name").jsonPrimitive.content == "customers"
                    }
                )
                assertNotNull(
                    tables.find {
                        val table = it.jsonObject
                        table.getValue("schema").jsonPrimitive.content == connection.database &&
                            table.getValue("name").jsonPrimitive.content == "orders"
                    }
                )

                val receiptResult =
                    client.callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to connection.id,
                            "sql" to "SELECT id, email FROM customers LIMIT 3",
                            "default_schema" to connection.database,
                        ),
                    )
                assertNotEquals(true, receiptResult.isError, receiptResult.text())
                val receiptText = receiptResult.text()
                assertPayloadIsPrivate(receiptText, connection, password)
                val receipt = Json.parseToJsonElement(receiptText).jsonObject
                assertEquals("3", receipt.getValue("row_count").jsonPrimitive.content)
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                assertTrue(resultId.isNotBlank())
            }
        } finally {
            cleanupPackagedJarWindowsSecret(connection.id)
        }
    }
}
