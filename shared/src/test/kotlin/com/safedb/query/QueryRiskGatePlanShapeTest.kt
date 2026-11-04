package com.safedb.query

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
        val shapes =
            listOf(
                "postgres-limit-seq-scan" to postgresSeqScan(1),
                "mysql-limit-table-scan" to mysqlTableScan(1),
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
    fun unboundedFkJoinFollowsSeverityAtEveryGateForBothEngines() {
        val spec = scanEventsJoinSpec()
        val schema = scanEventsJoinSchema()
        val static = staticRisk(spec, schema)
        val shapes =
            listOf(
                "postgres-nested-loop" to
                    NormalizedQueryPlan(
                        relations =
                            listOf(
                                PlanRelationAccess(
                                    schema = "analytics",
                                    table = "scan_events",
                                    alias = "t0",
                                    method = PlanAccessMethod.TableScan,
                                    estimatedRows = 1_000_000,
                                ),
                                PlanRelationAccess(
                                    schema = "public",
                                    table = "customers",
                                    alias = "t1",
                                    method = PlanAccessMethod.BoundedLookup,
                                    estimatedRows = 1,
                                ),
                            ),
                        joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), 1_000_000)),
                    ),
                "mysql-nested-loop" to
                    NormalizedQueryPlan(
                        relations =
                            listOf(
                                PlanRelationAccess(
                                    table = "scan_events",
                                    alias = "t0",
                                    method = PlanAccessMethod.TableScan,
                                    estimatedRows = 1_000_000,
                                ),
                                PlanRelationAccess(
                                    table = "customers",
                                    alias = "t1",
                                    method = PlanAccessMethod.BoundedLookup,
                                    estimatedRows = 1,
                                ),
                            ),
                        joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), 1_000_000)),
                    ),
            )

        for ((name, plan) in shapes) {
            val refined = refineRiskWithPlan(static, plan, spec, schema)
            assertEquals(
                RiskSignalCode.PlanConfirmedLargeScan,
                refined.signals.single { it.code == RiskSignalCode.PlanConfirmedLargeScan }.code,
                name,
            )
            assertEquals(
                RiskGateState.Allowed,
                applyRiskGate(refined, QueryRiskGate.Flexible).state,
                name,
            )
            assertGate(
                refined,
                name,
                blocked = gatesAtOrBelow(refined.severity),
                allowed = gatesAbove(refined.severity),
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

private fun scanEventsSpec(limit: Int, uncapped: Boolean = false) =
    QuerySpec(
        tables = listOf(TableRef("analytics", "scan_events", "t0")),
        columns = listOf(ColumnSel("t0", "id")),
        filters = FilterGroup.empty(),
        limit = limit,
        uncapped = uncapped,
        schemaVersion = CURRENT_SCHEMA_VERSION,
    )

private fun scanEventsJoinSpec() =
    QuerySpec(
        tables =
            listOf(
                TableRef("analytics", "scan_events", "t0"),
                TableRef("public", "customers", "t1"),
            ),
        columns = listOf(ColumnSel("t0", "id")),
        joins = listOf(JoinSpec("t0", "customer_id", "t1", "id")),
        filters = FilterGroup.empty(),
        limit = 500,
        uncapped = true,
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
                indexes =
                    listOf(
                        IndexInfo(
                            "customers_pkey",
                            columns = listOf("id"),
                            keys = listOf(IndexKey("id")),
                            capabilities = IndexCapabilities(equality = true, ordering = true),
                            isUnique = true,
                            isPrimary = true,
                            isPartial = false,
                        )
                    ),
                size = TableSizeClass.Small,
            ),
        )
    )

private fun largeTable(
    schema: String,
    name: String,
    columns: List<ColumnInfo>,
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
) = table(schema, name, columns, foreignKeys = foreignKeys, size = TableSizeClass.Large)

private fun table(
    schema: String,
    name: String,
    columns: List<ColumnInfo>,
    indexes: List<IndexInfo> =
        listOf(
            IndexInfo(
                "${name}_pkey",
                columns = listOf("id"),
                keys = listOf(IndexKey("id")),
                capabilities = IndexCapabilities(equality = true, ordering = true),
                isUnique = true,
                isPrimary = true,
                isPartial = false,
            )
        ),
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
