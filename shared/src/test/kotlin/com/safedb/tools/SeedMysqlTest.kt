package com.safedb.tools

import java.io.BufferedWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedMysqlTest {
    @Test
    fun parsesFlagsAndGeneratorSizes() {
        val options = parseArgs(
            listOf(
                "--reset",
                "--orders", "12",
                "--customers", "8",
                "--products", "4",
                "--categories", "2",
                "--seed", "7",
                "--batch-size", "3",
            ),
        )

        assertTrue(options.reset)
        assertEquals(12, options.generator.orders)
        assertEquals(8, options.generator.customers)
        assertEquals(4, options.generator.products)
        assertEquals(2, options.generator.categories)
        assertEquals(7, options.generator.seed)
        assertEquals(3, options.generator.batchSize)
    }

    @Test
    fun rejectsMalformedArgumentsAndUnsafeIdentifiers() {
        assertEquals(
            "--orders must be a positive integer",
            assertFailsWith<UsageError> { parseArgs(listOf("--orders", "nope")) }.message,
        )
        assertEquals(
            "--products must be greater than or equal to --categories",
            assertFailsWith<UsageError> {
                parseArgs(listOf("--products", "1", "--categories", "2"))
            }.message,
        )
        assertFailsWith<RuntimeException> { sanitizeIdentifier("safe;drop", "database") }
    }

    @Test
    fun generatedSqlIsDeterministicAndBatched() {
        val options = GeneratorOptions(
            database = "fixture_db",
            categories = 2,
            products = 4,
            customers = 5,
            orders = 7,
            seed = 9,
            batchSize = 2,
        )

        val first = generate(options)
        val second = generate(options)

        assertEquals(first, second)
        assertTrue(first.contains("CREATE DATABASE IF NOT EXISTS `fixture_db`"))
        assertTrue(first.contains("INSERT INTO customers"))
        assertTrue(first.contains("INSERT INTO orders"))
        assertTrue(first.count { it == '\n' } > 20)
    }

    @Test
    fun escapesSqlStringsAndRecognizesMysqlContainerImages() {
        assertEquals("'O''Brien\\\\path'", sqlString("O'Brien\\path"))
        assertEquals("NULL", sqlString(null))
        assertEquals("db", parseMysqlContainerLine("db\tmysql:8.4"))
        assertEquals("maria", parseMysqlContainerLine("maria\tmariadb:11"))
        assertNull(parseMysqlContainerLine("cache\tredis:7"))
        assertNull(parseMysqlContainerLine("invalid"))
    }

    private fun generate(options: GeneratorOptions): String {
        val output = StringWriter()
        BufferedWriter(output).use { writer ->
            MysqlFixtureGenerator(options, writer).generate()
        }
        return output.toString()
    }
}
