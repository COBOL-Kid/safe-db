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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class QueryToolsTest {
    @Test
    fun sqlSuccessReturnsReceiptWithPreviewAndNoTaggedCells() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
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
            assertEquals("false", parsed.getValue("preview_truncated").jsonPrimitive.content)
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
        service.runQueryResult =
            sampleMcpRunResult(rowCount = 50, truncated = true, warnings = listOf("engine warning"))
        withTempMcpClient(service) { client ->
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
            assertEquals("true", parsed.getValue("truncated").jsonPrimitive.content)
            assertTrue(
                parsed.getValue("warnings").jsonArray.any {
                    it.jsonPrimitive.content == "engine warning"
                }
            )
            assertEquals(PREVIEW_ROW_LIMIT, parsed.getValue("preview").jsonArray.size)
            assertEquals("true", parsed.getValue("preview_truncated").jsonPrimitive.content)
            val resultId = parsed.getValue("result_id").jsonPrimitive.content
            val page = client.callTool("get_result_rows", mapOf("result_id" to resultId)).json()
            assertEquals("true", page.getValue("truncated").jsonPrimitive.content)
            assertEquals("false", page.getValue("page_truncated").jsonPrimitive.content)
        }
    }

    @Test
    fun sqlParseDoesNotUseTheSchemaCache() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
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
    fun sqlSchemaAndUnexpectedExecutionFailuresAreToolErrors() = runBlocking {
        val service = queryService()
        service.schemaError = IllegalStateException("catalog unavailable")
        withTempMcpClient(service) { client ->
            val schemaFailure =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, schemaFailure.isError)
            assertEquals("catalog unavailable", schemaFailure.text())
            assertTrue(service.runQueryRequests.isEmpty())

            service.schemaError = null
            service.runQueryError = IllegalStateException("unexpected executor failure")
            val executionFailure =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, executionFailure.isError)
            assertEquals("unexpected executor failure", executionFailure.text())
        }
        assertEquals(2, service.schemaCalls.size)
        assertEquals(1, service.runQueryRequests.size)
    }

    @Test
    fun specPathRunsWithoutGetSchema() = runBlocking {
        val service = queryService()
        val specJson = SafeDbJson.lenient.encodeToJsonElement(sampleMcpQuerySpec())
        withTempMcpClient(service) { client ->
            val result =
                client.callTool("run_query", mapOf("connection_id" to "c1", "spec" to specJson))
            assertFalse(result.isError == true)
            assertEquals("2", result.json().getValue("row_count").jsonPrimitive.content)
        }
        assertTrue(service.schemaCalls.isEmpty())
        assertEquals(sampleMcpQuerySpec(), service.runQueryRequests.single().spec)
    }

    @Test
    fun sqlDefaultSchemaQualifiesUnqualifiedTables() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
            val withArg =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM customers",
                        "default_schema" to "public",
                    ),
                )
            assertFalse(withArg.isError == true)
            assertEquals("public", service.runQueryRequests.single().spec.tables.single().schema)

            service.settings =
                Settings(
                    blockedSchemas = listOf("audit"),
                    defaultConnectionId = "c1",
                    defaultSchema = "public",
                )
            val fromSettings =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM customers"),
                )
            assertFalse(fromSettings.isError == true)
            assertEquals("public", service.runQueryRequests.last().spec.tables.single().schema)

            val requestsAfterMatchingConnection = service.runQueryRequests.size
            service.settings =
                Settings(
                    blockedSchemas = listOf("audit"),
                    defaultConnectionId = "other",
                    defaultSchema = "public",
                )
            val otherConnection =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM customers"),
                )
            assertEquals(true, otherConnection.isError)
            val mismatched = otherConnection.json()
            assertEquals("parse", mismatched.getValue("error").jsonPrimitive.content)
            assertTrue(
                mismatched
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains(
                        "schema",
                        ignoreCase = true,
                    )
            )
            assertEquals(requestsAfterMatchingConnection, service.runQueryRequests.size)
        }
    }

    @Test
    fun parseXorAndMissingConnectionAreErrors() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
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

            val specAsString =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "spec" to "not-an-object"),
                )
            assertEquals(true, specAsString.isError)
            assertEquals("parse", specAsString.json().getValue("error").jsonPrimitive.content)

            val confirmationAsString =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "confirmation" to "not-an-object",
                    ),
                )
            assertEquals(true, confirmationAsString.isError)
            assertEquals(
                "parse",
                confirmationAsString.json().getValue("error").jsonPrimitive.content,
            )
        }
        assertTrue(service.runQueryRequests.isEmpty())
    }

    @Test
    fun malformedSpecAndConfirmationObjectsAreParseErrors() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
            val malformedSpec =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "spec" to JsonObject(mapOf("tables" to JsonPrimitive("not-an-array"))),
                    ),
                )
            assertEquals(true, malformedSpec.isError)
            assertEquals("parse", malformedSpec.json().getValue("error").jsonPrimitive.content)

            val missingConfirmationFields =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "confirmation" to JsonObject(mapOf("connection_id" to JsonPrimitive("c1"))),
                    ),
                )
            assertEquals(true, missingConfirmationFields.isError)
            assertEquals(
                "parse",
                missingConfirmationFields.json().getValue("error").jsonPrimitive.content,
            )

            val invalidConfirmationCondition =
                JsonObject(
                    mapOf(
                        "connection_id" to JsonPrimitive("c1"),
                        "connection_fingerprint" to JsonPrimitive("fp-conn"),
                        "query_fingerprint" to JsonPrimitive("fp-query"),
                        "conditions" to
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "reason_code" to JsonPrimitive("NotAReason"),
                                            "condition_key" to JsonPrimitive("key"),
                                        )
                                    )
                                )
                            ),
                    )
                )
            val invalidCondition =
                client.callTool(
                    "run_query",
                    mapOf(
                        "connection_id" to "c1",
                        "sql" to "SELECT id FROM public.customers",
                        "confirmation" to invalidConfirmationCondition,
                    ),
                )
            assertEquals(true, invalidCondition.isError)
            assertEquals("parse", invalidCondition.json().getValue("error").jsonPrimitive.content)
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
        withTempMcpClient(service) { client ->
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
    fun structuredErrorsIncludeWarningsAndFallBackToStaticRisk() = runBlocking {
        val service = queryService()
        val base =
            allowedMcpRisk(
                state = RiskGateState.Blocked,
                severity = QueryRiskSeverity.Elevated,
                score = 42,
                reasons = listOf(RiskDecisionReason("static_risk", "Static analysis blocked it.")),
            )
        val evaluation = base.copy(staticAssessment = base.finalAssessment, finalAssessment = null)
        service.runQueryError =
            QueryFailureException(
                QueryError.RiskGate(evaluation, sampleMcpQuerySpec()),
                warnings = listOf("Query plan unavailable; using static risk."),
            )

        withTempMcpClient(service) { client ->
            val result =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, result.isError)
            val body = result.json()
            assertEquals(
                "Query plan unavailable; using static risk.",
                body.getValue("warnings").jsonArray.single().jsonPrimitive.content,
            )
            val risk = body.getValue("risk").jsonObject
            assertEquals("Elevated", risk.getValue("severity").jsonPrimitive.content)
            assertEquals("42", risk.getValue("score").jsonPrimitive.content)
            assertEquals(
                "Static analysis blocked it.",
                risk.getValue("reasons").jsonArray.single().jsonPrimitive.content,
            )
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
        withTempMcpClient(service) { client ->
            val first =
                client.callTool(
                    "run_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, first.isError)
            val body = first.json()
            assertEquals("confirmation_required", body.getValue("error").jsonPrimitive.content)
            assertEquals(
                listOf("Query plan assessment is unavailable: timeout."),
                body.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(body.getValue("message").jsonPrimitive.content.isNotBlank())
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
        withTempMcpClient(service) { client ->
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
        withTempMcpClient(service) { client ->
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
        withTempMcpClient(service) { client ->
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

    @Test
    fun successReceiptIncludesResultIdAndJsonlArtifact() = runBlocking {
        val resultsDir = Files.createTempDirectory("safedb-mcp-results")
        try {
            val service = queryService()
            withMcpClient(createSafeDbMcpServer(service, resultsDir = resultsDir)) { client ->
                val parsed =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id, email FROM public.customers",
                            ),
                        )
                        .json()
                val resultId = parsed.getValue("result_id").jsonPrimitive.content
                assertTrue(resultId.isNotBlank())
                val artifactPath = parsed.getValue("artifact_path").jsonPrimitive.content
                val artifactBytes = parsed.getValue("artifact_bytes").jsonPrimitive.content.toLong()
                val file = Path.of(artifactPath)
                assertTrue(Files.isRegularFile(file))
                assertEquals(Files.size(file), artifactBytes)
                val lines = Files.readAllLines(file)
                val expected = flattenRows(sampleMcpQueryResult())
                assertEquals(expected.size, lines.size)
                lines.zip(expected).forEach { (line, row) ->
                    assertEquals(
                        row,
                        kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject,
                    )
                }
                assertEquals(2, parsed.getValue("preview").jsonArray.size)
            }
        } finally {
            resultsDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun getResultRowsPagesWithCapAndSummarizeUsesStoredId() = runBlocking {
        val resultsDir = Files.createTempDirectory("safedb-mcp-results")
        try {
            val service = queryService()
            service.runQueryResult = sampleMcpRunResult(rowCount = 60, truncated = true)
            withMcpClient(createSafeDbMcpServer(service, resultsDir = resultsDir)) { client ->
                val receipt =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id FROM public.customers LIMIT 60",
                            ),
                        )
                        .json()
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                assertEquals(PREVIEW_ROW_LIMIT, receipt.getValue("preview").jsonArray.size)

                val page = client.callTool("get_result_rows", mapOf("result_id" to resultId)).json()
                assertEquals(resultId, page.getValue("result_id").jsonPrimitive.content)
                assertEquals("0", page.getValue("offset").jsonPrimitive.content)
                assertEquals("60", page.getValue("row_count").jsonPrimitive.content)
                assertEquals("true", page.getValue("truncated").jsonPrimitive.content)
                assertEquals(GET_RESULT_ROWS_MAX, page.getValue("rows").jsonArray.size)
                assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
                assertEquals(
                    "1",
                    page
                        .getValue("rows")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )

                val capped =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "limit" to 100),
                        )
                        .json()
                assertEquals(GET_RESULT_ROWS_MAX, capped.getValue("rows").jsonArray.size)

                val tail =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "offset" to 55, "limit" to 50),
                        )
                        .json()
                assertEquals(5, tail.getValue("rows").jsonArray.size)
                assertEquals("false", tail.getValue("page_truncated").jsonPrimitive.content)
                assertEquals(
                    "56",
                    tail
                        .getValue("rows")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )

                val summary =
                    client.callTool("summarize_result", mapOf("result_id" to resultId)).json()
                assertEquals(resultId, summary.getValue("result_id").jsonPrimitive.content)
                assertEquals("60", summary.getValue("row_count").jsonPrimitive.content)
                assertEquals("true", summary.getValue("truncated").jsonPrimitive.content)
                val columns = summary.getValue("columns").jsonArray
                assertEquals(2, columns.size)
                val idCol = columns[0].jsonObject
                assertEquals("id", idCol.getValue("name").jsonPrimitive.content)
                assertEquals("0", idCol.getValue("null_count").jsonPrimitive.content)
                assertEquals("1", idCol.getValue("min").jsonPrimitive.content)
                assertEquals("60", idCol.getValue("max").jsonPrimitive.content)
                assertEquals(DISTINCT_VALUE_LIMIT, idCol.getValue("distinct").jsonArray.size)
                assertEquals("true", idCol.getValue("distinct_truncated").jsonPrimitive.content)
            }
        } finally {
            resultsDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun getResultRowsCoercesPagingArguments() = runBlocking {
        val service = queryService()
        service.runQueryResult = sampleMcpRunResult(rowCount = 3)
        withTempMcpClient(service) { client ->
            val receipt =
                client
                    .callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id FROM public.customers LIMIT 3",
                        ),
                    )
                    .json()
            val resultId = receipt.getValue("result_id").jsonPrimitive.content

            val clamped =
                client
                    .callTool(
                        "get_result_rows",
                        mapOf("result_id" to resultId, "offset" to -7, "limit" to 0),
                    )
                    .json()
            assertEquals("0", clamped.getValue("offset").jsonPrimitive.content)
            assertEquals(1, clamped.getValue("rows").jsonArray.size)
            assertEquals(
                "1",
                clamped
                    .getValue("rows")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )

            val numericStrings =
                client
                    .callTool(
                        "get_result_rows",
                        mapOf("result_id" to resultId, "offset" to "1", "limit" to "2"),
                    )
                    .json()
            assertEquals("1", numericStrings.getValue("offset").jsonPrimitive.content)
            assertEquals(2, numericStrings.getValue("rows").jsonArray.size)
            assertEquals(
                "2",
                numericStrings
                    .getValue("rows")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content,
            )

            val invalidValues =
                client
                    .callTool(
                        "get_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "offset" to JsonObject(emptyMap()),
                            "limit" to "not-a-number",
                        ),
                    )
                    .json()
            assertEquals("0", invalidValues.getValue("offset").jsonPrimitive.content)
            assertEquals(3, invalidValues.getValue("rows").jsonArray.size)
        }
    }

    @Test
    fun artifactWriteFailureStillReturnsPageableInMemoryResult() = runBlocking {
        val resultsDir = Files.createTempDirectory("safedb-mcp-results")
        try {
            val service = queryService()
            val store =
                ResultStore(
                    resultsDir = resultsDir,
                    onAfterJsonlWrite = { error("simulated artifact failure") },
                )
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    resultsDir = resultsDir,
                    resultStore = store,
                )
            ) { client ->
                val receipt =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id FROM public.customers",
                            ),
                        )
                        .json()
                assertFalse(receipt.containsKey("artifact_path"))
                assertFalse(receipt.containsKey("artifact_bytes"))
                val resultId = receipt.getValue("result_id").jsonPrimitive.content

                val page =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "offset" to 1, "limit" to 1),
                        )
                        .json()
                assertEquals(1, page.getValue("rows").jsonArray.size)
                assertEquals(
                    "2",
                    page
                        .getValue("rows")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )
                Files.list(resultsDir).use { files -> assertEquals(0L, files.count()) }
            }
        } finally {
            resultsDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownAndExpiredResultIsNotFound() = runBlocking {
        val resultsDir = Files.createTempDirectory("safedb-mcp-results")
        try {
            val service = queryService()
            val clock = mutableListOf(1_000L)
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    nowMs = { clock.single() },
                    resultStoreTtlMs = 100,
                    resultsDir = resultsDir,
                )
            ) { client ->
                val missing = client.callTool("get_result_rows", emptyMap())
                assertEquals(true, missing.isError)
                assertEquals("result_id is required", missing.text())

                val unknown = client.callTool("get_result_rows", mapOf("result_id" to "missing"))
                assertEquals(true, unknown.isError)
                assertEquals("Result not found", unknown.text())

                val receipt =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id, email FROM public.customers",
                            ),
                        )
                        .json()
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                val live = client.callTool("get_result_rows", mapOf("result_id" to resultId))
                assertFalse(live.isError == true)

                clock[0] = 1_100
                val expired = client.callTool("get_result_rows", mapOf("result_id" to resultId))
                assertEquals(true, expired.isError)
                assertEquals("Result not found", expired.text())

                val expiredSummary =
                    client.callTool("summarize_result", mapOf("result_id" to resultId))
                assertEquals(true, expiredSummary.isError)
                assertEquals("Result not found", expiredSummary.text())
            }
        } finally {
            resultsDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun receiptOmitsNullArtifactKeys() {
        val receipt =
            QueryReceipt(
                columns = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
                risk = riskSummary(allowedMcpRisk()),
                preview = emptyList(),
                previewTruncated = false,
                resultId = "rid",
                artifactPath = null,
                artifactBytes = null,
            )
        val omitted = toolJson.encodeToString(receipt)
        assertTrue(omitted.contains("\"result_id\""))
        assertFalse(omitted.contains("\"artifact_path\""))
        assertFalse(omitted.contains("\"artifact_bytes\""))

        val included =
            toolJson.encodeToString(
                receipt.copy(artifactPath = "/tmp/x.jsonl", artifactBytes = 12L)
            )
        assertTrue(included.contains("\"artifact_path\""))
        assertTrue(included.contains("\"artifact_bytes\""))
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
