package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.adapter.ConnectionFailedException
import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.Outcome
import com.safedb.model.ResultCell
import com.safedb.query.compileValidated
import com.safedb.query.sql.SqlIssueCode
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.mySqlBackslashEscapes
import com.safedb.query.sql.parseSqlToSpec
import com.safedb.query.validateQuery
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import com.zaxxer.hikari.HikariDataSource
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Tag("integration")
class SreResilienceIntegrationTest {
    private data class EngineSpec(
        val assumeAvailable: () -> Unit,
        val def: ConnectionDef,
        val password: String,
        val defaultSchema: String,
        val expectedCustomersSchema: String,
        val expectedCustomerCount: Int,
        val observerSessionQuery: CompiledQuery? = null,
    )

    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Nested
    inner class MySql {
        private val engine =
            EngineSpec(
                assumeAvailable = IntegrationAssumptions::assumeMysqlAvailable,
                def = IntegrationAssumptions.mysqlConnectionDef(),
                password = IntegrationAssumptions.mysqlPassword,
                defaultSchema = IntegrationAssumptions.mysqlDatabase,
                expectedCustomersSchema = IntegrationAssumptions.mysqlDatabase,
                expectedCustomerCount = 15,
                observerSessionQuery =
                    CompiledQuery(
                        sql = "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = ?",
                        params = listOf(BindValue.Text(IntegrationAssumptions.mysqlUser)),
                    ),
            )

        @Test
        fun rejectsDmlDdlAndMultiStatementsWithoutModifyingCustomers() = runBlocking {
            verifyDmlDdlAndMultiStatementsRejected(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnUnreachablePortWithoutLeakingPassword() = runBlocking {
            verifyUnreachablePortFastFail(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnWrongPasswordWithoutLeakingPassword() = runBlocking {
            verifyWrongPasswordFastFail(engine)
        }

        @Test
        @Timeout(30)
        fun connectQueryCloseCyclesShutDownPoolWithoutLeakingSessions() = runBlocking {
            verifyPoolLifecycle(engine)
        }
    }

    @Nested
    inner class Postgres {
        private val engine =
            EngineSpec(
                assumeAvailable = IntegrationAssumptions::assumePostgresAvailable,
                def = IntegrationAssumptions.postgresConnectionDef(),
                password = IntegrationAssumptions.postgresPassword,
                defaultSchema = "public",
                expectedCustomersSchema = "public",
                expectedCustomerCount = 3,
                observerSessionQuery =
                    CompiledQuery(
                        sql =
                            "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = \$1 AND usename = \$2",
                        params =
                            listOf(
                                BindValue.Text(IntegrationAssumptions.postgresDatabase),
                                BindValue.Text(IntegrationAssumptions.postgresUser),
                            ),
                    ),
            )

        @Test
        fun rejectsDmlDdlAndMultiStatementsWithoutModifyingCustomers() = runBlocking {
            verifyDmlDdlAndMultiStatementsRejected(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnUnreachablePortWithoutLeakingPassword() = runBlocking {
            verifyUnreachablePortFastFail(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnWrongPasswordWithoutLeakingPassword() = runBlocking {
            verifyWrongPasswordFastFail(engine)
        }

        @Test
        @Timeout(30)
        fun connectQueryCloseCyclesShutDownPoolWithoutLeakingSessions() = runBlocking {
            verifyPoolLifecycle(engine)
        }
    }

    @Nested
    inner class Mssql {
        private val engine =
            EngineSpec(
                assumeAvailable = IntegrationAssumptions::assumeMssqlAvailable,
                def = IntegrationAssumptions.mssqlConnectionDef(),
                password = IntegrationAssumptions.mssqlPassword,
                defaultSchema = "dbo",
                expectedCustomersSchema = "dbo",
                expectedCustomerCount = 3,
                observerSessionQuery =
                    CompiledQuery(
                        sql = "SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE program_name = @P1",
                        params = listOf(BindValue.Text("safe-db")),
                    ),
            )

        @Test
        fun rejectsDmlDdlAndMultiStatementsWithoutModifyingCustomers() = runBlocking {
            verifyDmlDdlAndMultiStatementsRejected(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnUnreachablePortWithoutLeakingPassword() = runBlocking {
            verifyUnreachablePortFastFail(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnWrongPasswordWithoutLeakingPassword() = runBlocking {
            verifyWrongPasswordFastFail(engine)
        }

        @Test
        @Timeout(30)
        fun connectQueryCloseCyclesShutDownPoolWithoutLeakingSessions() = runBlocking {
            verifyPoolLifecycle(engine)
        }
    }

    @Nested
    inner class Oracle {
        private val engine =
            EngineSpec(
                assumeAvailable = IntegrationAssumptions::assumeOracleAvailable,
                def = IntegrationAssumptions.oracleConnectionDef(),
                password = IntegrationAssumptions.oraclePassword,
                defaultSchema = "SAFEDB",
                expectedCustomersSchema = "SAFEDB",
                expectedCustomerCount = 3,
            )

        @Test
        fun rejectsDmlDdlAndMultiStatementsWithoutModifyingCustomers() = runBlocking {
            verifyDmlDdlAndMultiStatementsRejected(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnUnreachablePortWithoutLeakingPassword() = runBlocking {
            verifyUnreachablePortFastFail(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnWrongPasswordWithoutLeakingPassword() = runBlocking {
            verifyWrongPasswordFastFail(engine)
        }

        @Test
        @Timeout(30)
        fun connectQueryCloseCyclesShutDownPoolWithoutLeakingSessions() = runBlocking {
            verifyPoolLifecycle(engine)
        }
    }

    @Nested
    inner class Db2 {
        private val engine =
            EngineSpec(
                assumeAvailable = IntegrationAssumptions::assumeDb2Available,
                def = IntegrationAssumptions.db2ConnectionDef(),
                password = IntegrationAssumptions.db2Password,
                defaultSchema = "DB2INST1",
                expectedCustomersSchema = "DB2INST1",
                expectedCustomerCount = 3,
            )

        @Test
        fun rejectsDmlDdlAndMultiStatementsWithoutModifyingCustomers() = runBlocking {
            verifyDmlDdlAndMultiStatementsRejected(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnUnreachablePortWithoutLeakingPassword() = runBlocking {
            verifyUnreachablePortFastFail(engine)
        }

        @Test
        @Timeout(15)
        fun failsFastOnWrongPasswordWithoutLeakingPassword() = runBlocking {
            verifyWrongPasswordFastFail(engine)
        }

        @Test
        @Timeout(30)
        fun connectQueryCloseCyclesShutDownPoolWithoutLeakingSessions() = runBlocking {
            verifyPoolLifecycle(engine)
        }
    }

    private suspend fun verifyDmlDdlAndMultiStatementsRejected(engine: EngineSpec) {
        engine.assumeAvailable()
        val dir =
            Files.createTempDirectory("safedb-sre-safety-${engine.def.dialect.name.lowercase()}")
        try {
            val service =
                SafeDbServiceImpl(
                    configStore = ConfigStore.new(dir),
                    queryStore = QueryStore.new(dir),
                    settingsStore = SettingsStore.new(dir),
                )
            service.createConnection(engine.def, engine.password)
            val schema = service.getSchema(engine.def.id)
            val customersSpec =
                IntegrationFixtures.customersQuery(
                    schema,
                    limit = 100,
                    expectedSchema = engine.expectedCustomersSchema,
                )
            val before = service.runQuery(QueryRunRequest(engine.def.id, customersSpec))
            assertEquals(engine.expectedCustomerCount, before.queryResult.rowCount)
            assertFalse(before.queryResult.truncated)

            val backslashMode = mySqlBackslashEscapes(engine.def)
            val blockedSingleStatements =
                listOf(
                    "DELETE FROM customers",
                    "UPDATE customers SET email = 'hacked@example.com'",
                    "DROP TABLE customers",
                )
            for (sql in blockedSingleStatements) {
                val parsed =
                    assertIs<SqlParseResult.Failure>(
                        parseSqlToSpec(
                            sql,
                            engine.def.dialect,
                            schema,
                            engine.defaultSchema,
                            backslashMode,
                        ),
                        "expected $sql to be rejected for ${engine.def.dialect}",
                    )
                assertTrue(
                    parsed.issues.any { it.code == SqlIssueCode.NotSelect },
                    "expected NotSelect for $sql, got ${parsed.issues}",
                )
            }

            // Never send raw multi-statement SQL directly to MssqlAdapter.executeQuery because
            // T-SQL
            // has no SET TRANSACTION READ ONLY and would execute the trailing DROP.
            val multiParsed =
                assertIs<SqlParseResult.Failure>(
                    parseSqlToSpec(
                        "SELECT id FROM customers; DROP TABLE customers",
                        engine.def.dialect,
                        schema,
                        engine.defaultSchema,
                        backslashMode,
                    ),
                )
            assertTrue(
                multiParsed.issues.any { it.code == SqlIssueCode.MultipleStatements },
                "expected MultipleStatements, got ${multiParsed.issues}",
            )

            val after = service.runQuery(QueryRunRequest(engine.def.id, customersSpec))
            assertEquals(before.queryResult.rowCount, after.queryResult.rowCount)
            assertEquals(before.queryResult.rows, after.queryResult.rows)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun verifyUnreachablePortFastFail(engine: EngineSpec) {
        val closedPort = ServerSocket(0).use { it.localPort }
        val secret = "unreachable-secret-${UUID.randomUUID()}"
        val unreachableDef = engine.def.copy(host = "127.0.0.1", port = closedPort)
        val startedAt = System.nanoTime()
        val error =
            assertFailsWith<ConnectionFailedException> {
                Adapter.connect(unreachableDef, secret, connectTimeoutMs = 3_000L)
            }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(
            elapsedMs < 5_000,
            "expected unreachable port fast-fail < 5000ms, took ${elapsedMs}ms",
        )
        assertTrue(
            error.message?.startsWith("Could not connect to 127.0.0.1:$closedPort") == true,
            "unexpected unreachable error message: ${error.message}",
        )
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        assertFalse(
            chain.any { it.message.orEmpty().contains(secret) },
            "password leaked in exception chain",
        )
    }

    private suspend fun verifyWrongPasswordFastFail(engine: EngineSpec) {
        engine.assumeAvailable()
        val wrongPassword = "wrong-secret-${UUID.randomUUID()}"
        val startedAt = System.nanoTime()
        val error =
            assertFailsWith<ConnectionFailedException> {
                Adapter.connect(engine.def, wrongPassword)
            }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(
            elapsedMs < 10_000,
            "expected wrong-password fast-fail < 10000ms, took ${elapsedMs}ms",
        )
        assertEquals("Authentication failed for user ${engine.def.username}", error.message)
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        assertFalse(
            chain.any { it.message.orEmpty().contains(wrongPassword) },
            "wrong password leaked in exception chain",
        )
    }

    private suspend fun verifyPoolLifecycle(engine: EngineSpec) {
        engine.assumeAvailable()
        val observer = Adapter.connect(engine.def, engine.password)
        try {
            val schema = Adapter.introspectWithTimeout(observer)
            val customersSpec =
                IntegrationFixtures.customersQuery(
                    schema,
                    limit = 1,
                    expectedSchema = engine.expectedCustomersSchema,
                )
            val validated =
                when (
                    val result =
                        validateQuery(customersSpec, schema, emptyList(), engine.def.dialect)
                ) {
                    is Outcome.Ok -> result.value.first
                    is Outcome.Err -> error(result.message)
                }
            val compiledCustomers =
                when (val result = compileValidated(validated, engine.def.dialect)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> error(result.message)
                }

            val baselineSessions =
                engine.observerSessionQuery?.let { queryScalarCount(observer, it) }

            repeat(5) {
                val adapter = Adapter.connect(engine.def, engine.password)
                val ds = dataSourceOf(adapter)
                assertFalse(ds.isClosed)
                val result = adapter.executeQuery(compiledCustomers, 10_000)
                // compileValidated fetches limit + 1 (2) rows so QueryCore can detect truncation.
                assertEquals(2, result.rowCount)
                assertEquals(0, ds.hikariPoolMXBean?.activeConnections ?: 0)
                adapter.close()
                assertTrue(ds.isClosed)
                if (adapter is Adapter.Mssql) {
                    assertNull(adapter.state.dataSource)
                    assertFailsWith<IllegalStateException> { adapter.test() }
                }
            }

            if (engine.observerSessionQuery != null && baselineSessions != null) {
                var observed = queryScalarCount(observer, engine.observerSessionQuery)
                var attempts = 0
                while (observed > baselineSessions && attempts < 20) {
                    delay(100)
                    observed = queryScalarCount(observer, engine.observerSessionQuery)
                    attempts++
                }
                assertTrue(
                    observed <= baselineSessions,
                    "expected no leaked server sessions after closing 5 pools: baseline=$baselineSessions observed=$observed",
                )
                if (engine.def.dialect == Dialect.Postgres) {
                    val idleInTx =
                        queryScalarCount(
                            observer,
                            CompiledQuery(
                                sql =
                                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = \$1 AND state = 'idle in transaction'",
                                params =
                                    listOf(BindValue.Text(IntegrationAssumptions.postgresDatabase)),
                            ),
                        )
                    assertEquals(0L, idleInTx)
                }
            }
        } finally {
            observer.close()
        }
    }

    private fun dataSourceOf(adapter: Adapter): HikariDataSource =
        when (adapter) {
            is Adapter.Postgres -> adapter.dataSource
            is Adapter.MySql -> adapter.dataSource
            is Adapter.Oracle -> adapter.dataSource
            is Adapter.Db2 -> adapter.dataSource
            is Adapter.Mssql ->
                requireNotNull(adapter.state.dataSource) { "MSSQL dataSource unexpectedly null" }
            is Adapter.PooledAdapter ->
                error("Unexpected PooledAdapter subclass: ${adapter::class.simpleName}")
        }

    private suspend fun queryScalarCount(adapter: Adapter, query: CompiledQuery): Long {
        val result = adapter.executeQuery(query, 10_000)
        return when (val cell = result.rows.single().single()) {
            is ResultCell.IntegerCell -> cell.value
            is ResultCell.TextCell -> cell.value.text.trim().toLong()
            else -> error("unexpected count cell: $cell")
        }
    }
}
