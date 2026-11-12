package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.testsupport.IntegrationAssumptions
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Packaged-JAR smoke across every supported dialect. Each case seeds one saved connection into an
 * isolated data dir, then drives the real stdio server and asserts schema discovery plus a small
 * query against the seeded shop tables.
 *
 * Cases skip when their engine is not reachable unless the matching SAFEDB_TEST_REQUIRE_* flag is
 * set, matching the rest of the integration suite.
 */
@Tag("integration")
class McpPackagedDialectsIntegrationTest {
    @Test
    fun packagedJarQueriesMysql() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        assertPackagedDialect(
            connection =
                IntegrationAssumptions.mysqlConnectionDef(
                    id = "mcp-packaged-mysql-dialect",
                    name = "MCP Packaged MySQL Dialect",
                ),
            password = IntegrationAssumptions.mysqlPassword,
            defaultSchema = IntegrationAssumptions.mysqlDatabase,
            query = "SELECT id, email FROM customers LIMIT 3",
        )
    }

    @Test
    fun packagedJarQueriesPostgres() = runBlocking {
        IntegrationAssumptions.assumePostgresAvailable()
        assertPackagedDialect(
            connection =
                IntegrationAssumptions.postgresConnectionDef(
                    id = "mcp-packaged-postgres-dialect",
                    name = "MCP Packaged PostgreSQL Dialect",
                ),
            password = IntegrationAssumptions.postgresPassword,
            defaultSchema = "public",
            query = "SELECT id, email FROM customers LIMIT 3",
        )
    }

    @Test
    fun packagedJarQueriesMssql() = runBlocking {
        IntegrationAssumptions.assumeMssqlAvailable()
        assertPackagedDialect(
            connection =
                IntegrationAssumptions.mssqlConnectionDef(
                    id = "mcp-packaged-mssql-dialect",
                    name = "MCP Packaged SQL Server Dialect",
                ),
            password = IntegrationAssumptions.mssqlPassword,
            defaultSchema = "dbo",
            query = "SELECT TOP 3 id, email FROM customers",
        )
    }

    @Test
    fun packagedJarQueriesOracle() = runBlocking {
        IntegrationAssumptions.assumeOracleAvailable()
        assertPackagedDialect(
            connection =
                IntegrationAssumptions.oracleConnectionDef(
                    id = "mcp-packaged-oracle-dialect",
                    name = "MCP Packaged Oracle Dialect",
                ),
            password = IntegrationAssumptions.oraclePassword,
            defaultSchema = "SAFEDB",
            query = "SELECT id, email FROM customers FETCH FIRST 3 ROWS ONLY",
        )
    }

    @Test
    fun packagedJarQueriesDb2() = runBlocking {
        IntegrationAssumptions.assumeDb2Available()
        assertPackagedDialect(
            connection =
                IntegrationAssumptions.db2ConnectionDef(
                    id = "mcp-packaged-db2-dialect",
                    name = "MCP Packaged Db2 Dialect",
                ),
            password = IntegrationAssumptions.db2Password,
            defaultSchema = IntegrationAssumptions.db2User.uppercase(),
            query = "SELECT id, email FROM CUSTOMERS FETCH FIRST 3 ROWS ONLY",
        )
    }

    private suspend fun assertPackagedDialect(
        connection: ConnectionDef,
        password: String,
        defaultSchema: String,
        query: String,
    ) {
        try {
            withPackagedMcpClient(
                // Default "disabled" would hide secrets that seedMcpConnectionForPackagedJar
                // just wrote.
                keychainBackend = null,
                prepare = { tempRoot, home ->
                    seedMcpConnectionForPackagedJar(
                        isolatedMcpDataDir(tempRoot, home),
                        connection,
                        password,
                    )
                },
            ) { client ->
                val listed = client.callTool("list_tables", mapOf("connection_id" to connection.id))
                assertNotEquals(true, listed.isError, listed.text())
                val listedText = listed.text()
                assertPayloadIsPrivate(listedText, connection, password)
                val tables =
                    Json.parseToJsonElement(listedText).jsonObject.getValue("tables").jsonArray
                assertTrue(
                    tables.any {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content
                            .equals("customers", ignoreCase = true)
                    },
                    "customers not listed: $listedText",
                )

                val described =
                    client.callTool(
                        "describe_table",
                        mapOf(
                            "connection_id" to connection.id,
                            "schema" to defaultSchema,
                            "table" to "customers",
                        ),
                    )
                assertNotEquals(true, described.isError, described.text())
                val describedName =
                    Json.parseToJsonElement(described.text())
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive
                        .content
                assertTrue(describedName.equals("customers", ignoreCase = true), described.text())

                if (connection.dialect == Dialect.Db2) {
                    val hiddenPrefixes = listOf("ADVISE_", "EXPLAIN_")
                    assertTrue(
                        tables.none { table ->
                            val name = table.jsonObject.getValue("name").jsonPrimitive.content
                            hiddenPrefixes.any { name.startsWith(it, ignoreCase = true) }
                        },
                        "Db2 internal catalog tables leaked: $listedText",
                    )
                }

                val queried =
                    client.callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to connection.id,
                            "sql" to query,
                            "default_schema" to defaultSchema,
                        ),
                    )
                assertNotEquals(true, queried.isError, queried.text())
                val queriedText = queried.text()
                assertPayloadIsPrivate(queriedText, connection, password)
                val receipt = Json.parseToJsonElement(queriedText).jsonObject
                assertEquals("3", receipt.getValue("row_count").jsonPrimitive.content)
            }
        } finally {
            cleanupPackagedJarWindowsSecret(connection.id)
        }
    }
}
