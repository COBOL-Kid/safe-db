package com.safedb.query.sql

import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlCompletionTest {
    private val schema = sqlTestSchema()

    private fun complete(
        text: String,
        caret: Int = text.length,
        defaultSchema: String? = "public",
        withSchema: Boolean = true,
        dialect: Dialect = Dialect.Postgres,
        schema: Schema = this.schema,
    ): SqlCompletionResult =
        sqlCompletions(
            SqlCompletionRequest(
                text = text,
                caret = caret,
                dialect = dialect,
                schema = if (withSchema) schema else null,
                defaultSchema = defaultSchema,
            )
        )

    private fun schemaWithTable(name: String): Schema =
        Schema(
            tables =
                listOf(
                    TableInfo(
                        schema = "public",
                        name = name,
                        columns = listOf(column("id", "int")),
                        indexes = emptyList(),
                    )
                )
        )

    private fun apply(
        text: String,
        result: SqlCompletionResult,
        item: SqlCompletionItem,
    ): String = text.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)

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
    fun defaultSchemaMatchesCaseInsensitively() {
        val result = complete("SELECT id FROM ", defaultSchema = "PUBLIC")
        val tables = result.items.filter { it.kind == SqlCompletionKind.Table }.map { it.label }
        assertEquals(listOf("categories", "users"), tables)
    }

    @Test
    fun nullDefaultSchemaOffersOnlySchemaPrefixes() {
        val result = complete("SELECT id FROM ", defaultSchema = null)
        assertTrue(result.items.none { it.kind == SqlCompletionKind.Table })
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

        val joined = complete("SELECT id FROM users u JOIN public.")
        assertTrue(joined.items.any { it.label == "categories" })
    }

    @Test
    fun schemaTableCompletionsAreLimitedToTableReferencePositions() {
        // Accepting a table after `SELECT public.` would insert SQL that conversion rejects, so
        // an unresolved qualifier only reads as a schema in FROM/JOIN positions.
        val select = complete("SELECT public.")
        assertTrue(select.items.isEmpty())

        val where = complete("SELECT id FROM users WHERE public.")
        assertTrue(where.items.isEmpty())
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

    @Test
    fun decimalDoesNotOpenJunkCompletion() {
        val afterDot = complete("SELECT id FROM users WHERE id = 1.")
        assertTrue(afterDot.items.none { it.kind == SqlCompletionKind.Table })
        assertTrue(afterDot.items.none { it.kind == SqlCompletionKind.Keyword })

        val afterNumber = complete("SELECT id FROM users WHERE id = 1.0")
        assertTrue(afterNumber.items.none { it.kind == SqlCompletionKind.Table })
        assertTrue(afterNumber.items.none { it.kind == SqlCompletionKind.Keyword })
    }

    @Test
    fun caretAtStartOfWordReplacesTheWholeWord() {
        val text = "SELECT id FROM users"
        val caret = text.indexOf("users")
        val result = complete(text, caret = caret)
        assertEquals(caret, result.replaceStart)
        assertEquals(text.length, result.replaceEnd)
        val categories = result.items.single { it.label == "categories" }
        assertEquals("SELECT id FROM categories", apply(text, result, categories))
    }

    @Test
    fun reservedTableNameIsQuotedAndParses() {
        val schema = schemaWithTable("order")
        val text = "SELECT id FROM "
        val result = complete(text, schema = schema)
        val item = result.items.single { it.kind == SqlCompletionKind.Table && it.label == "order" }
        assertEquals("\"order\"", item.insertText)
        val applied = apply(text, result, item)
        assertIs<SqlParseResult.Success>(
            parseSqlToSpec(applied, Dialect.Postgres, schema, "public")
        )
    }

    @Test
    fun spacedTableNameIsQuotedOnMysql() {
        val schema = schemaWithTable("order details")
        val text = "SELECT id FROM "
        val result = complete(text, dialect = Dialect.MySql, schema = schema)
        val item =
            result.items.single {
                it.kind == SqlCompletionKind.Table && it.label == "order details"
            }
        assertEquals("`order details`", item.insertText)
        assertIs<SqlParseResult.Success>(
            parseSqlToSpec(apply(text, result, item), Dialect.MySql, schema, "public")
        )
    }

    @Test
    fun embeddedBracketInTableNameIsQuotedOnMssql() {
        val schema = schemaWithTable("a]b")
        val text = "SELECT id FROM "
        val result = complete(text, dialect = Dialect.Mssql, schema = schema)
        val item = result.items.single { it.kind == SqlCompletionKind.Table && it.label == "a]b" }
        assertEquals("[a]]b]", item.insertText)
        assertIs<SqlParseResult.Success>(
            parseSqlToSpec(apply(text, result, item), Dialect.Mssql, schema, "public")
        )
    }

    @Test
    fun embeddedQuoteInTableNameIsQuotedOnOracle() {
        val schema = schemaWithTable("a\"b")
        val text = "SELECT id FROM "
        val result = complete(text, dialect = Dialect.Oracle, schema = schema)
        val item = result.items.single { it.kind == SqlCompletionKind.Table && it.label == "a\"b" }
        assertEquals("\"a\"\"b\"", item.insertText)
        assertIs<SqlParseResult.Success>(
            parseSqlToSpec(apply(text, result, item), Dialect.Oracle, schema, "public")
        )
    }

    @Test
    fun schemaAndQualifiedColumnInsertTextQuotesEachPart() {
        val from = complete("SELECT id FROM ")
        assertEquals("\"Sales\".", from.items.single { it.label == "Sales." }.insertText)

        val schema =
            Schema(
                tables =
                    listOf(
                        TableInfo(
                            schema = "public",
                            name = "users",
                            columns = listOf(column("id", "int"), column("order", "text")),
                            indexes = emptyList(),
                        ),
                        TableInfo(
                            schema = "public",
                            name = "categories",
                            columns = listOf(column("id", "int")),
                            indexes = emptyList(),
                        ),
                    )
            )
        val where = complete("SELECT id FROM users u JOIN categories c WHERE ", schema = schema)
        assertEquals("u.\"order\"", where.items.single { it.label == "u.order" }.insertText)
    }

    @Test
    fun closedStringAtEofOffersKeywords() {
        val text = "SELECT id FROM users WHERE name = 'ab'"
        val result = complete(text)
        assertTrue(result.items.any { it.kind == SqlCompletionKind.Keyword && it.label == "AND" })
        assertTrue(complete("SELECT id FROM \"users\"").items.isNotEmpty())
        assertTrue(complete("SELECT id /* note */").items.isNotEmpty())
    }

    @Test
    fun completingAtEndOfMidFileLineCommentOffersNothing() {
        val text = "SELECT id -- note\nFROM "
        val newline = text.indexOf('\n')
        assertTrue(complete(text, caret = newline).items.isEmpty())
        assertTrue(complete(text, caret = newline + 1).items.isNotEmpty())
    }

    @Test
    fun foldedAliasInsertsResolvableQualifier() {
        val text = "SELECT  FROM users AS U JOIN categories c ON U.category_id = c.id"
        val result = complete(text, caret = "SELECT ".length)
        val item = result.items.single { it.insertText == "u.email" }
        val parsed = parseSqlToSpec(apply(text, result, item), Dialect.Postgres, schema, "public")
        assertIs<SqlParseResult.Success>(parsed)
        assertEquals("u", parsed.spec.columns.single().tableAlias)
    }

    @Test
    fun oracleFoldedAliasInsertsResolvableQualifier() {
        val text = "SELECT  FROM users AS u JOIN categories c ON u.category_id = c.id"
        val result = complete(text, caret = "SELECT ".length, dialect = Dialect.Oracle)
        val item = result.items.single { it.label == "U.email" }
        // Oracle folds unquoted names to uppercase, so lowercase metadata columns are quoted.
        assertEquals("U.\"email\"", item.insertText)
        val parsed = parseSqlToSpec(apply(text, result, item), Dialect.Oracle, schema, "public")
        assertIs<SqlParseResult.Success>(parsed)
        assertEquals("U", parsed.spec.columns.single().tableAlias)
    }

    @Test
    fun quotedAliasInsertsQuotedQualifier() {
        val text = "SELECT  FROM users AS \"U\" JOIN categories c ON \"U\".category_id = c.id"
        val result = complete(text, caret = "SELECT ".length)
        val item = result.items.single { it.insertText == "\"U\".email" }
        val parsed = parseSqlToSpec(apply(text, result, item), Dialect.Postgres, schema, "public")
        assertIs<SqlParseResult.Success>(parsed)
        assertEquals("U", parsed.spec.columns.single().tableAlias)
    }

    @Test
    fun unaliasedMixedCaseTableInsertsMetadataAlias() {
        val text = "SELECT  FROM Users JOIN categories c ON Users.category_id = c.id"
        val result = complete(text, caret = "SELECT ".length)
        val item = result.items.single { it.label.equals("users.email", ignoreCase = true) }
        assertEquals("users.email", item.insertText)
        val parsed = parseSqlToSpec(apply(text, result, item), Dialect.Postgres, schema, "public")
        assertIs<SqlParseResult.Success>(parsed)
        assertEquals("users", parsed.spec.columns.single().tableAlias)
    }

    @Test
    fun unquotedQualifierPrefersFoldedAliasOverQuotedAlias() {
        val text = "SELECT u. FROM users AS \"U\" JOIN categories u ON \"U\".category_id = u.id"
        val result = complete(text, caret = "SELECT u.".length)
        assertTrue(result.items.any { it.label == "name" })
        assertTrue(result.items.any { it.label == "id" })
        assertTrue(result.items.none { it.label == "email" })
        val name = result.items.single { it.label == "name" }
        val parsed = parseSqlToSpec(apply(text, result, name), Dialect.Postgres, schema, "public")
        assertIs<SqlParseResult.Success>(parsed)
        assertEquals("u", parsed.spec.columns.single().tableAlias)
        assertEquals("name", parsed.spec.columns.single().column)
    }
}
