package com.safedb.tools

import com.safedb.platform.UnsupportedDesktopPlatformException
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedMysqlTest {
    @Test
    fun parsesFlagsAndGeneratorSizes() {
        val options =
            parseArgs(
                listOf(
                    "--reset",
                    "--orders",
                    "12",
                    "--customers",
                    "8",
                    "--products",
                    "4",
                    "--categories",
                    "2",
                    "--seed",
                    "7",
                    "--batch-size",
                    "3",
                )
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
                }
                .message,
        )
        assertFailsWith<RuntimeException> { sanitizeIdentifier("safe;drop", "database") }
    }

    @Test
    fun generatedSqlIsDeterministicAndBatched() {
        val options =
            GeneratorOptions(
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
    fun generatesDialectSpecificSchemasAndInserts() {
        val options =
            GeneratorOptions(
                categories = 2,
                products = 4,
                customers = 5,
                orders = 7,
                seed = 9,
                batchSize = 2,
            )

        val postgres = generate(options, GeneratedSqlDialect.Postgres)
        val mssql = generate(options, GeneratedSqlDialect.Mssql)
        val oracle = generate(options, GeneratedSqlDialect.Oracle)

        assertTrue(postgres.contains("DROP TABLE IF EXISTS order_items"))
        assertTrue(postgres.contains("is_active BOOLEAN DEFAULT TRUE NOT NULL"))
        assertTrue(mssql.contains("SET XACT_ABORT ON"))
        assertTrue(mssql.contains("order_date DATETIME2(0)"))
        assertTrue(oracle.contains("DROP TABLE order_items CASCADE CONSTRAINTS PURGE"))
        assertTrue(oracle.contains("INSERT ALL\nINTO categories"))
        assertEquals(oracle, generate(options, GeneratedSqlDialect.Oracle))
    }

    @Test
    fun relationalSeedPasswordsPreferTestThenDockerThenDefault() {
        val repoRoot = Path.of("/repo")

        assertEquals(
            "postgres",
            relationalSeedSettings(GeneratedSqlDialect.Postgres, emptyMap(), repoRoot)
                .clientEnvironment
                .getValue("PGPASSWORD"),
        )
        assertEquals(
            "docker-postgres",
            relationalSeedSettings(
                    GeneratedSqlDialect.Postgres,
                    mapOf("SAFEDB_DOCKER_POSTGRES_PASSWORD" to "docker-postgres"),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("PGPASSWORD"),
        )
        assertEquals(
            "test-postgres",
            relationalSeedSettings(
                    GeneratedSqlDialect.Postgres,
                    mapOf(
                        "SAFEDB_DOCKER_POSTGRES_PASSWORD" to "docker-postgres",
                        "SAFEDB_TEST_POSTGRES_PASSWORD" to "test-postgres",
                    ),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("PGPASSWORD"),
        )
        assertEquals(
            "postgres",
            relationalSeedSettings(
                    GeneratedSqlDialect.Postgres,
                    mapOf("SAFEDB_DOCKER_POSTGRES_PASSWORD" to ""),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("PGPASSWORD"),
        )
        assertEquals(
            "",
            relationalSeedSettings(
                    GeneratedSqlDialect.Postgres,
                    mapOf(
                        "SAFEDB_DOCKER_POSTGRES_PASSWORD" to "docker-postgres",
                        "SAFEDB_TEST_POSTGRES_PASSWORD" to "",
                    ),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("PGPASSWORD"),
        )
        assertEquals(
            "SafeDb_Ssl_Passw0rd!",
            relationalSeedSettings(GeneratedSqlDialect.Mssql, emptyMap(), repoRoot)
                .clientEnvironment
                .getValue("SQLCMDPASSWORD"),
        )
        assertEquals(
            "docker-mssql",
            relationalSeedSettings(
                    GeneratedSqlDialect.Mssql,
                    mapOf("SAFEDB_DOCKER_MSSQL_PASSWORD" to "docker-mssql"),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("SQLCMDPASSWORD"),
        )
        assertEquals(
            "test-mssql",
            relationalSeedSettings(
                    GeneratedSqlDialect.Mssql,
                    mapOf(
                        "SAFEDB_DOCKER_MSSQL_PASSWORD" to "docker-mssql",
                        "SAFEDB_TEST_MSSQL_PASSWORD" to "test-mssql",
                    ),
                    repoRoot,
                )
                .clientEnvironment
                .getValue("SQLCMDPASSWORD"),
        )
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

    @Test
    fun resolvesOnlySupportedPlatformAppDataDirectories() {
        assertEquals(
            Path.of("/Users/test/Library/Application Support/com.safedb.app"),
            safeDbAppDataDir(SeedMysqlPlatformEnvironment("Darwin", "/Users/test")),
        )
        assertEquals(
            Path.of("C:/Users/test/AppData/Roaming/com.safedb.app"),
            safeDbAppDataDir(
                SeedMysqlPlatformEnvironment(
                    "Windows 11",
                    "C:/Users/test",
                    appData = "C:/Users/test/AppData/Roaming",
                )
            ),
        )
        assertNull(safeDbAppDataDir(SeedMysqlPlatformEnvironment("Windows 11", "C:/Users/test")))
    }

    @Test
    fun linuxAppDataLookupReportsUnsupportedPlatform() {
        val error =
            assertFailsWith<UnsupportedDesktopPlatformException> {
                safeDbAppDataDir(SeedMysqlPlatformEnvironment("Linux", "/home/test"))
            }

        assertEquals(
            "unsupported operating system 'Linux'; supported platforms are macOS and Windows",
            error.message,
        )

        var resetMessage = ""
        assertNull(
            safeDbAppDataDirForStateReset(
                environment = SeedMysqlPlatformEnvironment("Linux", "/home/test"),
                report = { resetMessage = it },
            )
        )
        assertEquals(
            "-> skipping safe-db app state reset " +
                "(unsupported operating system 'Linux'; supported platforms are macOS and Windows)",
            resetMessage,
        )
    }

    private fun generate(options: GeneratorOptions): String {
        val output = StringWriter()
        BufferedWriter(output).use { writer ->
            RelationalFixtureGenerator(options, GeneratedSqlDialect.Mysql, writer).generate()
        }
        return output.toString()
    }

    private fun generate(options: GeneratorOptions, dialect: GeneratedSqlDialect): String {
        val output = StringWriter()
        BufferedWriter(output).use { writer ->
            RelationalFixtureGenerator(options, dialect, writer).generate()
        }
        return output.toString()
    }
}
