package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.query.compileValidated
import com.safedb.query.validateQuery
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
class MySqlAdapterIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun mysqlAdapterConnectsAndIntrospectsSeededSchema() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val def = IntegrationAssumptions.mysqlConnectionDef()
        val adapter = Adapter.connect(def, IntegrationAssumptions.mysqlPassword)
        try {
            assertTrue(adapter.test().isNotBlank())
            val schema = Adapter.introspectWithTimeout(adapter)
            val orders = IntegrationFixtures.requireSeededTable(schema, "orders", setOf("id", "status"))
            IntegrationFixtures.requireSeededTable(schema, "customers", setOf("id", "email"))
            val customerForeignKey = orders.foreignKeys.single { it.name == "fk_orders_customer" }
            assertEquals(listOf("customer_id"), customerForeignKey.columns)
            assertEquals(IntegrationAssumptions.mysqlDatabase, customerForeignKey.referencedSchema)
            assertEquals("customers", customerForeignKey.referencedTable)
            assertEquals(listOf("id"), customerForeignKey.referencedColumns)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun mysqlAdapterExecutesSelectAndExplain() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val def = IntegrationAssumptions.mysqlConnectionDef()
        val adapter = Adapter.connect(def, IntegrationAssumptions.mysqlPassword)
        try {
            val schema = Adapter.introspectWithTimeout(adapter)
            val spec = IntegrationFixtures.customersQuery(schema, limit = 5)
            val validated = when (val validation = validateQuery(spec, schema, emptyList())) {
                is Outcome.Ok -> validation.value.first
                is Outcome.Err -> error(validation.message)
            }
            val compiled = when (val compilation = compileValidated(validated, def.dialect)) {
                is Outcome.Ok -> compilation.value
                is Outcome.Err -> error(compilation.message)
            }
            val explain = Adapter.explainWithTimeout(adapter, compiled)
            assertTrue(explain is ExplainResult.Estimated || explain is ExplainResult.Unavailable)
            val result = adapter.executeQuery(compiled, timeoutMs = 10_000)
            assertTrue(result.rowCount > 0)
            assertTrue(result.columns.isNotEmpty())
        } finally {
            adapter.close()
        }
    }
}
