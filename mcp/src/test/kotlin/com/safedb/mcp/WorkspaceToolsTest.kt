package com.safedb.mcp

import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.HistoryEntry
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.query.specToSql
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkspaceToolsTest {
    @Test
    fun listSavedQueriesMapsSummariesAndFiltersByConnection() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1", createdAt = "100")
        service.savedQueries += savedQuery("q2", "Remote", "missing", createdAt = "200")

        withTempMcpClient(service) { client ->
            val all = client.callTool("list_saved_queries", emptyMap()).jsonArray()
            assertEquals(2, all.size)
            val first = all[0].jsonObject
            assertEquals("q1", first.string("id"))
            assertEquals("Customers", first.string("name"))
            assertEquals("c1", first.string("connection_id"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect).unwrap(),
                first.string("sql"),
            )
            assertEquals(
                listOf("public.customers"),
                first.getValue("tables").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("100", first.string("created_at"))

            val second = all[1].jsonObject
            assertEquals("q2", second.string("id"))
            assertEquals("-- connection not found", second.string("sql"))

            val filtered =
                client.callTool("list_saved_queries", mapOf("connection_id" to "c1")).jsonArray()
            assertEquals(1, filtered.size)
            assertEquals("q1", filtered.single().jsonObject.string("id"))

            val unknownArgument = client.callTool("list_saved_queries", mapOf("bogus" to true))
            assertError(unknownArgument, "invalid_arguments", "unknown argument 'bogus'")
        }
    }

    @Test
    fun runSavedQueryRunsTheStoredSpecAndParsesConfirmation() = runBlocking {
        val service = workspaceService()
        val spec = sampleMcpQuerySpec()
        service.savedQueries += savedQuery("q1", "Customers", "c1", spec = spec)
        var confirmation: QueryExecutionConfirmation? = null
        service.runQueryHandler = { request ->
            confirmation = request.confirmation
            QueryRunResult(sampleExploreQueryResult(), allowedMcpRisk())
        }

        withTempMcpClient(service) { client ->
            val receipt =
                client.callJson(
                    "run_saved_query",
                    mapOf(
                        "query_id" to "q1",
                        "confirmation" to
                            Json.parseToJsonElement(
                                """{"connection_id":"c1","connection_fingerprint":"cf","query_fingerprint":"qf","conditions":[]}"""
                            ),
                    ),
                )
            assertTrue(receipt.containsKey("result_id"))
            assertEquals("Postgres", receipt.string("dialect"))
            assertEquals("5", receipt.string("row_count"))
            assertEquals("c1", service.runQueryRequests.single().connectionId)
            assertEquals(spec, service.runQueryRequests.single().spec)
            assertEquals("c1", confirmation?.connectionId)
            assertEquals("cf", confirmation?.connectionFingerprint)
            assertEquals("qf", confirmation?.queryFingerprint)
        }
    }

    @Test
    fun runSavedQueryHandlesMissingRecordsAndInvalidConfirmation() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        service.savedQueries += savedQuery("q2", "Remote", "missing")
        withTempMcpClient(service) { client ->
            val unknown = client.callTool("run_saved_query", mapOf("query_id" to "nope"))
            assertError(unknown, "not_found", "Saved query not found")

            val unknownConnection = client.callTool("run_saved_query", mapOf("query_id" to "q2"))
            assertError(unknownConnection, "not_found", "Connection not found")

            val invalidConfirmation =
                client.callTool(
                    "run_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to "nope"),
                )
            assertError(invalidConfirmation, "invalid_arguments", "confirmation must be an object")

            val malformedConfirmation =
                client.callTool(
                    "run_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to Json.parseToJsonElement("{}")),
                )
            assertError(malformedConfirmation, "invalid_arguments", "confirmation is invalid")
        }
    }

    @Test
    fun runSavedQueryRiskGateIsAStructuredToolError() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        val evaluation =
            allowedMcpRisk(
                state = RiskGateState.Blocked,
                severity = QueryRiskSeverity.High,
                score = 80,
                reasons =
                    listOf(RiskDecisionReason("volume", "The query risk gate blocks this query.")),
            )
        service.runQueryError =
            QueryFailureException(QueryError.RiskGate(evaluation, sampleMcpQuerySpec()))
        withTempMcpClient(service) { client ->
            val result = client.callTool("run_saved_query", mapOf("query_id" to "q1"))
            assertEquals(true, result.isError)
            val body = result.json()
            assertEquals("risk_gate", body.getValue("error").jsonPrimitive.content)
            assertTrue(body.getValue("message").jsonPrimitive.content.contains("risk gate"))
            assertEquals(
                "Blocked",
                body.getValue("risk").jsonObject.getValue("state").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun saveQueryValidatesAndPersistsTheNormalizedSpec() = runBlocking {
        val service = workspaceService()
        withTempMcpClient(service) { client ->
            val summary =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Customers",
                        "sql" to "SELECT id, email FROM public.customers",
                    ),
                )
            assertEquals("Customers", summary.string("name"))
            assertEquals("c1", summary.string("connection_id"))
            assertEquals(
                listOf("public.customers"),
                summary.getValue("tables").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(summary.string("sql").contains("SELECT"))
            val saved = service.savedQueries.single()
            assertEquals("Customers", saved.name)
            assertEquals("c1", saved.connectionId)
            assertEquals(2, saved.spec.columns.size)
            assertEquals(listOf("c1"), service.schemaCalls)
        }
    }

    @Test
    fun saveQueryUsesDefaultSchemaAndRejectsInvalidSql() = runBlocking {
        val service = workspaceService()
        service.settings = Settings(defaultConnectionId = "c1", defaultSchema = "public")
        withTempMcpClient(service) { client ->
            val summary =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Customers",
                        "sql" to "SELECT id FROM customers",
                    ),
                )
            assertEquals("Customers", summary.string("name"))

            val explicitDefault =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Orders",
                        "sql" to "SELECT id FROM orders",
                        "default_schema" to "public",
                    ),
                )
            assertEquals("Orders", explicitDefault.string("name"))

            val parse =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Bad",
                        "sql" to "DELETE FROM public.customers",
                    ),
                )
            assertEquals("parse", parse.json().string("error"))

            val blocked =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Blocked",
                        "sql" to "SELECT oid FROM pg_catalog.pg_class",
                    ),
                )
            assertEquals("validation", blocked.json().string("error"))
            assertTrue(blocked.json().string("message").contains("blocked"))

            val unknownConnection =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "nope",
                        "name" to "X",
                        "sql" to "SELECT id FROM customers",
                    ),
                )
            assertError(unknownConnection, "not_found", "Connection not found")
        }
    }

    @Test
    fun deleteSavedQueryRequiresAndConsumesTheConfirmationToken() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        withTempMcpClient(service) { client ->
            val pending = client.callTool("delete_saved_query", mapOf("query_id" to "q1"))
            assertError(
                pending,
                "confirmation_required",
                "Deleting saved query 'Customers' (q1) removes it from this MCP store. " +
                    "Show the user, then retry with the returned confirmation object; do not " +
                    "auto-confirm.",
            )
            assertEquals(
                listOf("This deletes saved query Customers (q1)."),
                pending.json().getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("q1", service.savedQueries.single().id)
            val confirmation = pending.json().getValue("confirmation")

            val deleted =
                client.callTool(
                    "delete_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to confirmation),
                )
            assertFalse(deleted.isError == true)
            assertEquals("q1", deleted.json().string("deleted"))
            assertTrue(service.savedQueries.isEmpty())
        }
    }

    @Test
    fun deleteSavedQueryRejectsStaleTokensAndMalformedConfirmations() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        withTempMcpClient(service) { client ->
            val first =
                client
                    .callTool("delete_saved_query", mapOf("query_id" to "q1"))
                    .json()
                    .getValue("confirmation")
            val second =
                client
                    .callTool("delete_saved_query", mapOf("query_id" to "q1"))
                    .json()
                    .getValue("confirmation")
            assertEquals(first, second)

            val mismatched =
                client.callTool(
                    "delete_saved_query",
                    mapOf(
                        "query_id" to "q1",
                        "confirmation" to
                            Json.parseToJsonElement("""{"query_id":"q1","token":"invented"}"""),
                    ),
                )
            assertError(
                mismatched,
                "confirmation_required",
                "Deleting saved query 'Customers' (q1) removes it from this MCP store. " +
                    "Show the user, then retry with the returned confirmation object; do not " +
                    "auto-confirm.",
            )
            assertEquals(
                listOf("This deletes saved query Customers (q1)."),
                mismatched.json().getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(first, mismatched.json().getValue("confirmation"))

            val malformed =
                client.callTool(
                    "delete_saved_query",
                    mapOf(
                        "query_id" to "q1",
                        "confirmation" to Json.parseToJsonElement("""{"query_id":"q1"}"""),
                    ),
                )
            assertError(malformed, "invalid_arguments", "confirmation is invalid")

            val unknown = client.callTool("delete_saved_query", mapOf("query_id" to "nope"))
            assertError(unknown, "not_found", "Saved query not found")

            val deleted =
                client.callTool(
                    "delete_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to first),
                )
            assertFalse(deleted.isError == true)
            assertTrue(service.savedQueries.isEmpty())
        }
    }

    @Test
    fun listQueryHistoryDefaultsCapsFiltersAndMapsFields() = runBlocking {
        val service = workspaceService()
        repeat(60) { index ->
            service.historyEntries += historyEntry("h$index", connectionId = "c1")
        }
        service.historyEntries +=
            historyEntry(
                "h-error",
                connectionId = "c2",
                connectionName = "Remote",
                error = "boom",
                riskSeverity = null,
            )
        withTempMcpClient(service) { client ->
            val default = client.callTool("list_query_history", emptyMap()).jsonArray()
            assertEquals(HISTORY_DEFAULT_LIMIT, default.size)
            val first = default[0].jsonObject
            assertEquals("h0", first.string("id"))
            assertEquals("c1", first.string("connection_id"))
            assertEquals("Demo", first.string("connection_name"))
            assertEquals("5", first.string("row_count"))
            assertEquals("Low", first.string("risk_severity"))
            assertEquals("7", first.string("risk_final_score"))
            assertEquals("Allowed", first.string("risk_gate_state"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect).unwrap(),
                first.string("sql"),
            )
            assertFalse(first.containsKey("error"))

            val capped = client.callTool("list_query_history", mapOf("limit" to 100)).jsonArray()
            assertEquals(HISTORY_MAX_LIMIT, capped.size)

            val limited = client.callTool("list_query_history", mapOf("limit" to 2)).jsonArray()
            assertEquals(2, limited.size)

            val filtered =
                client.callTool("list_query_history", mapOf("connection_id" to "c2")).jsonArray()
            assertEquals(1, filtered.size)
            val filteredEntry = filtered.single().jsonObject
            assertEquals("h-error", filteredEntry.string("id"))
            assertEquals("Remote", filteredEntry.string("connection_name"))
            assertEquals("-- connection not found", filteredEntry.string("sql"))
            assertEquals("boom", filteredEntry.string("error"))
            assertFalse(filteredEntry.containsKey("risk_severity"))

            assertError(
                client.callTool("list_query_history", mapOf("limit" to 0)),
                "invalid_arguments",
                "limit must be 1 or more",
            )
            assertError(
                client.callTool("list_query_history", mapOf("bogus" to true)),
                "invalid_arguments",
                "unknown argument 'bogus'",
            )
        }
    }

    @Test
    fun workspaceToolAnnotationsAreDocumented() = runBlocking {
        withTempMcpClient(workspaceService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("list_saved_queries").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_query_history").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("delete_saved_query").annotations?.destructiveHint)
            assertNull(tools.getValue("save_query").annotations)
            assertNull(tools.getValue("run_saved_query").annotations)
        }
    }

    @Test
    fun listSavedQueriesAndHistoryTolerateMalformedFilters() = runBlocking {
        val service = workspaceService()
        val malformed = malformedBetweenSpec()
        service.savedQueries += savedQuery("q-bad", "Broken", "c1", spec = malformed)
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        service.historyEntries += historyEntry("h-bad", spec = malformed)
        service.historyEntries += historyEntry("h1")

        withTempMcpClient(service) { client ->
            val saved = client.callTool("list_saved_queries", emptyMap()).jsonArray()
            assertEquals(2, saved.size)
            val brokenSaved = saved[0].jsonObject
            assertEquals("q-bad", brokenSaved.string("id"))
            assertTrue(brokenSaved.string("sql").startsWith("-- invalid query spec:"))
            val healthySaved = saved[1].jsonObject
            assertEquals("q1", healthySaved.string("id"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect).unwrap(),
                healthySaved.string("sql"),
            )

            val history = client.callTool("list_query_history", emptyMap()).jsonArray()
            assertEquals(2, history.size)
            val brokenHistory = history[0].jsonObject
            assertEquals("h-bad", brokenHistory.string("id"))
            assertTrue(brokenHistory.string("sql").startsWith("-- invalid query spec:"))
            val healthyHistory = history[1].jsonObject
            assertEquals("h1", healthyHistory.string("id"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect).unwrap(),
                healthyHistory.string("sql"),
            )
        }
    }
}

private fun malformedBetweenSpec(): QuerySpec =
    sampleMcpQuerySpec()
        .copy(
            filters =
                FilterGroup(
                    id = "g1",
                    children =
                        listOf(
                            FilterNode.Leaf(
                                FilterSpec(
                                    id = "f1",
                                    tableAlias = "customers",
                                    column = "id",
                                    op = FilterOp.Between,
                                    value = null,
                                )
                            )
                        ),
                )
        )

private fun workspaceService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult = QueryRunResult(sampleExploreQueryResult(), allowedMcpRisk())
    return service
}

private fun savedQuery(
    id: String,
    name: String,
    connectionId: String,
    spec: com.safedb.model.QuerySpec = sampleMcpQuerySpec(),
    createdAt: String = "1",
): SavedQuery =
    SavedQuery(
        id = id,
        name = name,
        connectionId = connectionId,
        spec = spec,
        createdAt = createdAt,
    )

private fun historyEntry(
    id: String,
    connectionId: String = "c1",
    connectionName: String = "Demo",
    error: String? = null,
    riskSeverity: String? = "Low",
    spec: QuerySpec = sampleMcpQuerySpec(),
): HistoryEntry =
    HistoryEntry(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        spec = spec,
        rowCount = 5,
        warnings = listOf("sample capped"),
        error = error,
        timestamp = id,
        riskSeverity = riskSeverity,
        riskFinalScore = 7,
        riskGateState = "Allowed",
    )

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()

private fun CallToolResult.jsonArray(): kotlinx.serialization.json.JsonArray =
    Json.parseToJsonElement(text()).jsonArray

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
