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

// Db2 LUW runs in Docker via icr.io/db2_community/db2 (privileged, linux/amd64, no TLS by
// default); seed it with scripts/docker_test_databases.sh up. Db2 on z/OS additionally needs
// the licensed db2jcc_license_cisuz.jar on the classpath: it is not bundled, so point
// SAFEDB_TEST_DB2_* at a licensed host manually instead of using the LUW container.
@Tag("integration")
class Db2AdapterIntegrationTest {
    @BeforeEach
    fun setup() {
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun db2ConnectsAndIntrospectsSeededSchema() = runBlocking {
        IntegrationAssumptions.assumeDb2Available()
        val adapter =
            Adapter.connect(
                IntegrationAssumptions.db2ConnectionDef(),
                IntegrationAssumptions.db2Password,
            )
        try {
            assertTrue(adapter.test().isNotBlank())
            val schema = Adapter.introspectWithTimeout(adapter)
            val orders =
                IntegrationFixtures.requireSeededTable(
                    schema,
                    "ORDERS",
                    setOf("ID", "CUSTOMER_ID", "TOTAL"),
                    expectedSchema = "DB2INST1",
                )
            // LUW resolves this key through SYSCAT.REFERENCES. A z/OS foreign key whose parent
            // is a non-primary unique index joins SYSRELS.IXOWNER and IXNAME; that needs a
            // live subsystem and is not covered here.
            val foreignKey = orders.foreignKeys.single()
            assertEquals(listOf("CUSTOMER_ID"), foreignKey.columns)
            assertEquals("DB2INST1", foreignKey.referencedSchema)
            assertEquals("CUSTOMERS", foreignKey.referencedTable)
            assertEquals(listOf("ID"), foreignKey.referencedColumns)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun db2BindsDecodesExplainsAndPreservesEmptyMetadata() = runBlocking {
        IntegrationAssumptions.assumeDb2Available()
        val adapter =
            Adapter.connect(
                IntegrationAssumptions.db2ConnectionDef(),
                IntegrationAssumptions.db2Password,
            )
        try {
            val compiled =
                CompiledQuery(
                    sql =
                        """
                        SELECT "t0"."TOTAL" AS "t0__total", "t0"."NOTES" AS "t0__notes", "t0"."ORDER_DATE" AS "t0__order_date"
                        FROM "DB2INST1"."ORDERS" AS "t0"
                        WHERE "t0"."STATUS" = ? AND "t0"."ORDER_DATE" >= ?
                        ORDER BY "t0"."ID"
                        FETCH FIRST 5 ROWS ONLY
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
                            "SELECT \"t0\".\"ID\" AS \"t0__id\", \"t0\".\"EMAIL\" AS \"t0__email\" FROM \"DB2INST1\".\"CUSTOMERS\" AS \"t0\" WHERE \"t0\".\"ID\" = ?",
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
