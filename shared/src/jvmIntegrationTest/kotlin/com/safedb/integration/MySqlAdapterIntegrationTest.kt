package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.ExplainResult
import com.safedb.model.Outcome
import com.safedb.model.ResultCell
import com.safedb.query.compileValidated
import com.safedb.query.validateQuery
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun mysqlBindsDecodesAndPreservesEmptyResultMetadata() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val adapter = Adapter.connect(
            IntegrationAssumptions.mysqlConnectionDef(),
            IntegrationAssumptions.mysqlPassword,
        )
        try {
            val result = adapter.executeQuery(
                CompiledQuery(
                    sql = """
                        SELECT `t0`.`total` AS `t0__total`, `t0`.`notes` AS `t0__notes`, `t0`.`order_date` AS `t0__order_date`
                        FROM `${IntegrationAssumptions.mysqlDatabase}`.`orders` AS `t0`
                        WHERE `t0`.`status` = ? AND `t0`.`order_date` >= ?
                        ORDER BY `t0`.`id`
                        LIMIT 5
                    """.trimIndent(),
                    params = listOf(
                        BindValue.Text("delivered"),
                        BindValue.DateTime(LocalDateTime.of(2025, 1, 1, 0, 0)),
                    ),
                ),
                10_000,
            )
            assertTrue(result.rows.isNotEmpty())
            assertIs<ResultCell.TextCell>(result.rows.first()[0])
            assertTrue(result.rows.any { it[1] is ResultCell.Null })
            assertIs<ResultCell.TextCell>(result.rows.first()[2])

            val empty = adapter.executeQuery(
                CompiledQuery(
                    sql = "SELECT `t0`.`id` AS `t0__id`, `t0`.`email` AS `t0__email` FROM `${IntegrationAssumptions.mysqlDatabase}`.`customers` AS `t0` WHERE `t0`.`id` = ?",
                    params = listOf(BindValue.Int(-1)),
                ),
                10_000,
            )
            assertTrue(empty.rows.isEmpty())
            assertEquals(listOf("t0__id", "t0__email"), empty.columns.map { it.name })
        } finally {
            adapter.close()
        }
    }
}
