package com.safedb.mcp

import com.safedb.model.ResultColumn
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.QueryFailureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AssessQueryToolsTest {
    @Test
    fun assessQueryReturnsColumnsRiskAndNoConfirmation() = runBlocking {
        val service = queryService()
        service.assessQueryResult =
            sampleMcpAssessment(
                columns = listOf(ResultColumn("customers__id", "int")),
                compiledSql = "SELECT id FROM public.customers WHERE id = ?",
                parameterCount = 1,
                warnings = listOf("assessment warning"),
            )
        withTempMcpClient(service) { client ->
            val result =
                client.callTool(
                    "assess_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertFalse(result.isError == true)
            val body = result.json()
            assertEquals("Postgres", body.getValue("dialect").jsonPrimitive.content)
            val columns = body.getValue("columns").jsonArray
            assertEquals(
                "customers__id",
                columns.single().jsonObject.getValue("name").jsonPrimitive.content,
            )
            assertEquals(
                "int",
                columns.single().jsonObject.getValue("data_type").jsonPrimitive.content,
            )
            assertEquals(
                "SELECT id FROM public.customers WHERE id = ?",
                body.getValue("compiled_sql").jsonPrimitive.content,
            )
            assertEquals("1", body.getValue("parameter_count").jsonPrimitive.content)
            assertEquals(
                listOf("assessment warning"),
                body.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                "Allowed",
                body.getValue("risk").jsonObject.getValue("state").jsonPrimitive.content,
            )
            assertEquals("false", body.getValue("confirmation_required").jsonPrimitive.content)
            assertFalse(body.containsKey("reasons"))
        }
        assertTrue(service.runQueryRequests.isEmpty())
        assertEquals(1, service.assessQueryRequests.size)
        assertEquals(
            "customers",
            service.assessQueryRequests.single().spec.tables.single().name,
        )
    }

    @Test
    fun assessQueryReportsConfirmationRequirement() = runBlocking {
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
        service.assessQueryResult =
            sampleMcpAssessment(
                risk =
                    allowedMcpRisk(
                            state = RiskGateState.ConfirmationRequired,
                            planStatus = QueryPlanStatus.Unavailable,
                            reasons = requirement.reasons,
                        )
                        .copy(confirmationRequirement = requirement)
            )
        withTempMcpClient(service) { client ->
            val body =
                client
                    .callTool(
                        "assess_query",
                        mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                    )
                    .json()
            assertEquals("true", body.getValue("confirmation_required").jsonPrimitive.content)
            assertEquals(
                listOf("Query plan assessment is unavailable: timeout."),
                body.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                "ConfirmationRequired",
                body.getValue("risk").jsonObject.getValue("state").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun assessQueryHandlesParseValidationAndArgumentErrors() = runBlocking {
        val service = queryService()
        withTempMcpClient(service) { client ->
            val missingSql = client.callTool("assess_query", mapOf("connection_id" to "c1"))
            assertEquals(true, missingSql.isError)
            assertEquals(
                "sql is required",
                missingSql.json().getValue("message").jsonPrimitive.content,
            )

            val unknownArg =
                client.callTool(
                    "assess_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT 1", "bogus" to 1),
                )
            assertEquals(true, unknownArg.isError)
            assertEquals(
                "unknown argument 'bogus'",
                unknownArg.json().getValue("message").jsonPrimitive.content,
            )

            val unknownConnection =
                client.callTool(
                    "assess_query",
                    mapOf("connection_id" to "nope", "sql" to "SELECT 1"),
                )
            assertEquals(true, unknownConnection.isError)
            assertEquals(
                "not_found",
                unknownConnection.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "Connection not found",
                unknownConnection.json().getValue("message").jsonPrimitive.content,
            )

            val parseFailure =
                client.callTool(
                    "assess_query",
                    mapOf("connection_id" to "c1", "sql" to "DELETE FROM public.customers"),
                )
            assertEquals(true, parseFailure.isError)
            assertEquals(
                "parse",
                parseFailure.json().getValue("error").jsonPrimitive.content,
            )

            service.assessQueryError = QueryFailureException(QueryError.Validation("Bad query."))
            val validation =
                client.callTool(
                    "assess_query",
                    mapOf("connection_id" to "c1", "sql" to "SELECT id FROM public.customers"),
                )
            assertEquals(true, validation.isError)
            assertEquals(
                "validation",
                validation.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "Bad query.",
                validation.json().getValue("message").jsonPrimitive.content,
            )
        }
        assertTrue(service.runQueryRequests.isEmpty())
    }

    @Test
    fun assessQueryHonorsDefaultSchema() = runBlocking {
        val service = queryService()
        service.assessQueryResult = sampleMcpAssessment()
        withTempMcpClient(service) { client ->
            val body =
                client
                    .callTool(
                        "assess_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id FROM customers",
                            "default_schema" to "public",
                        ),
                    )
                    .json()
            assertEquals("0", body.getValue("parameter_count").jsonPrimitive.content)
        }
        assertEquals(
            "public",
            service.assessQueryRequests.single().spec.tables.single().schema,
        )
    }

    @Test
    fun assessQueryToolMetadataIsDocumented() = runBlocking {
        withTempMcpClient(queryService()) { client ->
            val tool = client.listTools().tools.single { it.name == "assess_query" }
            assertEquals(true, tool.annotations?.readOnlyHint)
            assertTrue(tool.outputSchema?.properties?.containsKey("compiled_sql") == true)
        }
    }
}
