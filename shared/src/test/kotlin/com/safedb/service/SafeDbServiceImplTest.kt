package com.safedb.service

import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.ExplainResult
import com.safedb.model.ColumnCategory
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.FilterGroup
import com.safedb.model.IndexInfo
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TransportSecurity
import com.safedb.query.QueryRunner
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.CredentialStore
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.SecretsManager
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SafeDbServiceImplTest {
    @BeforeTest
    fun setup() {
        CredentialSession.lockCredentials()
    }

    @Test
    fun deleteConnectionRestoresProfileWhenCredentialDeleteFails() = runBlocking {
        SecretsManager.useStoreForTest(FailingDeleteStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
        )
        configStore.save(sampleConnection())

        assertFailsWith<IllegalStateException> {
            service.deleteConnection("c1")
        }

        assertEquals("Delete me", configStore.get("c1")?.name)
    }

    @Test
    fun saveConnectionAcceptsEmptyPassword() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val service = SafeDbServiceImpl(
            configStore = ConfigStore.new(dir),
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
        )

        service.saveConnection(sampleConnection(), "")

        assertEquals(
            "",
            SecretsManager.passwordForDefinition(sampleConnection()).getOrThrow(),
        )
    }

    @Test
    fun saveConnectionRollsBackProfileWhenPasswordSaveFails() = runBlocking {
        SecretsManager.useStoreForTest(FailingSaveStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
        )

        assertFailsWith<IllegalStateException> {
            service.saveConnection(sampleConnection(), "secret")
        }

        assertEquals(null, configStore.get("c1"))
    }

    @Test
    fun updateConnectionRequiresPasswordWhenEndpointChanges() {
        runBlocking {
            SecretsManager.useStoreForTest(DisabledMemoryStore())
            val dir = Files.createTempDirectory("safedb-service-test")
            val configStore = ConfigStore.new(dir)
            val service = SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )
            configStore.save(sampleConnection())
            SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()

            assertFailsWith<IllegalArgumentException> {
                service.updateConnection(sampleConnection().copy(host = "db.example.com"), password = null)
            }
        }
    }

    @Test
    fun runQueryRecordsHistoryOnSuccess() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()

        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = queryStore,
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = QuerySessionFactory { _, _ ->
                QuerySession(
                    schema = sampleSchema(),
                    runner = StubRunner(),
                )
            },
        )

        val result = service.runQuery(QueryRunRequest("c1", sampleQuerySpec(), force = true))
        assertEquals(1, result.rowCount)
        assertEquals(1, queryStore.listHistory().size)
        assertEquals("Delete me", queryStore.listHistory().single().connectionName)
    }

    @Test
    fun testConnectionAlwaysClosesAdapter() = runBlocking {
        val dir = Files.createTempDirectory("safedb-service-test")
        val adapter = FakeConnectedAdapter()
        val service = SafeDbServiceImpl(
            configStore = ConfigStore.new(dir),
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = null,
            adapterFactory = AdapterFactory { _, _ -> adapter },
        )

        assertEquals("ok", service.testConnection(sampleConnection(), "secret"))
        assertEquals(1, adapter.closeCount)

        adapter.testFailure = IllegalStateException("probe failed")
        assertFailsWith<IllegalStateException> {
            service.testConnection(sampleConnection(), "secret")
        }
        assertEquals(2, adapter.closeCount)
    }

    @Test
    fun schemaIntrospectionFailureClosesAdapter() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter = FakeConnectedAdapter(introspectionFailure = IllegalStateException("metadata failed"))
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = null,
            adapterFactory = AdapterFactory { _, _ -> adapter },
        )

        assertFailsWith<IllegalStateException> { service.getSchema("c1") }
        assertEquals(1, adapter.closeCount)
    }

    @Test
    fun runQueryIntrospectsConnectedSchemaBeforeValidation() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter = FakeConnectedAdapter()
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = null,
            adapterFactory = AdapterFactory { _, _ -> adapter },
        )

        service.runQuery(QueryRunRequest("c1", sampleQuerySpec(), force = true))

        assertEquals(1, adapter.introspectionCount)
        assertEquals(1, adapter.closeCount)
    }

    @Test
    fun runQueryIntrospectionFailureClosesAdapter() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter = FakeConnectedAdapter(introspectionFailure = IllegalStateException("metadata failed"))
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = null,
            adapterFactory = AdapterFactory { _, _ -> adapter },
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.runQuery(QueryRunRequest("c1", sampleQuerySpec(), force = true))
        }

        assertEquals("metadata failed", failure.message)
        assertEquals(1, adapter.introspectionCount)
        assertEquals(1, adapter.closeCount)
    }

    @Test
    fun runQueryRecordsFailureAndClosesSession() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        var closeCount = 0
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = queryStore,
            settingsStore = SettingsStore.new(dir),
            querySessionFactory = QuerySessionFactory { _, _ ->
                QuerySession(
                    schema = sampleSchema(),
                    runner = FailingRunner(),
                    onClose = { closeCount += 1 },
                )
            },
        )

        val failure = assertFailsWith<QueryFailureException> {
            service.runQuery(QueryRunRequest("c1", sampleQuerySpec(), force = true))
        }

        assertTrue(failure.message?.contains("execution failed") == true)
        assertEquals(1, closeCount)
        val history = queryStore.listHistory().single()
        assertTrue(assertNotNull(history.error).contains("execution failed"))
    }
}

private fun sampleQuerySpec() = QuerySpec(
    tables = listOf(TableRef(schema = "safedb_test", name = "customers", alias = "t0")),
    columns = listOf(ColumnSel("t0", "id")),
    joins = emptyList(),
    filters = FilterGroup.empty(),
    limit = 10,
    schemaVersion = CURRENT_SCHEMA_VERSION,
)

private fun sampleSchema() = Schema(
    tables = listOf(
        TableInfo(
            schema = "safedb_test",
            name = "customers",
            columns = listOf(
                ColumnInfo(
                    name = "id",
                    dataType = "int",
                    nullable = false,
                    isIndexed = true,
                    joinEligible = true,
                    category = ColumnCategory.Integer,
                ),
            ),
            indexes = listOf(
                IndexInfo(
                    name = "customers_pkey",
                    columns = listOf("id"),
                    supportsEquality = true,
                    isUnique = true,
                    isPrimary = true,
                ),
            ),
        ),
    ),
)

private class StubRunner : QueryRunner {
    override suspend fun explain(compiled: CompiledQuery): ExplainResult =
        ExplainResult.Estimated(1.0)

    override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> =
        Outcome.ok(
            QueryResult(
                columns = listOf(ResultColumn("id", "int")),
                rows = listOf(listOf(ResultCell.integer(1))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            ),
        )
}

private class FailingRunner : QueryRunner {
    override suspend fun explain(compiled: CompiledQuery): ExplainResult = ExplainResult.Estimated(1.0)

    override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> =
        Outcome.err("execution failed")
}

private class FakeConnectedAdapter(
    private val introspectionFailure: Throwable? = null,
) : ConnectedAdapter {
    var closeCount = 0
    var introspectionCount = 0
    var testFailure: Throwable? = null

    override suspend fun test(): String {
        testFailure?.let { throw it }
        return "ok"
    }

    override suspend fun introspect(): Schema {
        introspectionCount += 1
        introspectionFailure?.let { throw it }
        return sampleSchema()
    }

    override suspend fun explain(compiled: CompiledQuery): ExplainResult = ExplainResult.Estimated(1.0)

    override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
        QueryResult(emptyList(), emptyList(), 0, false, emptyList())

    override fun close() {
        closeCount += 1
    }
}

private fun sampleConnection() = ConnectionDef(
    id = "c1",
    name = "Delete me",
    dialect = Dialect.MySql,
    host = "localhost",
    port = 3306,
    database = "safedb_test",
    username = "testuser",
    transportSecurity = TransportSecurity(),
)

private class FailingDeleteStore : CredentialStore {
    override fun setPassword(service: String, account: String, password: String) = Unit
    override fun getPassword(service: String, account: String): String? = null
    override fun deletePassword(service: String, account: String) {
        throw IllegalStateException()
    }
    override fun vendor(): String = "failing-delete"
}

private class FailingSaveStore : CredentialStore {
    override fun setPassword(service: String, account: String, password: String) {
        throw IllegalStateException("save failed")
    }
    override fun getPassword(service: String, account: String): String? = null
    override fun deletePassword(service: String, account: String) = Unit
    override fun vendor(): String = "failing-save"
}
