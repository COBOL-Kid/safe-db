package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.model.BindValue
import com.safedb.model.CompiledQuery
import com.safedb.model.ExplainResult
import com.safedb.model.ResultCell
import com.safedb.secrets.CredentialSession
import com.safedb.secrets.DisabledMemoryStore
import com.safedb.secrets.SecretsManager
import com.safedb.testsupport.IntegrationAssumptions
import com.safedb.testsupport.IntegrationFixtures
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class MssqlAdapterIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun mssqlConnectsAndIntrospectsSeededSchema() = runBlocking {
        IntegrationAssumptions.assumeMssqlAvailable()
        val adapter =
            Adapter.connect(
                IntegrationAssumptions.mssqlConnectionDef(),
                IntegrationAssumptions.mssqlPassword,
            )
        try {
            assertTrue(adapter.test().isNotBlank())
            val schema = Adapter.introspectWithTimeout(adapter)
            val orders =
                IntegrationFixtures.requireSeededTable(
                    schema,
                    "orders",
                    setOf("id", "customer_id", "total"),
                    expectedSchema = "dbo",
                )
            val foreignKey = orders.foreignKeys.single()
            assertEquals(listOf("customer_id"), foreignKey.columns)
            assertEquals("dbo", foreignKey.referencedSchema)
            assertEquals("customers", foreignKey.referencedTable)
            assertEquals(listOf("id"), foreignKey.referencedColumns)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun mssqlBindsDecodesExplainsAndPreservesEmptyMetadata() = runBlocking {
        IntegrationAssumptions.assumeMssqlAvailable()
        val adapter =
            Adapter.connect(
                IntegrationAssumptions.mssqlConnectionDef(),
                IntegrationAssumptions.mssqlPassword,
            )
        try {
            val compiled =
                CompiledQuery(
                    sql =
                        """
                        SELECT TOP 5 [t0].[total] AS [t0__total], [t0].[notes] AS [t0__notes], [t0].[order_date] AS [t0__order_date]
                        FROM [dbo].[orders] AS [t0]
                        WHERE [t0].[status] = @P1 AND [t0].[order_date] >= @P2
                        ORDER BY [t0].[id]
                        """
                            .trimIndent(),
                    params =
                        listOf(
                            BindValue.Text("delivered"),
                            BindValue.DateTime(LocalDateTime.of(2025, 1, 1, 0, 0)),
                        ),
                )
            val result = adapter.executeQuery(compiled, 10_000)
            assertTrue(result.rows.isNotEmpty())
            assertIs<ResultCell.TextCell>(result.rows.first()[0])
            assertTrue(result.rows.any { it[1] is ResultCell.Null })
            assertIs<ResultCell.TextCell>(result.rows.first()[2])
            val explain = Adapter.explainWithTimeout(adapter, compiled)
            val plan = assertIs<ExplainResult.Available>(explain, "Expected a plan: $explain").plan
            assertTrue(plan.relations.isNotEmpty())

            val empty =
                adapter.executeQuery(
                    CompiledQuery(
                        sql =
                            "SELECT [t0].[id] AS [t0__id], [t0].[email] AS [t0__email] FROM [dbo].[customers] AS [t0] WHERE [t0].[id] = @P1",
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
