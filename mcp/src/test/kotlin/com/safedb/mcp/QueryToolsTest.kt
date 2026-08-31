package com.safedb.mcp

import com.safedb.model.SafeDbJson
import com.safedb.model.Settings
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class QueryToolsTest {
    @Test
    fun sqlSuccessReturnsReceiptWithPreviewAndNoTaggedCells() = runBlocking {
        val service = queryService()
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val result =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id, email FROM public.customers",
                    ),
                )
            assertFalse(result.isError == true)
            val text = result.text()
            assertFalse(text.contains("\"kind\""))
            assertFalse(text.contains("localhost"))
            assertFalse(text.contains("should-not-leak"))
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            assertEquals(
                "id",
                parsed.columns()[0].jsonObject.getValue("name").jsonPrimitive.content,
            )
            assertEquals(
                "int",
                parsed.columns()[0].jsonObject.getValue("data_type").jsonPrimitive.content,
            )
            assertEquals("2", parsed.getValue("row_count").jsonPrimitive.content)
            assertEquals("false", parsed.getValue("truncated").jsonPrimitive.content)
            assertTrue(
                parsed.getValue("warnings").jsonArray.any {
                    it.jsonPrimitive.content.contains("defaulting to")
                }
            )
            val risk = parsed.getValue("risk").jsonObject
            assertEquals("Allowed", risk.getValue("state").jsonPrimitive.content)
            assertEquals("Minimal", risk.getValue("severity").jsonPrimitive.content)
            assertEquals("Standard", risk.getValue("effective_gate").jsonPrimitive.content)
            val preview = parsed.getValue("preview").jsonArray
            assertEquals(2, preview.size)
            assertEquals("1", preview[0].jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(
                "user1@example.com",
                preview[0].jsonObject.getValue("email").jsonPrimitive.content,
            )
        }
        assertEquals(listOf("c1"), service.schemaCalls)
        assertEquals(1, service.runQueryRequests.size)
        val spec = service.runQueryRequests.single().spec
        assertEquals("customers", spec.tables.single().name)
        assertEquals("public", spec.tables.single().schema)
        assertNull(service.runQueryRequests.single().confirmation)
    }

    @Test
    fun previewIsCappedWhileRowCountKeepsTheFetchedSize() = runBlocking {
        val service = queryService()
        service.runQueryResult = sampleMcpRunResult(rowCount = 50)
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val parsed =
                client
                    .callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id FROM public.customers LIMIT 50",
                        ),
                    )
                    .json()
            assertEquals("50", parsed.getValue("row_count").jsonPrimitive.content)
            assertEquals(PREVIEW_ROW_LIMIT, parsed.getValue("preview").jsonArray.size)
        }
    }

    @Test
    fun sqlParseDoesNotUseTheSchemaCache() = runBlocking {
        val service = queryService()
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            client.callTool("list_tables", mapOf("connection_id" to "c1"))
            client.callTool(
                "run_query",
                mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
            )
            assertEquals(listOf("c1", "c1"), service.schemaCalls)
            assertEquals(1, service.runQueryRequests.size)
        }
    }

    @Test
    fun specPathRunsWithoutGetSchema() = runBlocking {
        val service = queryService()
        val specJson = SafeDbJson.lenient.encodeToJsonElement(sampleMcpQuerySpec())
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val result =
                client.callTool("run_query", mapOf("connection_id" to "c1", "spec" to specJson))
            assertFalse(result.isError == true)
            assertEquals("2", result.json().getValue("row_count").jsonPrimitive.content)
        }
        assertTrue(service.schemaCalls.isEmpty())
        assertEquals(sampleMcpQuerySpec(), service.runQueryRequests.single().spec)
    }

    @Test
    fun parseXorAndMissingConnectionAreErrors() = runBlocking {
        val service = queryService()
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val missingId = client.callTool("run_query", emptyMap())
            assertEquals(true, missingId.isError)
            assertEquals("connection_id is required", missingId.text())

            val unknown =
                client.callTool("run_query", mapOf("connection_id" to "nope", "sql" to "SELECT 1"))
            assertEquals(true, unknown.isError)
            assertEquals("Connection not found", unknown.text())

            val neither = client.callTool("run_query", mapOf("connection_id" to "c1"))
            assertEquals(true, neither.isError)
            assertEquals("sql or spec is required", neither.text())

            val both =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "spec" to SafeDbJson.lenient.encodeToJsonElement(sampleMcpQuerySpec()),
                    ),
                )
            assertEquals(true, both.isError)
            assertEquals("Provide sql or spec, not both", both.text())

            val parsed =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "DELETE FROM public.customers"),
                )
            assertEquals(true, parsed.isError)
            val body = parsed.json()
            assertEquals("parse", body.getValue("error").jsonPrimitive.content)
            assertTrue(body.getValue("message").jsonPrimitive.content.isNotBlank())
        }
        assertTrue(service.runQueryRequests.isEmpty())
    }

    @Test
    fun riskGateIsAStructuredToolError() = runBlocking {
        val service = queryService()
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
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val result =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, result.isError)
            val body = result.json()
            assertEquals("risk_gate", body.getValue("error").jsonPrimitive.content)
            assertTrue(body.getValue("message").jsonPrimitive.content.contains("risk gate"))
            assertEquals(
                "Blocked",
                body.getValue("risk").jsonObject.getValue("state").jsonPrimitive.content,
            )
            assertFalse(body.containsKey("preview"))
        }
    }

    @Test
    fun confirmationRoundTripPassesTheEchoedObject() = runBlocking {
        val service = queryService()
        val confirmation =
            QueryExecutionConfirmation(
                connectionId = "c1",
                connectionFingerprint = "fp-conn",
                queryFingerprint = "fp-query",
                conditions =
                    setOf(
                        QueryConfirmationCondition(
                            QueryConfirmationReasonCode.PlanUnavailable,
                            "ExecutionFailure",
                        )
                    ),
            )
        val requirement =
            QueryConfirmationRequirement(
                confirmation = confirmation,
                reasons =
                    listOf(
                        RiskDecisionReason(
                            "plan_unavailable",
                            "Query plan assessment is unavailable: timeout.",
                        )
                    ),
            )
        val evaluation =
            allowedMcpRisk(
                    state = RiskGateState.ConfirmationRequired,
                    planStatus = QueryPlanStatus.Unavailable,
                    reasons = requirement.reasons,
                )
                .copy(confirmationRequirement = requirement)
        service.runQueryHandler = { request ->
            if (request.confirmation == null) {
                throw QueryFailureException(
                    QueryError.ConfirmationRequired(evaluation, requirement, request.spec)
                )
            }
            if (request.confirmation != confirmation) {
                throw QueryFailureException(
                    QueryError.ConfirmationRequired(evaluation, requirement, request.spec)
                )
            }
            QueryRunResult(sampleMcpQueryResult(), allowedMcpRisk())
        }
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val first =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, first.isError)
            val body = first.json()
            assertEquals("confirmation_required", body.getValue("error").jsonPrimitive.content)
            val echoed = body.getValue("confirmation")
            val success =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "confirmation" to echoed,
                    ),
                )
            assertFalse(success.isError == true)
            assertEquals("2", success.json().getValue("row_count").jsonPrimitive.content)

            val forged = (echoed as JsonObject).toMutableMap()
            forged["connection_fingerprint"] = kotlinx.serialization.json.JsonPrimitive("forged")
            val rejected =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "confirmation" to JsonObject(forged),
                    ),
                )
            assertEquals(true, rejected.isError)
            assertEquals(
                "confirmation_required",
                rejected.json().getValue("error").jsonPrimitive.content,
            )
        }
        assertEquals(3, service.runQueryRequests.size)
        assertNull(service.runQueryRequests[0].confirmation)
        assertEquals(confirmation, service.runQueryRequests[1].confirmation)
        assertEquals("forged", service.runQueryRequests[2].confirmation?.connectionFingerprint)
    }

    @Test
    fun validationAndExecutionErrorsAreCoded() = runBlocking {
        val service = queryService()
        service.runQueryError = QueryFailureException(QueryError.Validation("Bad query."))
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val validation =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, validation.isError)
            assertEquals("validation", validation.json().getValue("error").jsonPrimitive.content)
            assertEquals("Bad query.", validation.json().getValue("message").jsonPrimitive.content)
        }

        service.runQueryError =
            QueryFailureException(QueryError.Compilation("cannot compile", sampleMcpQuerySpec()))
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val compilation =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, compilation.isError)
            assertEquals("compilation", compilation.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "cannot compile",
                compilation.json().getValue("message").jsonPrimitive.content,
            )
        }

        service.runQueryError = QueryFailureException(QueryError.Execution("jdbc down"))
        withMcpClient(createSafeDbMcpServer(service)) { client ->
            val execution =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, execution.isError)
            assertEquals("execution", execution.json().getValue("error").jsonPrimitive.content)
            assertEquals("jdbc down", execution.json().getValue("message").jsonPrimitive.content)
        }
    }
}

private fun queryService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.passwords["c1"] = "should-not-leak"
    service.schemas["c1"] = sampleMcpSchema()
    service.settings = Settings(blockedSchemas = listOf("audit"))
    service.runQueryResult = sampleMcpRunResult()
    return service
}

private fun CallToolResult.text(): String = (content.single() as TextContent).text

private fun CallToolResult.json(): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(text()).jsonObject

private fun JsonObject.columns() = getValue("columns").jsonArray
