package com.safedb.integration

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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

// Db2 LUW runs in Docker via icr.io/db2_community/db2 (privileged, linux/amd64, no TLS by
// default); seed it with scripts/docker_test_databases.sh up. Db2 on z/OS additionally needs
// the licensed db2jcc_license_cisuz.jar on the classpath: it is not bundled, so point
// SAFEDB_TEST_DB2_* at a licensed host manually instead of using the LUW container.
@Tag("integration")
class SafeDbServiceDb2IntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun db2EndToEndSaveSchemaRunAndHistory() = runBlocking {
        IntegrationAssumptions.assumeDb2Available()
        val dir = Files.createTempDirectory("safedb-db2-integration")
        val queryStore = QueryStore.new(dir)
        val service =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(dir),
                queryStore = queryStore,
                settingsStore = SettingsStore.new(dir),
            )
        val def = IntegrationAssumptions.db2ConnectionDef()
        service.createConnection(def, IntegrationAssumptions.db2Password)
        val schema = service.getSchema(def.id)
        val result =
            service.runQuery(
                QueryRunRequest(
                    def.id,
                    IntegrationFixtures.customersQuery(
                        schema,
                        limit = 2,
                        expectedSchema = "DB2INST1",
                    ),
                )
            )

        assertEquals(2, result.queryResult.rowCount)
        assertTrue(result.queryResult.truncated)
        assertEquals(com.safedb.query.QueryPlanStatus.Available, result.riskEvaluation.planStatus)
        assertEquals(1, queryStore.listHistory().size)
    }
}
