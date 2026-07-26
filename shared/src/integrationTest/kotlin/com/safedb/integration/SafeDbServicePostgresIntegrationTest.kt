package com.safedb.integration

import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.service.QueryRunRequest
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
import kotlin.test.assertTrue

@Tag("integration")
class SafeDbServicePostgresIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun postgresEndToEndSaveSchemaRunAndHistory() = runBlocking {
        IntegrationAssumptions.assumePostgresAvailable()
        val dir = Files.createTempDirectory("safedb-postgres-integration")
        val queryStore = QueryStore.new(dir)
        val service = SafeDbServiceImpl(
            configStore = ConfigStore.new(dir),
            queryStore = queryStore,
            settingsStore = SettingsStore.new(dir),
        )
        val def = IntegrationAssumptions.postgresConnectionDef()
        service.saveConnection(def, IntegrationAssumptions.postgresPassword)
        val schema = service.getSchema(def.id)
        val result = service.runQuery(
            QueryRunRequest(
                def.id,
                IntegrationFixtures.customersQuery(schema, limit = 2, expectedSchema = "public"),
                schema,
                force = true,
            ),
        )

        assertEquals(2, result.rowCount)
        assertTrue(result.truncated)
        assertEquals(1, queryStore.listHistory().size)
    }
}
