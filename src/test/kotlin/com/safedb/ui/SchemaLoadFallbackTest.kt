package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaLoadFallbackTest {
    private val options = listOf("analytics", "public")

    @Test
    fun `missing restored schema falls back to first option with warning`() {
        val fallback =
            resolveSchemaFallback(
                loaded = true,
                selectedSchema = null,
                schemaOptions = options,
                preferredSchemaWarning = "Query schema \"reporting\" is unavailable.",
            )
        assertEquals(
            SchemaFallback(
                "analytics",
                "Query schema \"reporting\" is unavailable. Showing \"analytics\" instead.",
            ),
            fallback,
        )
    }

    @Test
    fun `unselected connection falls back silently`() {
        val fallback =
            resolveSchemaFallback(
                loaded = true,
                selectedSchema = null,
                schemaOptions = options,
                preferredSchemaWarning = null,
            )
        assertEquals(SchemaFallback("analytics", warning = null), fallback)
    }

    @Test
    fun `successful selection needs no fallback`() {
        assertNull(
            resolveSchemaFallback(
                loaded = true,
                selectedSchema = "public",
                schemaOptions = options,
                preferredSchemaWarning = null,
            )
        )
    }

    @Test
    fun `failed or empty load needs no fallback`() {
        assertNull(
            resolveSchemaFallback(
                loaded = false,
                selectedSchema = null,
                schemaOptions = options,
                preferredSchemaWarning = "Query schema \"reporting\" is unavailable.",
            )
        )
        assertNull(
            resolveSchemaFallback(
                loaded = true,
                selectedSchema = null,
                schemaOptions = emptyList(),
                preferredSchemaWarning = null,
            )
        )
    }
}
