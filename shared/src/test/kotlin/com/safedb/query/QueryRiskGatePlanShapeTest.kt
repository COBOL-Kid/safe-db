package com.safedb.query

import com.safedb.adapter.parseMySqlPlan
import com.safedb.adapter.parsePostgresPlan
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterGroup
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexCapabilities
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.JoinSpec
import com.safedb.model.MetadataCoverage
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.Outcome
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanRelationAccess
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QueryRiskGatePlanShapeTest {
    @Test
    fun unboundedLargeTableScanFollowsSeverityAtEveryGateForBothEngines() {
        val spec = scanEventsSpec(limit = 500, uncapped = true)
        val schema = scanEventsSchema()
        val static = staticRisk(spec, schema)
        val shapes =
            listOf(
                "postgres-seq-scan" to postgresSeqScan(1_000_000),
                "mysql-table-scan" to mysqlTableScan(1_000_000),
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(QueryRiskSeverity.Elevated, refined.severity, name)
            assertEquals(4, refined.score, name)
            assertGate(
                refined,
                name,
                blocked = setOf(QueryRiskGate.Cautious),
                allowed =
                    setOf(QueryRiskGate.Standard, QueryRiskGate.Flexible, QueryRiskGate.Disabled),
            )
        }
    }

    @Test
    fun limitOneScanIsAllowedAtEveryGateForBothEngines() {
        val spec = scanEventsSpec(limit = 1)
        val schema = scanEventsSchema()
        val static = staticRisk(spec, schema)
        val mysqlExplain =
            parseMySqlPlan(
                """{"query_block":{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":906091,"rows":906091}}}""",
                queryLimit = 1,
            )
        val postgresExplain =
            parsePostgresPlan(
                """[{"Plan":{"Node Type":"Limit","Plan Rows":1,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000}]}}]"""
            )
        assertNotNull(mysqlExplain)
        assertNotNull(postgresExplain)
        val shapes =
            listOf(
                "postgres-limit-seq-scan" to postgresSeqScan(1),
                "mysql-limit-table-scan" to mysqlTableScan(1),
                "postgres-explain-limit" to postgresExplain,
                "mysql-explain-limit" to mysqlExplain,
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, name)
            assertGate(
                refined,
                name,
                blocked = emptySet(),
                allowed = QueryRiskGate.entries.toSet(),
            )
        }
    }

    @Test
    fun unboundedFkJoinIsHighAtEveryGateForBothEngines() {
        val spec = scanEventsJoinSpec()
        val schema = scanEventsJoinSchema()
        val static = staticRisk(spec, schema)
        val mysqlExplain =
            parseMySqlPlan(
                """{"query_block":{"nested_loop":[{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":906091,"rows_produced_per_join":906091}},{"table":{"table_name":"customers","table_alias":"t1","access_type":"eq_ref","rows_examined_per_scan":1,"rows_produced_per_join":1}}]}}"""
            )
        val postgresExplain =
            parsePostgresPlan(
                """[{"Plan":{"Node Type":"Nested Loop","Plan Rows":1000000,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000},{"Node Type":"Index Scan","Schema":"public","Relation Name":"customers","Alias":"t1","Plan Rows":1,"Index Cond":"(id = customer_id)"}]}}]"""
            )
        assertNotNull(mysqlExplain)
        assertNotNull(postgresExplain)
        val shapes =
            listOf(
                "postgres-nested-loop" to
                    fkJoinPlan(
                        scanRows = 1_000_000,
                        joinRows = 1_000_000,
                        postgres = true,
                    ),
                "mysql-nested-loop" to
                    fkJoinPlan(scanRows = 1_000_000, joinRows = 1_000_000, postgres = false),
                "mysql-eq-ref-inner-reports-one-row" to
                    fkJoinPlan(scanRows = 1_000_000, joinRows = 1, postgres = false),
                "postgres-explain-nested-loop" to postgresExplain,
                "mysql-explain-nested-loop" to mysqlExplain,
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(
                RiskSignalCode.PlanConfirmedLargeScan,
                refined.signals.single { it.code == RiskSignalCode.PlanConfirmedLargeScan }.code,
                name,
            )
            assertEquals(QueryRiskSeverity.High, refined.severity, name)
            assertGate(
                refined,
                name,
                blocked = setOf(QueryRiskGate.Cautious, QueryRiskGate.Standard),
                allowed = setOf(QueryRiskGate.Flexible, QueryRiskGate.Disabled),
            )
        }
    }

    @Test
    fun smallIndexedJoinIsAllowedAtEveryGateForBothEngines() {
        val spec = smallIndexedJoinSpec()
        val schema = smallIndexedJoinSchema()
        val static = staticRisk(spec, schema)
        val shapes =
            listOf(
                "postgres-small-indexed-join" to smallIndexedJoinPlan(postgres = true),
                "mysql-small-indexed-join" to smallIndexedJoinPlan(postgres = false),
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, name)
            assertGate(
                refined,
                name,
                blocked = emptySet(),
                allowed = QueryRiskGate.entries.toSet(),
            )
        }
    }

    @Test
    fun limitedJoinIsAllowedAtEveryGateForBothEngines() {
        val spec = scanEventsJoinSpec(limit = 1, uncapped = false)
        val schema = scanEventsJoinSchema()
        val static = staticRisk(spec, schema)
        val mysqlExplain =
            parseMySqlPlan(
                """{"query_block":{"nested_loop":[{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":906091,"rows_produced_per_join":906091}},{"table":{"table_name":"customers","table_alias":"t1","access_type":"eq_ref","rows_examined_per_scan":1,"rows_produced_per_join":1}}]}}""",
                queryLimit = 1,
            )
        val postgresExplain =
            parsePostgresPlan(
                """[{"Plan":{"Node Type":"Limit","Plan Rows":1,"Plans":[{"Node Type":"Nested Loop","Plan Rows":1000000,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000},{"Node Type":"Index Scan","Schema":"public","Relation Name":"customers","Alias":"t1","Plan Rows":1,"Index Cond":"(id = customer_id)"}]}]}}]"""
            )
        assertNotNull(mysqlExplain)
        assertNotNull(postgresExplain)
        val shapes =
            listOf(
                "postgres-limited-nested-loop" to
                    fkJoinPlan(scanRows = 1, joinRows = 1, postgres = true),
                "mysql-limited-nested-loop" to
                    fkJoinPlan(scanRows = 1, joinRows = 1, postgres = false),
                "postgres-explain-limited-join" to postgresExplain,
                "mysql-explain-limited-join" to mysqlExplain,
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, name)
            assertGate(
                refined,
                name,
                blocked = emptySet(),
                allowed = QueryRiskGate.entries.toSet(),
            )
        }
    }

    private fun assertGate(
        assessment: QueryRiskAssessment,
        name: String,
        blocked: Set<QueryRiskGate>,
        allowed: Set<QueryRiskGate>,
    ) {
        for (gate in blocked) {
            assertEquals(
                RiskGateState.Blocked,
                applyRiskGate(assessment, gate).state,
                "$name $gate",
            )
        }
        for (gate in allowed) {
            assertEquals(
                RiskGateState.Allowed,
                applyRiskGate(assessment, gate).state,
                "$name $gate",
            )
        }
    }
}

private fun gatesAtOrBelow(severity: QueryRiskSeverity): Set<QueryRiskGate> =
    QueryRiskGate.entries
        .filter { gate ->
            val band = blockingBand(gate) ?: return@filter false
            severity.ordinal >= band.ordinal
        }
        .toSet()

private fun gatesAbove(severity: QueryRiskSeverity): Set<QueryRiskGate> =
    QueryRiskGate.entries.toSet() - gatesAtOrBelow(severity)

private fun staticRisk(spec: QuerySpec, schema: Schema): QueryRiskAssessment {
    val validated =
        when (val result = validateQuery(spec, schema, emptyList(), Dialect.Postgres)) {
            is Outcome.Ok -> result.value.first
            is Outcome.Err -> error(result.message)
        }
    return assessStaticQueryRisk(validated, schema, Dialect.Postgres)
}

private fun postgresSeqScan(rows: Long) =
    NormalizedQueryPlan(
        relations =
            listOf(
                PlanRelationAccess(
                    schema = "analytics",
                    table = "scan_events",
                    alias = "t0",
                    method = PlanAccessMethod.TableScan,
                    estimatedRows = rows,
                )
            )
    )

private fun mysqlTableScan(rows: Long) =
    NormalizedQueryPlan(
        relations =
            listOf(
                PlanRelationAccess(
                    table = "scan_events",
                    alias = "t0",
                    method = PlanAccessMethod.TableScan,
                    estimatedRows = rows,
                )
            )
    )

private fun fkJoinPlan(scanRows: Long, joinRows: Long, postgres: Boolean) =
    NormalizedQueryPlan(
        relations =
            listOf(
                PlanRelationAccess(
                    schema = if (postgres) "analytics" else null,
                    table = "scan_events",
                    alias = "t0",
                    method = PlanAccessMethod.TableScan,
                    estimatedRows = scanRows,
                ),
                PlanRelationAccess(
                    schema = if (postgres) "public" else null,
                    table = "customers",
                    alias = "t1",
                    method = PlanAccessMethod.BoundedLookup,
                    estimatedRows = 1,
                ),
            ),
        joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), joinRows)),
    )

private fun smallIndexedJoinPlan(postgres: Boolean) =
    NormalizedQueryPlan(
        relations =
            listOf(
                PlanRelationAccess(
                    schema = if (postgres) "public" else null,
                    table = "orders",
                    alias = "t0",
                    method = PlanAccessMethod.BoundedLookup,
                    estimatedRows = 12,
                ),
                PlanRelationAccess(
                    schema = if (postgres) "public" else null,
                    table = "customers",
                    alias = "t1",
                    method = PlanAccessMethod.BoundedLookup,
                    estimatedRows = 1,
                ),
            ),
        joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), 12)),
    )

private fun scanEventsSpec(limit: Int, uncapped: Boolean = false) =
    QuerySpec(
        tables = listOf(TableRef("analytics", "scan_events", "t0")),
        columns = listOf(ColumnSel("t0", "id")),
        filters = FilterGroup.empty(),
        limit = limit,
        uncapped = uncapped,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun scanEventsJoinSpec(limit: Int = 500, uncapped: Boolean = true) =
    QuerySpec(
        tables =
            listOf(
                TableRef("analytics", "scan_events", "t0"),
                TableRef("public", "customers", "t1"),
            ),
        columns = listOf(ColumnSel("t0", "id")),
        joins = listOf(JoinSpec("t0", "customer_id", "t1", "id")),
        filters = FilterGroup.empty(),
        limit = limit,
        uncapped = uncapped,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun smallIndexedJoinSpec() =
    QuerySpec(
        tables =
            listOf(
                TableRef("public", "orders", "t0"),
                TableRef("public", "customers", "t1"),
            ),
        columns = listOf(ColumnSel("t0", "id"), ColumnSel("t1", "email")),
        joins = listOf(JoinSpec("t0", "customer_id", "t1", "id")),
        filters = FilterGroup.empty(),
        limit = 500,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun scanEventsSchema() =
    Schema(
        listOf(largeTable("analytics", "scan_events", listOf(column("id"), column("customer_id"))))
    )

private fun scanEventsJoinSchema() =
    Schema(
        listOf(
            largeTable(
                "analytics",
                "scan_events",
                listOf(column("id"), column("customer_id")),
                indexes =
                    listOf(
                        primaryKey("scan_events"),
                        index("idx_scan_events_customer", "customer_id"),
                    ),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "fk_scan_events_customer",
                            listOf("customer_id"),
                            "public",
                            "customers",
                            listOf("id"),
                        )
                    ),
            ),
            table(
                "public",
                "customers",
                listOf(column("id")),
                indexes = listOf(primaryKey("customers")),
                size = TableSizeClass.Small,
            ),
        )
    )

private fun smallIndexedJoinSchema() =
    Schema(
        listOf(
            table(
                "public",
                "orders",
                listOf(column("id"), column("customer_id")),
                indexes = listOf(primaryKey("orders"), index("idx_orders_customer", "customer_id")),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "fk_orders_customer",
                            listOf("customer_id"),
                            "public",
                            "customers",
                            listOf("id"),
                        )
                    ),
                size = TableSizeClass.Small,
            ),
            table(
                "public",
                "customers",
                listOf(column("id"), column("email")),
                indexes = listOf(primaryKey("customers")),
                size = TableSizeClass.Small,
            ),
        )
    )

private fun largeTable(
    schema: String,
    name: String,
    columns: List<ColumnInfo>,
    indexes: List<IndexInfo> = listOf(primaryKey(name)),
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
) =
    table(
        schema,
        name,
        columns,
        indexes = indexes,
        foreignKeys = foreignKeys,
        size = TableSizeClass.Large,
    )

private fun primaryKey(table: String) =
    IndexInfo(
        "${table}_pkey",
        columns = listOf("id"),
        keys = listOf(IndexKey("id")),
        capabilities = IndexCapabilities(equality = true, ordering = true),
        isUnique = true,
        isPrimary = true,
        isPartial = false,
    )

private fun index(name: String, column: String) =
    IndexInfo(
        name,
        columns = listOf(column),
        keys = listOf(IndexKey(column)),
        capabilities = IndexCapabilities(equality = true, ordering = true),
        isUnique = false,
        isPrimary = false,
        isPartial = false,
    )

private fun table(
    schema: String,
    name: String,
    columns: List<ColumnInfo>,
    indexes: List<IndexInfo> = listOf(primaryKey(name)),
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
    size: TableSizeClass = TableSizeClass.Medium,
): TableInfo =
    TableInfo(
        schema = schema,
        name = name,
        columns = columns,
        indexes = indexes,
        foreignKeys = foreignKeys,
        indexMetadata = MetadataCoverage.complete(),
        foreignKeyMetadata = MetadataCoverage.complete(),
        tableSize = TableSizeEstimate(size, MetadataCoverage.complete(), EvidenceConfidence.High),
    )

private fun column(name: String) =
    ColumnInfo(name = name, dataType = "bigint", nullable = false, joinEligible = true)
