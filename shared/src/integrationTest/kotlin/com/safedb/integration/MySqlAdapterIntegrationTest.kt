package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.adapter.closeDataSource
import com.safedb.adapter.createDataSource
import com.safedb.model.BindValue
import com.safedb.model.ColumnSel
import com.safedb.model.CompiledQuery
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.ExplainResult
import com.safedb.model.FilterGroup
import com.safedb.model.GroupConnector
import com.safedb.model.Outcome
import com.safedb.model.PlanAccessMethod
import com.safedb.model.QuerySpec
import com.safedb.model.Settings
import com.safedb.model.TableRef
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskSignalCode
import com.safedb.query.assessValidatedQueryRisk
import com.safedb.query.compileValidated
import com.safedb.query.refineRiskWithPlan
import com.safedb.query.sql.SqlTokenType
import com.safedb.query.sql.mySqlBackslashEscapes
import com.safedb.query.sql.tokenizeSql
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
            val orders =
                IntegrationFixtures.requireSeededTable(schema, "orders", setOf("id", "status"))
            IntegrationFixtures.requireSeededTable(schema, "customers", setOf("id", "email"))
            val customerForeignKey = orders.foreignKeys.single { it.name == "fk_orders_customer" }
            AdapterIntegrationAssertions.assertOrdersCustomerForeignKey(
                customerForeignKey,
                referencedSchema = IntegrationAssumptions.mysqlDatabase,
            )
        } finally {
            adapter.close()
        }
    }

    @Test
    fun mysqlExplainsScanEventsAsElevatedLargeScan() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val def = IntegrationAssumptions.mysqlConnectionDef()
        val adapter = Adapter.connect(def, IntegrationAssumptions.mysqlPassword)
        try {
            val schema = Adapter.introspectWithTimeout(adapter)
            val table =
                IntegrationFixtures.requireSeededTable(
                    schema,
                    "scan_events",
                    setOf("id"),
                    expectedSchema = "safedb_lab",
                )
            assertTrue(
                table.schema.equals("safedb_lab", ignoreCase = true),
                "scan_events must come from safedb_lab, found ${table.schema}.${table.name}",
            )
            val spec =
                QuerySpec(
                    tables =
                        listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
                    columns =
                        listOf(
                            ColumnSel(
                                tableAlias = "t0",
                                column = IntegrationFixtures.seededColumn(table, "id"),
                            )
                        ),
                    joins = emptyList(),
                    filters =
                        FilterGroup(
                            id = "integration-scan-events-filters",
                            connector = GroupConnector.And,
                            children = emptyList(),
                        ),
                    limit = 1,
                    uncapped = true,
                )
            val assessed =
                when (
                    val result =
                        assessValidatedQueryRisk(spec, schema, Settings.default(), def.dialect)
                ) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> error(result.message)
                }
            val compiled =
                when (val compilation = compileValidated(assessed.validated, def.dialect)) {
                    is Outcome.Ok -> compilation.value
                    is Outcome.Err -> error(compilation.message)
                }
            // EXPLAIN only: do not execute, so the client never fetches the million rows.
            val explain = Adapter.explainWithTimeout(adapter, compiled)
            val plan = assertIs<ExplainResult.Available>(explain).plan
            assertTrue(
                plan.relations.any { it.method != PlanAccessMethod.Unknown },
                "expected a known access method for scan_events: $plan",
            )
            val staticAssessment =
                requireNotNull(assessed.assessment) { "query risk gate is disabled" }
            val refined =
                refineRiskWithPlan(staticAssessment, plan, assessed.normalizedSpec, schema)
            assertTrue(
                refined.severity == QueryRiskSeverity.Elevated ||
                    refined.severity == QueryRiskSeverity.High ||
                    refined.severity == QueryRiskSeverity.VeryHigh,
                "expected an Elevated-or-higher live scan_events classification, " +
                    "got severity=${refined.severity} plan=$plan",
            )
            assertTrue(
                refined.signals.any { it.code == RiskSignalCode.PlanConfirmedLargeScan },
                "expected PlanConfirmedLargeScan for scan_events, " +
                    "got signals=${refined.signals.map { it.code }} plan=$plan",
            )
        } finally {
            adapter.close()
        }
    }

    @Test
    fun mysqlBindsDecodesAndPreservesEmptyResultMetadata() = runBlocking {
        IntegrationAssumptions.assumeMysqlAvailable()
        val adapter =
            Adapter.connect(
                IntegrationAssumptions.mysqlConnectionDef(),
                IntegrationAssumptions.mysqlPassword,
            )
        try {
            val result =
                adapter.executeQuery(
                    CompiledQuery(
                        sql =
                            """
                        SELECT `t0`.`total` AS `t0__total`, `t0`.`notes` AS `t0__notes`, `t0`.`order_date` AS `t0__order_date`
                        FROM `${IntegrationAssumptions.mysqlDatabase}`.`orders` AS `t0`
                        WHERE `t0`.`status` = ? AND `t0`.`order_date` >= ?
                        ORDER BY `t0`.`id`
                        LIMIT 5
                    """
                                .trimIndent(),
                        params =
                            listOf(
                                BindValue.Text("delivered"),
                                BindValue.DateTime(LocalDateTime.of(2025, 1, 1, 0, 0)),
                            ),
                    ),
                    10_000,
                )
            AdapterIntegrationAssertions.assertDecodedOrdersRow(result)

            val empty =
                adapter.executeQuery(
                    CompiledQuery(
                        sql =
                            "SELECT `t0`.`id` AS `t0__id`, `t0`.`email` AS `t0__email` FROM `${IntegrationAssumptions.mysqlDatabase}`.`customers` AS `t0` WHERE `t0`.`id` = ?",
                        params = listOf(BindValue.Int(-1)),
                    ),
                    10_000,
                )
            AdapterIntegrationAssertions.assertEmptyCustomerLookupMetadata(empty)
        } finally {
            adapter.close()
        }
    }

    // Safe-DB must never decode a string differently than the session the query would run in.
    // sessionVariables pins the session's sql_mode, so the derived escape mode has to match what
    // the server actually does with the same literal.
    @Test
    fun mysqlSessionVariablesPinBackslashSemanticsForParsing() {
        IntegrationAssumptions.assumeMysqlAvailable()
        val literalSql = "SELECT @@SESSION.sql_mode AS mode, 'a\\q' AS v"

        fun serverEvaluation(def: com.safedb.model.ConnectionDef): Pair<String, String> {
            val dataSource = createDataSource(def, IntegrationAssumptions.mysqlPassword)
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(literalSql).use { rs ->
                            rs.next()
                            return rs.getString("mode") to rs.getString("v")
                        }
                    }
                }
            } finally {
                closeDataSource(dataSource)
            }
        }

        val pinned =
            IntegrationAssumptions.mysqlConnectionDef()
                .copy(
                    driverProperties =
                        listOf(
                            DriverProperty(
                                "sessionVariables",
                                "sql_mode='STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES'",
                            )
                        )
                )
        assertEquals(false, mySqlBackslashEscapes(pinned))
        val (pinnedMode, pinnedValue) = serverEvaluation(pinned)
        assertTrue(pinnedMode.contains("NO_BACKSLASH_ESCAPES"))
        assertEquals("a\\q", pinnedValue)
        val pinnedToken =
            tokenizeSql("'a\\q'", Dialect.MySql, mySqlBackslashEscapes(pinned)).single()
        assertEquals(SqlTokenType.StringLiteral, pinnedToken.type)
        assertEquals(pinnedValue, pinnedToken.value)

        // Without pinned session variables the effective mode is unknown, so Safe-DB refuses the
        // ambiguous literal instead of guessing against whatever the server default happens to be.
        val unpinned = IntegrationAssumptions.mysqlConnectionDef()
        assertEquals(null, mySqlBackslashEscapes(unpinned))
        val unpinnedToken =
            tokenizeSql("'a\\q'", Dialect.MySql, mySqlBackslashEscapes(unpinned)).single()
        assertEquals(SqlTokenType.Error, unpinnedToken.type)

        // When the default session does use backslash escapes, the escapes-on decoding matches it.
        val (defaultMode, defaultValue) = serverEvaluation(unpinned)
        if (!defaultMode.contains("NO_BACKSLASH_ESCAPES")) {
            val escapedToken = tokenizeSql("'a\\q'", Dialect.MySql, true).single()
            assertEquals(defaultValue, escapedToken.value)
        }
    }
}
