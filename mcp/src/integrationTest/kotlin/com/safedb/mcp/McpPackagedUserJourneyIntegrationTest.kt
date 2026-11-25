package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.testsupport.IntegrationAssumptions
import io.modelcontextprotocol.kotlin.sdk.client.Client
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private const val CLI_JOURNEY_TIMEOUT_SECONDS = 60L
private const val JOURNEY_CLIENT_TIMEOUT_MS = 120_000L

@Tag("integration")
class McpPackagedUserJourneyIntegrationTest {
    @Test
    fun packagedUserJourneyMysql() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        runFullUserJourney(
            DialectJourneyTarget(
                slug = "mysql",
                cliDialect = "mysql",
                connection =
                    IntegrationAssumptions.mysqlConnectionDef(
                        id = "ignored",
                        name = "Journey MySQL",
                    ),
                password = IntegrationAssumptions.mysqlPassword,
                defaultSchema = IntegrationAssumptions.mysqlDatabase,
                scanEventsTable = "safedb_lab.scan_events",
                blockedScanSql = "SELECT id, label FROM safedb_lab.scan_events",
                customersPreviewSql = "SELECT id, email FROM customers LIMIT 5",
            ),
        )
    }

    @Test
    fun packagedUserJourneyPostgres() = runBlocking {
        IntegrationAssumptions.assumePostgresAvailable()
        runFullUserJourney(
            DialectJourneyTarget(
                slug = "postgres",
                cliDialect = "postgres",
                connection =
                    IntegrationAssumptions.postgresConnectionDef(
                        id = "ignored",
                        name = "Journey Postgres",
                    ),
                password = IntegrationAssumptions.postgresPassword,
                defaultSchema = "public",
                scanEventsTable = "analytics.scan_events",
                blockedScanSql = "SELECT id, label FROM analytics.scan_events",
                customersPreviewSql = "SELECT id, email FROM customers LIMIT 5",
            ),
        )
    }

    @Test
    fun packagedUserJourneyMssql() = runBlocking {
        IntegrationAssumptions.assumeMssqlAvailable()
        runCoreUserJourney(
            DialectJourneyTarget(
                slug = "mssql",
                cliDialect = "mssql",
                connection =
                    IntegrationAssumptions.mssqlConnectionDef(
                        id = "ignored",
                        name = "Journey MSSQL",
                    ),
                password = IntegrationAssumptions.mssqlPassword,
                defaultSchema = "dbo",
                scanEventsTable = "analytics.scan_events",
                blockedScanSql = "SELECT id, label FROM analytics.scan_events",
                customersPreviewSql = "SELECT TOP 5 id, email FROM customers",
            ),
        )
    }

    @Test
    fun packagedUserJourneyOracle() = runBlocking {
        IntegrationAssumptions.assumeOracleAvailable()
        runCoreUserJourney(
            DialectJourneyTarget(
                slug = "oracle",
                cliDialect = "oracle",
                connection =
                    IntegrationAssumptions.oracleConnectionDef(
                        id = "ignored",
                        name = "Journey Oracle",
                    ),
                password = IntegrationAssumptions.oraclePassword,
                defaultSchema = "SAFEDB",
                scanEventsTable = "SAFEDB.SCAN_EVENTS",
                blockedScanSql = "SELECT ID, LABEL FROM SAFEDB.SCAN_EVENTS",
                customersPreviewSql = "SELECT ID, EMAIL FROM CUSTOMERS FETCH FIRST 5 ROWS ONLY",
            ),
        )
    }

    @Test
    fun packagedUserJourneyDb2() = runBlocking {
        IntegrationAssumptions.assumeDb2Available()
        runCoreUserJourney(
            DialectJourneyTarget(
                slug = "db2",
                cliDialect = "db2",
                connection =
                    IntegrationAssumptions.db2ConnectionDef(
                        id = "ignored",
                        name = "Journey Db2",
                    ),
                password = IntegrationAssumptions.db2Password,
                defaultSchema = IntegrationAssumptions.db2User.uppercase(),
                scanEventsTable = "ANALYTICS.SCAN_EVENTS",
                blockedScanSql = "SELECT ID, LABEL FROM ANALYTICS.SCAN_EVENTS",
                customersPreviewSql = "SELECT ID, EMAIL FROM CUSTOMERS FETCH FIRST 5 ROWS ONLY",
            ),
        )
    }

    private suspend fun runFullUserJourney(target: DialectJourneyTarget) {
        val tempRoot =
            withContext(Dispatchers.IO) {
                Files.createTempDirectory("safedb-mcp-journey-${target.slug}-")
            }
        val createdIds = mutableListOf<String>()
        var failure: Throwable? = null

        try {
            val dataDir =
                withContext(Dispatchers.IO) {
                    val createdHome = Files.createDirectories(tempRoot.resolve("home"))
                    isolatedMcpDataDir(tempRoot, createdHome)
                }
            val connectionId =
                withContext(Dispatchers.IO) { prepareCliJourney(tempRoot, target, createdIds) }
            val connection = target.connection.copy(id = connectionId)
            var savedQueryId = ""

            withPackagedMcpClientIn(
                tempRoot = tempRoot,
                keychainBackend = null,
                timeoutMs = JOURNEY_CLIENT_TIMEOUT_MS,
            ) { client ->
                assertConnectionListed(client, connection, target.password)
                assertTablesListed(client, connection, target.password, target.scanEventsTable)
                assertOrdersDescribed(client, connection, target)
                assertCatalogSearched(client, connection, target.password)
                assertJoinPathsFound(client, connection, target)
                assertScanEventsBlockedUnderCautious(client, connection, target)

                val joinReceipt =
                    callToolObject(
                        client,
                        "run_query",
                        mapOf(
                            "connection_id" to connectionId,
                            "sql" to
                                "SELECT customers.id, customers.email, orders.status, orders.total " +
                                    "FROM customers INNER JOIN orders " +
                                    "ON orders.customer_id = customers.id " +
                                    "ORDER BY orders.id LIMIT 20",
                            "default_schema" to target.defaultSchema,
                        ),
                        connection,
                        target.password,
                    )
                val rowCount = joinReceipt.getValue("row_count").jsonPrimitive.int
                assertTrue(rowCount > 0, "Expected non-empty joined query rows: $joinReceipt")
                val resultId = joinReceipt.getValue("result_id").jsonPrimitive.content

                val paged =
                    callToolObject(
                        client,
                        "get_result_rows",
                        mapOf("result_id" to resultId, "offset" to 0, "limit" to 5),
                        connection,
                        target.password,
                    )
                val pagedRows = paged.getValue("rows").jsonArray
                assertTrue(pagedRows.isNotEmpty() && pagedRows.size <= 5, paged.toString())

                val summary =
                    callToolObject(
                        client,
                        "summarize_result",
                        mapOf("result_id" to resultId),
                        connection,
                        target.password,
                    )
                val summaryCols =
                    summary
                        .getValue("columns")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                        .toSet()
                assertTrue(
                    setOf("customers__id", "customers__email", "orders__status", "orders__total")
                        .all { it in summaryCols },
                    "Missing expected summary columns: $summaryCols",
                )

                val filtered =
                    callToolObject(
                        client,
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"orders__status","op":"equals","value":"delivered"}""",
                                    ),
                                ),
                            "sorts" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"customers__id","direction":"asc"}""",
                                    ),
                                ),
                            "columns" to listOf("customers__id", "orders__status", "orders__total"),
                            "offset" to 0,
                            "limit" to 10,
                        ),
                        connection,
                        target.password,
                    )
                assertTrue(
                    filtered.getValue("matched_row_count").jsonPrimitive.int > 0,
                    filtered.toString(),
                )
                val filteredRows = filtered.getValue("rows").jsonArray
                assertTrue(filteredRows.isNotEmpty(), filtered.toString())
                assertTrue(
                    filteredRows.all {
                        it.jsonObject.getValue("orders__status").jsonPrimitive.content ==
                            "delivered"
                    },
                    filtered.toString(),
                )

                val pivoted =
                    callToolObject(
                        client,
                        "pivot_result",
                        mapOf(
                            "result_id" to resultId,
                            "row_dimensions" to listOf("orders__status"),
                            "measures" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"alias":"order_count","fn":"count"}""",
                                    ),
                                ),
                        ),
                        connection,
                        target.password,
                    )
                val pivotDimCol =
                    pivoted
                        .getValue("columns")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive
                        .content
                assertEquals("status", pivotDimCol, pivoted.toString())
                val pivotRows = pivoted.getValue("rows").jsonArray
                assertTrue(
                    pivotRows.any {
                        it.jsonObject.getValue(pivotDimCol).jsonPrimitive.content == "Total"
                    },
                    "Expected Total row in pivot result: $pivoted",
                )

                val exported =
                    callToolObject(
                        client,
                        "export_result",
                        mapOf("result_id" to resultId, "filename" to "journey-export"),
                        connection,
                        target.password,
                    )
                val exportPath = Path.of(exported.getValue("file_path").jsonPrimitive.content)
                assertTrue(
                    exportPath.normalize().startsWith(dataDir.resolve("exports").normalize()),
                    "Export written outside exportsDir: $exportPath (expected under $dataDir)",
                )
                val csvContent =
                    withContext(Dispatchers.IO) {
                        assertTrue(
                            Files.isRegularFile(exportPath),
                            "Exported CSV does not exist: $exportPath",
                        )
                        Files.readString(exportPath)
                    }
                assertTrue(
                    csvContent
                        .lineSequence()
                        .first()
                        .contains(
                            "customers__id,customers__email,orders__status,orders__total",
                        ),
                    "Unexpected CSV header: $csvContent",
                )

                val saved =
                    callToolObject(
                        client,
                        "save_query",
                        mapOf(
                            "connection_id" to connectionId,
                            "name" to "Delivered Orders",
                            "sql" to
                                "SELECT id, status, total FROM orders " +
                                    "WHERE status = 'delivered' ORDER BY id LIMIT 5",
                            "default_schema" to target.defaultSchema,
                        ),
                        connection,
                        target.password,
                    )
                savedQueryId = saved.getValue("id").jsonPrimitive.content
                assertTrue(savedQueryId.isNotBlank(), saved.toString())

                val historyInProcess1 =
                    client.callTool(
                        "list_query_history",
                        mapOf("connection_id" to connectionId),
                    )
                assertNotEquals(true, historyInProcess1.isError, historyInProcess1.text())
                assertTrue(
                    Json.parseToJsonElement(historyInProcess1.text()).jsonArray.size >= 2,
                    historyInProcess1.text(),
                )
            }

            withPackagedMcpClientIn(
                tempRoot = tempRoot,
                keychainBackend = null,
                timeoutMs = JOURNEY_CLIENT_TIMEOUT_MS,
            ) { client ->
                assertConnectionListed(client, connection, target.password)

                val savedQueries =
                    callToolObject(
                        client,
                        "list_saved_queries",
                        mapOf("connection_id" to connectionId),
                        connection,
                        target.password,
                    )
                assertTrue(
                    savedQueries.getValue("saved_queries").jsonArray.any {
                        it.jsonObject.getValue("id").jsonPrimitive.content == savedQueryId
                    },
                    savedQueries.toString(),
                )

                val savedRun =
                    callToolObject(
                        client,
                        "run_saved_query",
                        mapOf("query_id" to savedQueryId),
                        connection,
                        target.password,
                    )
                assertTrue(
                    savedRun.getValue("row_count").jsonPrimitive.int > 0,
                    savedRun.toString(),
                )

                assertHistoryPersisted(client, connection, target.password, expectedMinEntries = 3)
                deleteConnectionTwoStep(client, connectionId)
            }

            withContext(Dispatchers.IO) { assertCliHasNoConnections(tempRoot) }
        } catch (error: Throwable) {
            failure = error
        } finally {
            createdIds.forEach { cleanupPackagedJarWindowsSecret(it) }
            if (!tempRoot.toFile().deleteRecursively()) {
                val cleanupFailure = AssertionError("Failed to delete temporary data: $tempRoot")
                if (failure == null) failure = cleanupFailure
                else failure.addSuppressed(cleanupFailure)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun runCoreUserJourney(target: DialectJourneyTarget) {
        val tempRoot =
            withContext(Dispatchers.IO) {
                Files.createTempDirectory("safedb-mcp-journey-${target.slug}-")
            }
        val createdIds = mutableListOf<String>()
        var failure: Throwable? = null

        try {
            val connectionId =
                withContext(Dispatchers.IO) { prepareCliJourney(tempRoot, target, createdIds) }
            val connection = target.connection.copy(id = connectionId)

            withPackagedMcpClientIn(
                tempRoot = tempRoot,
                keychainBackend = null,
                timeoutMs = JOURNEY_CLIENT_TIMEOUT_MS,
            ) { client ->
                assertConnectionListed(client, connection, target.password)
                assertTablesListed(client, connection, target.password, target.scanEventsTable)
                assertScanEventsBlockedUnderCautious(client, connection, target)

                val customersReceipt =
                    callToolObject(
                        client,
                        "run_query",
                        mapOf(
                            "connection_id" to connectionId,
                            "sql" to target.customersPreviewSql,
                            "default_schema" to target.defaultSchema,
                        ),
                        connection,
                        target.password,
                    )
                assertEquals("3", customersReceipt.getValue("row_count").jsonPrimitive.content)
            }

            withPackagedMcpClientIn(
                tempRoot = tempRoot,
                keychainBackend = null,
                timeoutMs = JOURNEY_CLIENT_TIMEOUT_MS,
            ) { client ->
                assertConnectionListed(client, connection, target.password)
                assertHistoryPersisted(client, connection, target.password, expectedMinEntries = 2)
                deleteConnectionTwoStep(client, connectionId)
            }

            withContext(Dispatchers.IO) { assertCliHasNoConnections(tempRoot) }
        } catch (error: Throwable) {
            failure = error
        } finally {
            createdIds.forEach { cleanupPackagedJarWindowsSecret(it) }
            if (!tempRoot.toFile().deleteRecursively()) {
                val cleanupFailure = AssertionError("Failed to delete temporary data: $tempRoot")
                if (failure == null) failure = cleanupFailure
                else failure.addSuppressed(cleanupFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun prepareCliJourney(
        tempRoot: Path,
        target: DialectJourneyTarget,
        createdIds: MutableList<String>,
    ): String {
        val passwordFile = tempRoot.resolve("db-password.txt")
        Files.writeString(passwordFile, target.password)
        if (Files.getFileStore(passwordFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                passwordFile,
                PosixFilePermissions.fromString("rw-------"),
            )
        }

        val setupResult =
            runPackagedMcpProcessIn(
                tempRoot = tempRoot,
                args =
                    listOf(
                        "setup",
                        "--name",
                        target.connection.name,
                        "--dialect",
                        target.cliDialect,
                        "--host",
                        target.connection.host,
                        "--port",
                        target.connection.port.toString(),
                        "--database",
                        target.connection.database,
                        "--username",
                        target.connection.username,
                        "--password-file",
                        passwordFile.toAbsolutePath().toString(),
                        "--transport",
                        "disabled",
                    ),
                keychainBackend = null,
                timeoutSeconds = CLI_JOURNEY_TIMEOUT_SECONDS,
            )
        assertEquals(
            0,
            setupResult.exitCode,
            "setup failed: stdout=${setupResult.stdout} stderr=${setupResult.stderr}",
        )
        if (
            target.password.isNotEmpty() &&
                !target.connection.database.contains(target.password) &&
                !target.connection.name.contains(target.password)
        ) {
            assertTrue(
                !setupResult.stdout.contains(target.password) &&
                    !setupResult.stderr.contains(target.password),
                "Password leaked in setup CLI output",
            )
        }
        val addedLine =
            setupResult.stdout.lineSequence().firstOrNull { it.startsWith("Added connection ") }
                ?: throw AssertionError(
                    "Missing 'Added connection <id>' in stdout: ${setupResult.stdout}",
                )
        val connectionId = addedLine.removePrefix("Added connection ").trim()
        assertTrue(connectionId.isNotBlank(), "Blank connection ID in: $addedLine")
        createdIds.add(connectionId)

        val listResult =
            runPackagedMcpProcessIn(
                tempRoot = tempRoot,
                args = listOf("connections", "list"),
                keychainBackend = null,
                timeoutSeconds = CLI_JOURNEY_TIMEOUT_SECONDS,
            )
        assertEquals(0, listResult.exitCode, listResult.stderr)
        assertTrue(
            listResult.stdout.contains(connectionId) &&
                listResult.stdout.contains(target.connection.name) &&
                listResult.stdout.contains(target.connection.database),
            "Unexpected connections list output: ${listResult.stdout}",
        )

        val testResult =
            runPackagedMcpProcessIn(
                tempRoot = tempRoot,
                args = listOf("connections", "test", connectionId),
                keychainBackend = null,
                timeoutSeconds = CLI_JOURNEY_TIMEOUT_SECONDS,
            )
        assertEquals(
            0,
            testResult.exitCode,
            "connections test failed: stdout=${testResult.stdout} stderr=${testResult.stderr}",
        )
        assertTrue(testResult.stdout.contains("OK $connectionId"), testResult.stdout)

        val gateResult =
            runPackagedMcpProcessIn(
                tempRoot = tempRoot,
                args = listOf("settings", "risk-gate", "cautious"),
                keychainBackend = null,
                timeoutSeconds = CLI_JOURNEY_TIMEOUT_SECONDS,
            )
        assertEquals(0, gateResult.exitCode, gateResult.stderr)
        assertTrue(gateResult.stdout.contains("query_risk_gate: Cautious"), gateResult.stdout)

        return connectionId
    }

    private fun assertCliHasNoConnections(tempRoot: Path) {
        val listAfterDelete =
            runPackagedMcpProcessIn(
                tempRoot = tempRoot,
                args = listOf("connections", "list"),
                keychainBackend = null,
                timeoutSeconds = CLI_JOURNEY_TIMEOUT_SECONDS,
            )
        assertEquals(0, listAfterDelete.exitCode, listAfterDelete.stderr)
        assertTrue(
            listAfterDelete.stdout.contains("No connections."),
            "Expected empty store after delete, got: ${listAfterDelete.stdout}",
        )
    }

    private suspend fun assertConnectionListed(
        client: Client,
        connection: ConnectionDef,
        password: String,
    ) {
        val body =
            callToolObject(
                client,
                "list_connections",
                emptyMap(),
                connection,
                password,
            )
        assertEquals("1", body.getValue("total_count").jsonPrimitive.content)
        val connections = body.getValue("connections").jsonArray
        assertTrue(
            connections.any {
                it.jsonObject.getValue("id").jsonPrimitive.content == connection.id &&
                    it.jsonObject.getValue("name").jsonPrimitive.content == connection.name
            },
            body.toString(),
        )
    }

    private suspend fun assertTablesListed(
        client: Client,
        connection: ConnectionDef,
        password: String,
        scanEventsTable: String,
    ) {
        val body =
            callToolObject(
                client,
                "list_tables",
                mapOf("connection_id" to connection.id),
                connection,
                password,
            )
        val tables = body.getValue("tables").jsonArray
        assertTrue(
            tables.any {
                it.jsonObject
                    .getValue("name")
                    .jsonPrimitive
                    .content
                    .equals(
                        "customers",
                        ignoreCase = true,
                    )
            },
            "customers missing from list_tables: $body",
        )
        assertTrue(
            tables.any {
                it.jsonObject
                    .getValue("qualified_name")
                    .jsonPrimitive
                    .content
                    .equals(
                        scanEventsTable,
                        ignoreCase = true,
                    )
            },
            "$scanEventsTable missing from list_tables: $body",
        )
    }

    private suspend fun assertOrdersDescribed(
        client: Client,
        connection: ConnectionDef,
        target: DialectJourneyTarget,
    ) {
        val body =
            callToolObject(
                client,
                "describe_table",
                mapOf(
                    "connection_id" to connection.id,
                    "schema" to target.defaultSchema,
                    "table" to "orders",
                ),
                connection,
                target.password,
            )
        val columns =
            body
                .getValue("columns")
                .jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                .toSet()
        assertTrue(
            setOf("id", "customer_id", "status", "total").all { it in columns },
            body.toString(),
        )
        assertTrue(body.getValue("indexes").jsonArray.isNotEmpty(), body.toString())
        assertTrue(
            body.getValue("foreign_keys").jsonArray.any {
                it.jsonObject
                    .getValue("referenced_table")
                    .jsonPrimitive
                    .content
                    .equals(
                        "customers",
                        ignoreCase = true,
                    )
            },
            body.toString(),
        )
    }

    private suspend fun assertCatalogSearched(
        client: Client,
        connection: ConnectionDef,
        password: String,
    ) {
        val body =
            callToolObject(
                client,
                "search_catalog",
                mapOf("connection_id" to connection.id, "query" to "customer"),
                connection,
                password,
            )
        assertTrue(body.getValue("match_count").jsonPrimitive.int >= 2, body.toString())
        val matchedNames =
            body.getValue("matches").jsonArray.map {
                it.jsonObject.getValue("name").jsonPrimitive.content
            }
        assertTrue(
            matchedNames.any { it.equals("customers", ignoreCase = true) } &&
                matchedNames.any { it.equals("orders", ignoreCase = true) },
            body.toString(),
        )
    }

    private suspend fun assertJoinPathsFound(
        client: Client,
        connection: ConnectionDef,
        target: DialectJourneyTarget,
    ) {
        val body =
            callToolObject(
                client,
                "find_join_paths",
                mapOf(
                    "connection_id" to connection.id,
                    "from_table" to "${target.defaultSchema}.customers",
                    "to_table" to "${target.defaultSchema}.orders",
                ),
                connection,
                target.password,
            )
        val paths = body.getValue("paths").jsonArray
        assertTrue(paths.isNotEmpty(), body.toString())
        val firstSteps = paths.first().jsonObject.getValue("steps").jsonArray
        assertTrue(
            firstSteps.any {
                it.jsonObject.getValue("kind").jsonPrimitive.content == "foreign_key"
            },
            body.toString(),
        )
    }

    private suspend fun assertScanEventsBlockedUnderCautious(
        client: Client,
        connection: ConnectionDef,
        target: DialectJourneyTarget,
    ) {
        val assessed =
            callToolObject(
                client,
                "assess_query",
                mapOf(
                    "connection_id" to connection.id,
                    "sql" to target.blockedScanSql,
                    "default_schema" to target.defaultSchema,
                ),
                connection,
                target.password,
            )
        val assessRisk = assessed.getValue("risk").jsonObject
        assertEquals(
            "Blocked",
            assessRisk.getValue("state").jsonPrimitive.content,
            assessed.toString(),
        )
        assertEquals(
            "Cautious",
            assessRisk.getValue("effective_gate").jsonPrimitive.content,
            assessed.toString(),
        )

        val blockedRun =
            client.callTool(
                "run_query",
                mapOf(
                    "connection_id" to connection.id,
                    "sql" to target.blockedScanSql,
                    "default_schema" to target.defaultSchema,
                ),
            )
        assertEquals(true, blockedRun.isError, blockedRun.text())
        val blockedText = blockedRun.text()
        assertPayloadIsPrivate(blockedText, connection, target.password)
        val blockedBody = Json.parseToJsonElement(blockedText).jsonObject
        assertEquals("risk_gate", blockedBody.getValue("error").jsonPrimitive.content)
        val runRisk = blockedBody.getValue("risk").jsonObject
        assertEquals("Blocked", runRisk.getValue("state").jsonPrimitive.content, blockedText)
        assertEquals(
            "Cautious",
            runRisk.getValue("effective_gate").jsonPrimitive.content,
            blockedText,
        )
    }

    private suspend fun assertHistoryPersisted(
        client: Client,
        connection: ConnectionDef,
        password: String,
        expectedMinEntries: Int,
    ) {
        val historyResult =
            client.callTool(
                "list_query_history",
                mapOf("connection_id" to connection.id),
            )
        assertNotEquals(true, historyResult.isError, historyResult.text())
        val historyText = historyResult.text()
        assertPayloadIsPrivate(historyText, connection, password)
        val entries = Json.parseToJsonElement(historyText).jsonArray
        assertTrue(
            entries.size >= expectedMinEntries,
            "Expected >= $expectedMinEntries history entries, got ${entries.size}: $historyText",
        )
        assertTrue(
            entries.any { it.jsonObject["risk_gate_state"]?.jsonPrimitive?.content == "Blocked" },
            "Expected blocked scan_events entry in history: $historyText",
        )
    }

    private suspend fun deleteConnectionTwoStep(client: Client, connectionId: String) {
        val firstDelete =
            client.callTool(
                "delete_connection",
                mapOf("connection_id" to connectionId),
            )
        assertEquals(true, firstDelete.isError, firstDelete.text())
        val firstBody = Json.parseToJsonElement(firstDelete.text()).jsonObject
        assertEquals("confirmation_required", firstBody.getValue("error").jsonPrimitive.content)
        val confirmation = firstBody.getValue("confirmation").jsonObject

        val confirmedDelete =
            client.callTool(
                "delete_connection",
                mapOf("connection_id" to connectionId, "confirmation" to confirmation),
            )
        assertNotEquals(true, confirmedDelete.isError, confirmedDelete.text())
        val deletedBody = Json.parseToJsonElement(confirmedDelete.text()).jsonObject
        assertEquals(connectionId, deletedBody.getValue("deleted").jsonPrimitive.content)
    }

    private suspend fun callToolObject(
        client: Client,
        name: String,
        arguments: Map<String, Any?>,
        connection: ConnectionDef,
        password: String,
    ): JsonObject {
        val result = client.callTool(name, arguments)
        assertNotEquals(true, result.isError, "$name failed: ${result.text()}")
        val text = result.text()
        assertPayloadIsPrivate(text, connection, password)
        return Json.parseToJsonElement(text).jsonObject
    }
}

private data class DialectJourneyTarget(
    val slug: String,
    val cliDialect: String,
    val connection: ConnectionDef,
    val password: String,
    val defaultSchema: String,
    val scanEventsTable: String,
    val blockedScanSql: String,
    val customersPreviewSql: String,
)
