package com.safedb.query

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
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class QueryRiskGatePlanShapeTest {
    @Test
    fun unboundedParsedSeqScanScoresElevated() {
        val spec = scanEventsSpec(limit = 500, uncapped = true)
        val schema = scanEventsSchema()
        val static = staticRisk(spec, schema)
        val plan = parsePostgresPlan(POSTGRES_UNBOUNDED_SEQ_SCAN)
        assertNotNull(plan)

        val refined = refineRiskWithPlan(static, plan, spec, schema)
        assertEquals(QueryRiskSeverity.Elevated, refined.severity)
    }

    @Test
    fun limitCappedParsedJoinClearsFkWithoutIndexAndConfirmedExpansion() {
        val spec = scanEventsJoinSpec(limit = 1, uncapped = false)
        val schema = scanEventsJoinSchema(supportingFkIndex = false)
        val static = staticRisk(spec, schema)
        val plan = parsePostgresPlan(POSTGRES_LIMITED_NESTED_LOOP)
        assertNotNull(plan)

        val refined = refineRiskWithPlan(static, plan, spec, schema)
        assertFalse(
            refined.signals.any {
                it.code == RiskSignalCode.ForeignKeyWithoutSupportingIndex ||
                    it.code == RiskSignalCode.PlanConfirmedJoinExpansion
            },
        )
    }
}

private fun staticRisk(spec: QuerySpec, schema: Schema): QueryRiskAssessment {
    val validated =
        when (val result = validateQuery(spec, schema, emptyList(), Dialect.Postgres)) {
            is Outcome.Ok -> result.value.first
            is Outcome.Err -> error(result.message)
        }
    return assessStaticQueryRisk(validated, schema, Dialect.Postgres)
}

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

private fun scanEventsSchema() =
    Schema(
        listOf(largeTable("analytics", "scan_events", listOf(column("id"), column("customer_id")))),
    )

private fun scanEventsJoinSchema(supportingFkIndex: Boolean = true) =
    Schema(
        listOf(
            largeTable(
                "analytics",
                "scan_events",
                listOf(column("id"), column("customer_id")),
                indexes =
                    buildList {
                        add(primaryKey("scan_events"))
                        if (supportingFkIndex) {
                            add(index("idx_scan_events_customer", "customer_id"))
                        }
                    },
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "fk_scan_events_customer",
                            listOf("customer_id"),
                            "public",
                            "customers",
                            listOf("id"),
                        ),
                    ),
            ),
            table(
                "public",
                "customers",
                listOf(column("id")),
                indexes = listOf(primaryKey("customers")),
                size = TableSizeClass.Small,
            ),
        ),
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
