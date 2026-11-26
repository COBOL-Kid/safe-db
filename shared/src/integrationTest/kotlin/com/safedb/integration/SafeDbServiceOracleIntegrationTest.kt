package com.safedb.integration

import com.safedb.query.QueryPlanStatus
import com.safedb.query.RiskGateState
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

@Tag("integration")
class SafeDbServiceOracleIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun oracleEndToEndSaveSchemaRunAndHistory() = runBlocking {
        IntegrationAssumptions.assumeOracleAvailable()
        val dir = Files.createTempDirectory("safedb-oracle-integration")
        try {
            val queryStore = QueryStore.new(dir)
            val service =
                SafeDbServiceImpl(
                    configStore = ConfigStore.new(dir),
                    queryStore = queryStore,
                    settingsStore = SettingsStore.new(dir),
                )
            val def = IntegrationAssumptions.oracleConnectionDef()
            service.createConnection(def, IntegrationAssumptions.oraclePassword)
            val schema = service.getSchema(def.id)
            val spec =
                IntegrationFixtures.customersQuery(schema, limit = 2, expectedSchema = "SAFEDB")
            val assessment = service.assessQuery(QueryRunRequest(def.id, spec))
            assertEquals(QueryPlanStatus.Available, assessment.riskEvaluation.planStatus)
            assertEquals(RiskGateState.Allowed, assessment.riskEvaluation.decision.state)

            val result = service.runQuery(QueryRunRequest(def.id, spec))
            assertEquals(2, result.queryResult.rowCount)
            assertTrue(result.queryResult.truncated)
            assertEquals(QueryPlanStatus.Available, result.riskEvaluation.planStatus)
            assertEquals(1, service.listHistory().size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
