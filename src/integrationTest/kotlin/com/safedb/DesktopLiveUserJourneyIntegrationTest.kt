package com.safedb

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.explore.ExploreMode
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryRiskGate
import com.safedb.query.RiskGateState
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.parseSqlToSpec
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import com.safedb.ui.ExploreWindowContent
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.DatabaseReachability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Tag

@Tag("integration")
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLiveUserJourneyIntegrationTest {
    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    @BeforeTest
    fun setUp() {
        CredentialSession.lockCredentials()
        mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "desktop-integration-main").apply { isDaemon = true }
        }
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
        CredentialSession.lockCredentials()
        SecretsManager.initStore("disabled")
        SecretsManager.resetStoreReadCountForTest()
    }

    @Test
    fun mysqlFullUserJourneyAndRestartPersistence() {
        IntegrationAssumptions.assumeMysqlAvailable()
        runFullUserJourney(
            connection = IntegrationAssumptions.mysqlConnectionDef(),
            password = IntegrationAssumptions.mysqlPassword,
            defaultSchema = IntegrationAssumptions.mysqlDatabase,
            bulkSchema = "safedb_lab",
            sqlQuery = "SELECT id, email FROM customers LIMIT 5",
        )
    }

    @Test
    fun postgresFullUserJourneyAndRestartPersistence() {
        IntegrationAssumptions.assumePostgresAvailable()
        runFullUserJourney(
            connection = IntegrationAssumptions.postgresConnectionDef(),
            password = IntegrationAssumptions.postgresPassword,
            defaultSchema = "public",
            bulkSchema = "analytics",
            sqlQuery = "SELECT id, email FROM customers LIMIT 5",
        )
    }

    @Test
    fun mssqlLiveConnectSchemaAndQuery() {
        IntegrationAssumptions.assumeMssqlAvailable()
        runCoreConnectSchemaAndQuery(
            connection = IntegrationAssumptions.mssqlConnectionDef(),
            password = IntegrationAssumptions.mssqlPassword,
            defaultSchema = "dbo",
            sqlQuery = "SELECT TOP 3 id, email FROM customers",
        )
    }

    @Test
    fun oracleLiveConnectSchemaAndQuery() {
        IntegrationAssumptions.assumeOracleAvailable()
        runCoreConnectSchemaAndQuery(
            connection = IntegrationAssumptions.oracleConnectionDef(),
            password = IntegrationAssumptions.oraclePassword,
            defaultSchema = "SAFEDB",
            sqlQuery = "SELECT id, email FROM customers FETCH FIRST 3 ROWS ONLY",
        )
    }

    @Test
    fun db2LiveConnectSchemaAndQuery() {
        IntegrationAssumptions.assumeDb2Available()
        runCoreConnectSchemaAndQuery(
            connection = IntegrationAssumptions.db2ConnectionDef(),
            password = IntegrationAssumptions.db2Password,
            defaultSchema = IntegrationAssumptions.db2User.uppercase(),
            sqlQuery = "SELECT id, email FROM customers FETCH FIRST 3 ROWS ONLY",
        )
    }

    private fun runFullUserJourney(
        connection: ConnectionDef,
        password: String,
        defaultSchema: String,
        bulkSchema: String,
        sqlQuery: String,
    ) {
        val dataDir = Files.createTempDirectory("safedb-desktop-it-")
        try {
            SecretsManager.initFileStore(dataDir.resolve("credentials"))
            val htmlExport = dataDir.resolve("explore-report.html")
            val csvExport = dataDir.resolve("explore-report.csv")

            runBlocking {
                val viewModel = AppViewModel(newService(dataDir))
                try {
                    awaitCondition("initial loading") { !viewModel.initialLoading.value }

                    val testOutput = viewModel.connections.testConnection(connection, password)
                    assertTrue(testOutput.isNotBlank(), "Expected non-blank testConnection output")

                    saveConnection(viewModel, connection, password)
                    loadSchemaAndAwaitConnected(viewModel, connection, defaultSchema)

                    val schema =
                        checkNotNull(withContext(mainDispatcher) { viewModel.schema.schema })
                    val builderSample =
                        runOrdersBuilderQuery(viewModel, connection, schema, defaultSchema)

                    runSqlEditorQuery(
                        viewModel = viewModel,
                        connection = connection,
                        schema = schema,
                        defaultSchema = defaultSchema,
                        sqlQuery = sqlQuery,
                    )

                    verifyCautiousGateBlocksScanEvents(
                        viewModel = viewModel,
                        connection = connection,
                        schema = schema,
                        bulkSchema = bulkSchema,
                    )

                    verifyExploreRenderAndExports(
                        viewModel = viewModel,
                        connection = connection,
                        sample = builderSample,
                        htmlExport = htmlExport,
                        csvExport = csvExport,
                    )

                    awaitCondition("history entries recorded") {
                        viewModel.history.entries.value.size >= 3
                    }
                } finally {
                    withContext(mainDispatcher) { viewModel.close() }
                }

                // Clear the in-memory CredentialSession and re-initialize the file store so the
                // restarted AppViewModel must read saved connections, settings, history, and
                // passwords directly from disk.
                SecretsManager.lockCredentials()
                SecretsManager.initFileStore(dataDir.resolve("credentials"))

                val restarted = AppViewModel(newService(dataDir))
                try {
                    awaitCondition("restarted initial loading") { !restarted.initialLoading.value }
                    val persistedConnections =
                        withContext(mainDispatcher) { restarted.connections.connections.value }
                    assertTrue(
                        persistedConnections.any {
                            it.id == connection.id && it.name == connection.name
                        },
                        "Expected ${connection.name} to persist across AppViewModel restart",
                    )
                    assertEquals(
                        QueryRiskGate.Cautious,
                        withContext(mainDispatcher) {
                            restarted.settings.settings.value.queryRiskGate
                        },
                    )
                    assertTrue(
                        withContext(mainDispatcher) { restarted.history.entries.value.size } >= 3,
                        "Expected query history to persist across AppViewModel restart",
                    )

                    withContext(mainDispatcher) {
                        restarted.refreshDatabaseReachability(connection)
                    }
                    awaitCondition("restarted reachability connected") {
                        restarted.databaseReachability.value == DatabaseReachability.Connected
                    }
                } finally {
                    withContext(mainDispatcher) { restarted.close() }
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    private fun runCoreConnectSchemaAndQuery(
        connection: ConnectionDef,
        password: String,
        defaultSchema: String,
        sqlQuery: String,
    ) {
        val dataDir = Files.createTempDirectory("safedb-desktop-it-")
        try {
            SecretsManager.initFileStore(dataDir.resolve("credentials"))
            runBlocking {
                val viewModel = AppViewModel(newService(dataDir))
                try {
                    awaitCondition("initial loading") { !viewModel.initialLoading.value }

                    val testOutput = viewModel.connections.testConnection(connection, password)
                    assertTrue(testOutput.isNotBlank(), "Expected non-blank testConnection output")

                    saveConnection(viewModel, connection, password)
                    loadSchemaAndAwaitConnected(viewModel, connection, defaultSchema)

                    val schema =
                        checkNotNull(withContext(mainDispatcher) { viewModel.schema.schema })
                    val customersTable =
                        IntegrationFixtures.requireSeededTable(
                            schema = schema,
                            tableName = "customers",
                            requiredColumns = setOf("id", "email"),
                            expectedSchema = defaultSchema,
                        )
                    withContext(mainDispatcher) {
                        viewModel.query.addTable(customersTable)
                        viewModel.query.toggleColumn(
                            "t0",
                            IntegrationFixtures.seededColumn(customersTable, "id"),
                        )
                        viewModel.query.toggleColumn(
                            "t0",
                            IntegrationFixtures.seededColumn(customersTable, "email"),
                        )
                        viewModel.query.setLimit(5)
                        viewModel.query.run(connection.id)
                    }
                    awaitCondition("builder query settled") { !viewModel.query.running }
                    val builderResult =
                        withContext(mainDispatcher) {
                            assertNull(viewModel.query.error)
                            assertNull(viewModel.query.pendingConfirmation)
                            assertFalse(viewModel.query.pendingRiskGate)
                            viewModel.query.results
                        }
                    assertNotNull(builderResult)
                    assertTrue(builderResult.rowCount > 0, "Expected non-empty Builder query rows")

                    runSqlEditorQuery(
                        viewModel = viewModel,
                        connection = connection,
                        schema = schema,
                        defaultSchema = defaultSchema,
                        sqlQuery = sqlQuery,
                    )
                } finally {
                    withContext(mainDispatcher) { viewModel.close() }
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    private suspend fun saveConnection(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        password: String,
    ) {
        var outcome: Result<ConnectionDef>? = null
        val job =
            withContext(mainDispatcher) {
                viewModel.connections.saveConnection(
                    def = connection,
                    password = password,
                    editing = false,
                    credentialMaterialChanged = true,
                ) {
                    outcome = it
                }
            }
        job.join()
        val saved = assertNotNull(outcome, "Expected saveConnection callback outcome")
        assertTrue(saved.isSuccess, "saveConnection failed: ${saved.exceptionOrNull()}")
        val stored = withContext(mainDispatcher) { viewModel.connections.connections.value }
        assertTrue(stored.any { it.id == connection.id }, "Expected saved connection in state")
    }

    private suspend fun loadSchemaAndAwaitConnected(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        defaultSchema: String,
    ) {
        withContext(mainDispatcher) {
            viewModel.refreshDatabaseReachability(connection)
            viewModel.schema.load(
                connection.id,
                SchemaSelectionIntent(defaultSchema, SchemaSelectionSource.User),
            )
        }
        awaitCondition("schema loaded and reachability connected") {
            !viewModel.schema.loading &&
                viewModel.schema.loadedConnectionId == connection.id &&
                viewModel.databaseReachability.value == DatabaseReachability.Connected
        }
        assertNull(withContext(mainDispatcher) { viewModel.schema.error })
    }

    private suspend fun runOrdersBuilderQuery(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        schema: com.safedb.model.Schema,
        defaultSchema: String,
    ) = run {
        val ordersTable =
            IntegrationFixtures.requireSeededTable(
                schema = schema,
                tableName = "orders",
                requiredColumns = setOf("id", "status", "total"),
                expectedSchema = defaultSchema,
            )
        withContext(mainDispatcher) {
            viewModel.query.addTable(ordersTable)
            viewModel.query.toggleColumn(
                "t0",
                IntegrationFixtures.seededColumn(ordersTable, "id"),
            )
            viewModel.query.toggleColumn(
                "t0",
                IntegrationFixtures.seededColumn(ordersTable, "status"),
            )
            viewModel.query.toggleColumn(
                "t0",
                IntegrationFixtures.seededColumn(ordersTable, "total"),
            )
            viewModel.query.setLimit(10)
            viewModel.query.run(connection.id)
        }
        awaitCondition("orders builder query settled") { !viewModel.query.running }
        val sample =
            withContext(mainDispatcher) {
                assertNull(viewModel.query.error)
                assertNull(viewModel.query.pendingConfirmation)
                assertFalse(viewModel.query.pendingRiskGate)
                viewModel.query.currentSample(connection.id)
            }
        assertNotNull(sample, "Expected BuilderQuerySample after orders query")
        assertTrue(sample.result.rowCount > 0, "Expected non-empty orders result")
        sample
    }

    private suspend fun runSqlEditorQuery(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        schema: com.safedb.model.Schema,
        defaultSchema: String,
        sqlQuery: String,
    ) {
        val parseResult = parseSqlToSpec(sqlQuery, connection.dialect, schema, defaultSchema)
        val success = parseResult as? SqlParseResult.Success
        assertNotNull(success, "Expected SQL parse success for '$sqlQuery', got $parseResult")
        withContext(mainDispatcher) {
            viewModel.sqlEditor.onTextChanged(TextFieldValue(sqlQuery))
            viewModel.sqlEditor.run(connection.id, success.spec, sqlQuery)
        }
        awaitCondition("SQL editor query settled") { !viewModel.sqlEditor.running }
        val result =
            withContext(mainDispatcher) {
                assertNull(viewModel.sqlEditor.error)
                assertFalse(viewModel.sqlEditor.pendingRiskGate)
                viewModel.sqlEditor.results
            }
        assertNotNull(result, "Expected SQL editor QueryResult")
        assertTrue(result.rowCount > 0, "Expected non-empty SQL editor query rows")
    }

    private suspend fun verifyCautiousGateBlocksScanEvents(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        schema: com.safedb.model.Schema,
        bulkSchema: String,
    ) {
        withContext(mainDispatcher) { viewModel.settings.setQueryRiskGate(QueryRiskGate.Cautious) }
        awaitCondition("Cautious query risk gate saved") {
            viewModel.settings.settings.value.queryRiskGate == QueryRiskGate.Cautious
        }
        val scanEventsTable =
            IntegrationFixtures.requireSeededTable(
                schema = schema,
                tableName = "scan_events",
                requiredColumns = setOf("id", "label"),
                expectedSchema = bulkSchema,
            )
        withContext(mainDispatcher) {
            viewModel.query.onQueryRiskGateChanged(QueryRiskGate.Cautious)
            viewModel.query.clear()
            viewModel.query.addTable(scanEventsTable)
            viewModel.query.toggleColumn(
                "t0",
                IntegrationFixtures.seededColumn(scanEventsTable, "id"),
            )
            viewModel.query.toggleColumn(
                "t0",
                IntegrationFixtures.seededColumn(scanEventsTable, "label"),
            )
            viewModel.query.setUncapped(true)
            viewModel.query.run(connection.id)
        }
        awaitCondition("scan_events query settled") { !viewModel.query.running }
        withContext(mainDispatcher) {
            assertTrue(
                viewModel.query.pendingRiskGate,
                "Expected Cautious gate to block scan_events",
            )
            assertNull(viewModel.query.results, "Blocked scan_events query must return no results")
            assertNotNull(
                viewModel.query.error,
                "Blocked scan_events query must expose error message",
            )
            assertEquals(
                RiskGateState.Blocked,
                viewModel.query.riskEvaluationFor(connection.id)?.decision?.state,
            )
        }
    }

    private suspend fun verifyExploreRenderAndExports(
        viewModel: AppViewModel,
        connection: ConnectionDef,
        sample: com.safedb.viewmodel.BuilderQuerySample,
        htmlExport: Path,
        csvExport: Path,
    ) {
        withContext(mainDispatcher) { viewModel.openExplore(connection, sample) }
        awaitCondition("Explore preview computed") {
            val explore = viewModel.explore.value
            explore != null &&
                !explore.isLoading(ExploreMode.Pivot) &&
                explore.preview.result.rows.isNotEmpty()
        }
        val explore = checkNotNull(withContext(mainDispatcher) { viewModel.explore.value })

        val labels =
            withContext(mainDispatcher) {
                ImageComposeScene(width = 1120, height = 760, density = Density(1f)) {
                        SafeDbTheme(isDark = false) {
                            ExploreWindowContent(
                                viewModel = explore,
                                currentSpec = sample.spec,
                                onClose = {},
                                recipesViewModel = viewModel.recipes,
                                connections = viewModel.connections.connections.value,
                            )
                        }
                    }
                    .use { scene ->
                        scene.render(0L).close()
                        scene.render(50_000_000L).close()
                        scene.semanticsOwners.flatMap { owner ->
                            collectSemanticsLabels(owner.rootSemanticsNode)
                        }
                    }
            }
        assertTrue(labels.any { it.contains("Explore") }, "Expected Explore title in semantics")
        assertTrue(
            labels.any { it.contains(connection.name) },
            "Expected connection name '${connection.name}' in Explore semantics",
        )

        withContext(mainDispatcher) { explore.savePreviewHtml(htmlExport) }
        awaitCondition("HTML export finished") { !explore.exporting }
        assertNull(withContext(mainDispatcher) { explore.exportError })

        withContext(mainDispatcher) { explore.savePreviewCsv(csvExport) }
        awaitCondition("CSV export finished") { !explore.exporting }
        assertNull(withContext(mainDispatcher) { explore.exportError })

        val htmlContent = withContext(Dispatchers.IO) { Files.readString(htmlExport) }
        assertTrue(
            htmlContent.contains("<script id=\"report-data\""),
            "Expected embedded report-data JSON in exported HTML",
        )
        val csvLines =
            withContext(Dispatchers.IO) { Files.readAllLines(csvExport) }.filter { it.isNotBlank() }
        assertTrue(csvLines.size >= 2, "Expected header + data rows in exported CSV")
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMs: Long = 30_000L,
        condition: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            if (withContext(mainDispatcher) { condition() }) return
            if (System.nanoTime() >= deadlineNanos) {
                fail("Timed out after ${timeoutMs}ms waiting for $description")
            }
            delay(25.milliseconds)
        }
    }

    private fun newService(dataDir: Path): SafeDbServiceImpl =
        SafeDbServiceImpl(
            configStore = ConfigStore.new(dataDir),
            queryStore = QueryStore.new(dataDir),
            settingsStore = SettingsStore.new(dataDir),
            recipeStore = RecipeStore.new(dataDir),
        )

    private fun collectSemanticsLabels(node: SemanticsNode): List<String> = buildList {
        if (node.config.contains(SemanticsProperties.Text)) {
            val text = node.config[SemanticsProperties.Text].joinToString { it.text }
            if (text.isNotEmpty()) add(text)
        }
        for (child in node.children) {
            addAll(collectSemanticsLabels(child))
        }
    }
}
