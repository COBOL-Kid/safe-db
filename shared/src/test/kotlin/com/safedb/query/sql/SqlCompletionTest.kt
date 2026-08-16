package com.safedb.query.sql

import com.safedb.model.Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlCompletionTest {
    private val schema = sqlTestSchema()

    private fun complete(
        text: String,
        caret: Int = text.length,
        defaultSchema: String? = "public",
        withSchema: Boolean = true,
    ): SqlCompletionResult =
        sqlCompletions(
            SqlCompletionRequest(
                text = text,
                caret = caret,
                dialect = Dialect.Postgres,
                schema = if (withSchema) schema else null,
                defaultSchema = defaultSchema,
            )
        )

    @Test
    fun afterFromOffersTablesAndSchemas() {
        val result = complete("SELECT id FROM ")
        val tables = result.items.filter { it.kind == SqlCompletionKind.Table }.map { it.label }
        assertEquals(listOf("categories", "users"), tables)
        val schemas =
            result.items.filter { it.kind == SqlCompletionKind.SchemaName }.map { it.label }
        assertEquals(listOf("Sales.", "public."), schemas)
    }

    @Test
    fun afterAliasDotOffersThatTablesColumns() {
        val result = complete("SELECT u. FROM users u", caret = "SELECT u.".length)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it.kind == SqlCompletionKind.Column })
        assertTrue(result.items.any { it.label == "email" })
    }

    @Test
    fun afterWhereOffersColumnsAndKeywords() {
        val result = complete("SELECT id FROM users WHERE ")
        assertTrue(result.items.any { it.kind == SqlCompletionKind.Column && it.label == "email" })
        assertTrue(result.items.any { it.kind == SqlCompletionKind.Keyword })
    }

    @Test
    fun multiTableColumnsAreQualifiedAndAliasesOffered() {
        val result =
            complete("SELECT id FROM users u JOIN categories c ON u.category_id = c.id WHERE ")
        assertTrue(result.items.any { it.label == "u.email" })
        assertTrue(result.items.any { it.kind == SqlCompletionKind.Alias && it.label == "c." })
    }

    @Test
    fun prefixFiltersCaseInsensitively() {
        val text = "SELECT id FROM users WHERE em"
        val result = complete(text, caret = text.length)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it.label.startsWith("em", ignoreCase = true) })
        assertEquals(text.length - 2, result.replaceStart)
        assertEquals(text.length, result.replaceEnd)
    }

    @Test
    fun defaultContextOffersKeywords() {
        val result = complete("SEL", caret = 3)
        assertTrue(result.items.any { it.label == "SELECT" })
        assertEquals(0, result.replaceStart)
    }

    @Test
    fun noSchemaMetadataStillOffersKeywords() {
        val fromContext = complete("SELECT id FROM ", withSchema = false)
        assertTrue(fromContext.items.isEmpty())

        val whereContext = complete("SELECT id FROM users WHERE ", withSchema = false)
        assertTrue(whereContext.items.all { it.kind == SqlCompletionKind.Keyword })
    }

    @Test
    fun caretInsideStringOffersNothing() {
        val text = "SELECT id FROM users WHERE name = 'ab'"
        val result = complete(text, caret = text.length - 2)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun schemaQualifiedTableCompletionAfterDot() {
        val text = "SELECT id FROM public.users u WHERE u."
        val result = complete(text, caret = text.length)
        assertTrue(result.items.any { it.label == "created_at" })
    }

    @Test
    fun schemaDotOffersThatSchemasTables() {
        // `public.` used to be scanned as a table named "public", which resolved to nothing and
        // left
        // the popup empty.
        val result = complete("SELECT id FROM public.")
        assertEquals(
            listOf("categories", "users"),
            result.items.filter { it.kind == SqlCompletionKind.Table }.map { it.label },
        )
        assertTrue(result.items.none { it.kind == SqlCompletionKind.Column })

        val sales = complete("SELECT id FROM Sales.")
        assertTrue(sales.items.any { it.label == "Invoices" })
    }

    @Test
    fun unqualifiedPrefixMatchesQualifiedColumnLabels() {
        // With more than one table the labels are qualified (u.email), but the user is typing the
        // column name, so the prefix has to match the segment after the dot.
        val text = "SELECT id FROM users u JOIN categories c ON u.category_id = c.id WHERE em"
        val result = complete(text)
        assertTrue(result.items.any { it.label == "u.email" })
    }

    @Test
    fun completingMidWordReplacesTheWholeWord() {
        val text = "SELECT id FROM users"
        val caret = text.length - 3
        val result = complete(text, caret = caret)
        assertEquals(text.length - "users".length, result.replaceStart)
        assertEquals(text.length, result.replaceEnd)
    }

    @Test
    fun caretAtEndOfLineCommentOffersNothing() {
        val text = "SELECT id -- note"
        assertTrue(complete(text).items.isEmpty())
    }

    @Test
    fun caretAtEndOfUnterminatedStringOffersNothing() {
        val text = "SELECT id FROM users WHERE name = 'ab"
        assertTrue(complete(text).items.isEmpty())
    }
}
