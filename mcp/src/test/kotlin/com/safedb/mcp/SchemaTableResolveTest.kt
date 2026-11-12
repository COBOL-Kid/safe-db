package com.safedb.mcp

import com.safedb.model.ColumnInfo
import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaTableResolveTest {
    @Test
    fun resolvesExactQualifiedReference() {
        val tables = sampleMcpSchema().tables
        val resolved =
            assertIs<Arg.Ok<TableInfo>>(resolveTableReference("public.customers", null, tables))
        assertEquals("public.customers", resolved.value.qualifiedName())
    }

    @Test
    fun resolvesCaseInsensitiveUniqueReference() {
        val tables = sampleMcpSchema().tables + table("SAFEDB", "CUSTOMERS")
        val resolved =
            assertIs<Arg.Ok<TableInfo>>(resolveTableReference("safedb.customers", null, tables))
        assertEquals("SAFEDB.CUSTOMERS", resolved.value.qualifiedName())
    }

    @Test
    fun resolvesUnqualifiedUniqueReferenceWithoutDefaultSchema() {
        val tables = sampleMcpSchema().tables
        val resolved = assertIs<Arg.Ok<TableInfo>>(resolveTableReference("customers", null, tables))
        assertEquals("public.customers", resolved.value.qualifiedName())
    }

    @Test
    fun unqualifiedDuplicateRequiresDefaultSchema() {
        val tables = sampleMcpSchema().tables + table("archive_orders", "orders")
        val err = assertIs<Arg.Err>(resolveTableReference("orders", null, tables))
        assertEquals("invalid_arguments", err.error())
        assertTrue(err.message().contains("default_schema is required to disambiguate 'orders'"))
    }

    @Test
    fun caseOnlyMatchReportsAmbiguityWithQualifiedNames() {
        val tables = listOf(table("public", "Foo"), table("public", "FOO"))
        val err = assertIs<Arg.Err>(resolveTable("public", "foo", tables))
        assertEquals("invalid_arguments", err.error())
        val message = err.message()
        assertTrue(message.contains("public.Foo"))
        assertTrue(message.contains("public.FOO"))
    }

    @Test
    fun exactQualifiedMatchWinsWhenCaseVariantsCoexist() {
        val tables = listOf(table("public", "Foo"), table("public", "FOO"))
        val foo = assertIs<Arg.Ok<TableInfo>>(resolveTable("public", "Foo", tables))
        assertEquals("public.Foo", foo.value.qualifiedName())
        val upper = assertIs<Arg.Ok<TableInfo>>(resolveTable("public", "FOO", tables))
        assertEquals("public.FOO", upper.value.qualifiedName())
        val err = assertIs<Arg.Err>(resolveTable("public", "foo", tables))
        assertEquals("invalid_arguments", err.error())
        val message = err.message()
        assertTrue(message.contains("Table reference is ambiguous"))
        assertTrue(message.contains("public.Foo"))
        assertTrue(message.contains("public.FOO"))
    }

    @Test
    fun duplicateQualifiedRowsAreAmbiguous() {
        val tables = listOf(table("public", "orders"), table("public", "orders"))
        val err = assertIs<Arg.Err>(resolveTable("public", "orders", tables))
        assertEquals("invalid_arguments", err.error())
        assertTrue(err.message().contains("Table reference is ambiguous"))
    }

    @Test
    fun duplicateExactUnqualifiedRowsAreAmbiguous() {
        val tables = listOf(table("public", "orders"), table("public", "orders"))
        val direct = assertIs<Arg.Err>(resolveUnqualifiedTable("orders", tables))
        assertEquals("invalid_arguments", direct.error())
        assertTrue(direct.message().contains("Table reference is ambiguous"))

        val viaReference = assertIs<Arg.Err>(resolveTableReference("orders", null, tables))
        assertEquals("invalid_arguments", viaReference.error())
        assertTrue(viaReference.message().contains("Table reference is ambiguous"))
    }

    @Test
    fun unknownTableIsNotFoundWithSuggestions() {
        val tables = sampleMcpSchema().tables
        val err = assertIs<Arg.Err>(resolveTableReference("public.missing", null, tables))
        assertEquals("not_found", err.error())
        val message = err.message()
        assertTrue(message.startsWith("Table not found"))
        assertTrue(message.contains("public.customers"))
        assertTrue(message.contains("public.orders"))
    }

    @Test
    fun emptySchemaOrTableIsInvalid() {
        val tables = sampleMcpSchema().tables
        for (reference in listOf(".orders", "public.")) {
            val err = assertIs<Arg.Err>(resolveTableReference(reference, null, tables))
            assertEquals("invalid_arguments", err.error())
            assertEquals("table names must be schema.table or table", err.message())
        }
    }
}

private fun table(schema: String, name: String): TableInfo =
    TableInfo(
        schema = schema,
        name = name,
        columns = listOf(ColumnInfo(name = "id", dataType = "int", nullable = false)),
        indexes = emptyList(),
    )

private fun Arg.Err.json(): JsonObject =
    Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject

private fun Arg.Err.error(): String = json().getValue("error").jsonPrimitive.content

private fun Arg.Err.message(): String = json().getValue("message").jsonPrimitive.content
