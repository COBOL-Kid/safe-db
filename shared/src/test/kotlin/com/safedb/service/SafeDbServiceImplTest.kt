package com.safedb.service

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
import com.safedb.model.IndexInfo
import com.safedb.model.MetadataCoverage
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.Outcome
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanRelationAccess
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import com.safedb.model.TransportSecurity
import com.safedb.query.QueryError
import com.safedb.query.QueryRunner
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.CredentialStore
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

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
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )
        configStore.save(sampleConnection())

        assertFailsWith<IllegalStateException> { service.deleteConnection("c1") }

        assertEquals("Delete me", configStore.get("c1")?.name)
    }

    @Test
    fun createConnectionAcceptsEmptyPassword() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val service =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(dir),
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )

        service.createConnection(sampleConnection(), "")

        assertEquals("", SecretsManager.passwordForDefinition(sampleConnection()).getOrThrow())
    }

    @Test
    fun createConnectionRollsBackProfileWhenPasswordSaveFails() = runBlocking {
        SecretsManager.useStoreForTest(FailingSaveStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )

        assertFailsWith<IllegalStateException> {
            service.createConnection(sampleConnection(), "secret")
        }

        assertEquals(null, configStore.get("c1"))
    }

    @Test
    fun updateConnectionRequiresPasswordWhenEndpointChanges() {
        runBlocking {
            SecretsManager.useStoreForTest(DisabledMemoryStore())
            val dir = Files.createTempDirectory("safedb-service-test")
            val configStore = ConfigStore.new(dir)
            val service =
                SafeDbServiceImpl(
                    configStore = configStore,
                    queryStore = QueryStore.new(dir),
                    settingsStore = SettingsStore.new(dir),
                )
            configStore.save(sampleConnection())
            SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()

            assertFailsWith<IllegalArgumentException> {
                service.updateConnection(
                    sampleConnection().copy(host = "db.example.com"),
                    password = null,
                )
            }
        }
    }

    @Test
    fun updateConnectionRequiresPasswordWhenDriverPropertiesChange() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()

        assertFailsWith<IllegalArgumentException> {
            service.updateConnection(
                sampleConnection()
                    .copy(driverProperties = listOf(DriverProperty("currentSchema", "reporting"))),
                password = null,
            )
        }
    }

    @Test
    fun testConnectionReusesStoredPasswordOnlyForMatchingFingerprint() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "stored-secret").getOrThrow()
        var connectedPassword: String? = null
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory =
                    AdapterFactory { _, password ->
                        connectedPassword = password
                        FakeConnectedAdapter()
                    },
            )

        assertEquals("ok", service.testConnection(sampleConnection().copy(name = "Renamed"), null))
        assertEquals("stored-secret", connectedPassword)
        assertFailsWith<IllegalArgumentException> {
            service.testConnection(sampleConnection().copy(host = "other.example.com"), null)
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

        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory =
                    QuerySessionFactory { _, _ ->
                        QuerySession(schema = sampleSchema(), runner = StubRunner())
                    },
            )

        val result = service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
        assertEquals(1, result.queryResult.rowCount)
        assertEquals(1, queryStore.listHistory().size)
        val history = queryStore.listHistory().single()
        assertEquals("Delete me", history.connectionName)
        assertEquals(2, history.riskScoreVersion)
        assertEquals(history.riskStaticScore, history.riskFinalScore)
        assertEquals("Available", history.riskPlanStatus)
        assertEquals("Allowed", history.riskGateState)
        assertEquals(1.0, history.riskOptimizerCost)
        assertTrue(history.riskConfirmationCodes.isEmpty())
        assertNull(history.riskConfirmationAccepted)
        assertTrue("NoEffectiveRestriction" in history.riskSignalCodes)
    }

    @Test
    fun runQueryRecordsRequiredAndAcceptedPlanConfirmation() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val runner = UnavailablePlanRunner()
        val spec = sampleQuerySpec()
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory =
                    QuerySessionFactory { _, _ -> QuerySession(sampleSchema(), runner) },
            )

        val failure =
            assertFailsWith<QueryFailureException> { service.runQuery(QueryRunRequest("c1", spec)) }
        val confirmationError = assertIs<QueryError.ConfirmationRequired>(failure.queryError)

        service.runQuery(QueryRunRequest("c1", spec, confirmationError.requirement.confirmation))

        val history = queryStore.listHistory()
        assertEquals(2, history.size)
        val required = history.single { it.error != null }
        val accepted = history.single { it.error == null }
        assertEquals("ConfirmationRequired", required.riskGateState)
        assertEquals(listOf("PlanUnavailable"), required.riskConfirmationCodes)
        assertEquals(false, required.riskConfirmationAccepted)
        assertEquals("Allowed", accepted.riskGateState)
        assertEquals(listOf("PlanUnavailable"), accepted.riskConfirmationCodes)
        assertEquals(true, accepted.riskConfirmationAccepted)
        assertEquals(1, runner.executeCalls)
    }

    @Test
    fun cancelledAdapterExecutionPropagatesClosesSessionAndWritesNoHistory() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val queryStore = QueryStore.new(dir)
        val adapter = FakeConnectedAdapter(executionFailure = CancellationException("cancelled"))
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory = AdapterFactory { _, _ -> adapter },
            )

        assertFailsWith<CancellationException> {
            service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
        }

        assertEquals(1, adapter.closeCount)
        assertTrue(queryStore.listHistory().isEmpty())
    }

    @Test
    fun testConnectionAlwaysClosesAdapter() = runBlocking {
        val dir = Files.createTempDirectory("safedb-service-test")
        val adapter = FakeConnectedAdapter()
        val service =
            SafeDbServiceImpl(
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
        val adapter =
            FakeConnectedAdapter(introspectionFailure = IllegalStateException("metadata failed"))
        val service =
            SafeDbServiceImpl(
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
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory = AdapterFactory { _, _ -> adapter },
            )

        service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))

        assertEquals(1, adapter.introspectionCount)
        assertEquals(1, adapter.closeCount)
    }

    @Test
    fun getSchemaAndRunQueryAlwaysIntrospectFreshSchema() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter = FakeConnectedAdapter()
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory = AdapterFactory { _, _ -> adapter },
            )

        service.getSchema("c1")
        service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
        service.getSchema("c1")

        assertEquals(3, adapter.introspectionCount)
        assertEquals(3, adapter.closeCount)
    }

    @Test
    fun runQueryUsesFreshMetadataForMandatoryRiskBlocking() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter = FakeConnectedAdapter(schema = sampleSchema(TableSizeClass.Small))
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory = AdapterFactory { _, _ -> adapter },
            )

        service.getSchema("c1")
        adapter.schema = sampleSchema(TableSizeClass.Large)
        adapter.explainResult =
            ExplainResult.Available(
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                schema = "safedb_test",
                                table = "customers",
                                alias = "t0",
                                method = PlanAccessMethod.TableScan,
                                estimatedRows = 150_000,
                            )
                        ),
                    rawOptimizerCost = 1.0,
                )
            )

        val failure =
            assertFailsWith<QueryFailureException> {
                service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
            }

        assertIs<QueryError.RiskGate>(failure.queryError)
        assertEquals(2, adapter.introspectionCount)
        assertEquals(0, adapter.executeCount)
    }

    @Test
    fun runQueryIntrospectionFailureClosesAdapter() = runBlocking {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        configStore.save(sampleConnection())
        SecretsManager.savePasswordForDefinition(sampleConnection(), "secret").getOrThrow()
        val adapter =
            FakeConnectedAdapter(introspectionFailure = IllegalStateException("metadata failed"))
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
                querySessionFactory = null,
                adapterFactory = AdapterFactory { _, _ -> adapter },
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
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
        val service =
            SafeDbServiceImpl(
                configStore = configStore,
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
                querySessionFactory =
                    QuerySessionFactory { _, _ ->
                        QuerySession(
                            schema = sampleSchema(),
                            runner = FailingRunner(),
                            onClose = { closeCount += 1 },
                        )
                    },
            )

        val failure =
            assertFailsWith<QueryFailureException> {
                service.runQuery(QueryRunRequest("c1", sampleQuerySpec()))
            }

        assertTrue(failure.message?.contains("execution failed") == true)
        assertEquals(1, closeCount)
        val history = queryStore.listHistory().single()
        assertTrue(assertNotNull(history.error).contains("execution failed"))
        assertEquals(2, history.riskScoreVersion)
        assertEquals("Allowed", history.riskGateState)
    }
}

private fun sampleQuerySpec() =
    QuerySpec(
        tables = listOf(TableRef(schema = "safedb_test", name = "customers", alias = "t0")),
        columns = listOf(ColumnSel("t0", "id")),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 10,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun sampleSchema(sizeClass: TableSizeClass = TableSizeClass.Unknown) =
    Schema(
        tables =
            listOf(
                TableInfo(
                    schema = "safedb_test",
                    name = "customers",
                    columns =
                        listOf(
                            ColumnInfo(
                                name = "id",
                                dataType = "int",
                                nullable = false,
                                isIndexed = true,
                                joinEligible = true,
                                category = ColumnCategory.Integer,
                            )
                        ),
                    indexes =
                        listOf(
                            IndexInfo(
                                name = "customers_pkey",
                                columns = listOf("id"),
                                supportsEquality = true,
                                isUnique = true,
                                isPrimary = true,
                            )
                        ),
                    tableSize =
                        TableSizeEstimate(
                            sizeClass = sizeClass,
                            coverage = MetadataCoverage.complete(),
                            confidence = EvidenceConfidence.High,
                        ),
                )
            )
    )

private class StubRunner : QueryRunner {
    override suspend fun explain(compiled: CompiledQuery): ExplainResult = availablePlan()

    override suspend fun executeQuery(
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): Outcome<QueryResult> =
        Outcome.ok(
            QueryResult(
                columns = listOf(ResultColumn("id", "int")),
                rows = listOf(listOf(ResultCell.integer(1))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        )
}

private class FailingRunner : QueryRunner {
    override suspend fun explain(compiled: CompiledQuery): ExplainResult = availablePlan()

    override suspend fun executeQuery(
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): Outcome<QueryResult> = Outcome.err("execution failed")
}

private class UnavailablePlanRunner : QueryRunner {
    var executeCalls = 0

    override suspend fun explain(compiled: CompiledQuery): ExplainResult =
        ExplainResult.Unavailable(PlanUnavailableReason.PermissionDenied, "planner denied")

    override suspend fun executeQuery(
        compiled: CompiledQuery,
        timeoutMs: Int,
    ): Outcome<QueryResult> {
        executeCalls += 1
        return Outcome.ok(QueryResult(emptyList(), emptyList(), 0, false, emptyList()))
    }
}

private class FakeConnectedAdapter(
    private val introspectionFailure: Throwable? = null,
    private val executionFailure: Throwable? = null,
    var schema: Schema = sampleSchema(),
    var explainResult: ExplainResult = availablePlan(),
) : ConnectedAdapter {
    var closeCount = 0
    var introspectionCount = 0
    var executeCount = 0
    var testFailure: Throwable? = null

    override suspend fun test(): String {
        testFailure?.let { throw it }
        return "ok"
    }

    override suspend fun introspect(): Schema {
        introspectionCount += 1
        introspectionFailure?.let { throw it }
        return schema
    }

    override suspend fun explain(compiled: CompiledQuery): ExplainResult = explainResult

    override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult {
        executeCount += 1
        executionFailure?.let { throw it }
        return QueryResult(emptyList(), emptyList(), 0, false, emptyList())
    }

    override fun close() {
        closeCount += 1
    }
}

private fun availablePlan(): ExplainResult =
    ExplainResult.Available(NormalizedQueryPlan(rawOptimizerCost = 1.0))

private fun sampleConnection() =
    ConnectionDef(
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
