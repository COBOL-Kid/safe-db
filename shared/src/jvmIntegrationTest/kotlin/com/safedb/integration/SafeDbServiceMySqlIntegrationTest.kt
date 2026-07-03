package com.safedb.integration

import com.safedb.model.Settings
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("integration")
class SafeDbServiceMySqlIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun endToEndSaveSchemaRunQueryAndRecordHistory() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val dir = Files.createTempDirectory("safedb-integration")
        val def = IntegrationAssumptions.mysqlConnectionDef()
        val service = SafeDbServiceImpl(
            configStore = ConfigStore.new(dir),
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
        )

        service.saveConnection(def, IntegrationAssumptions.mysqlPassword)
        val schema = service.getSchema(def.id)
        val spec = IntegrationFixtures.customersQuery(schema, limit = 5)

        val result = service.runQuery(def.id, spec, force = true)
        assertTrue(result.rowCount > 0)

        val history = service.listHistory()
        assertEquals(1, history.size)
        assertEquals(def.name, history.single().connectionName)
        assertEquals(result.rowCount, history.single().rowCount)
    }
}

@Tag("integration")
class MySqlQuerySafetyIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun blockedSystemSchemaIsRejected() {
        runBlocking {
            IntegrationAssumptions.assumeMysqlAvailable()
            val dir = Files.createTempDirectory("safedb-integration")
            val def = IntegrationAssumptions.mysqlConnectionDef()
            val settingsStore = SettingsStore.new(dir)
            settingsStore.save(Settings.default().copy(blockedSchemas = listOf("mysql")))
            val service = SafeDbServiceImpl(
                configStore = ConfigStore.new(dir),
                queryStore = QueryStore.new(dir),
                settingsStore = settingsStore,
            )
            service.saveConnection(def, IntegrationAssumptions.mysqlPassword)

            val error = assertFailsWith<IllegalArgumentException> {
                service.runQuery(def.id, IntegrationFixtures.blockedSchemaQuery(), force = true)
            }
            assertTrue(error.message?.contains("Schema 'mysql' is blocked") == true)
        }
    }
}
