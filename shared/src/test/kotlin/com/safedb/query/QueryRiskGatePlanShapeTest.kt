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
        val postgres = parsePostgresPlan(POSTGRES_UNBOUNDED_SEQ_SCAN)
        val mysql = parseMySqlPlan(MYSQL_UNBOUNDED_TABLE_SCAN)
        assertNotNull(postgres)
        assertNotNull(mysql)

        for (case in
            listOf(
                PlanGateCase("postgres-explain-seq-scan", static, postgres, schema),
                PlanGateCase("mysql-explain-table-scan", static, mysql, schema),
            )) {
            val refined = refineRiskWithPlan(case.staticAssessment, case.plan, spec, case.schema)
            assertEquals(QueryRiskSeverity.Elevated, refined.severity, case.name)
            assertEquals(4, refined.score, case.name)
            assertGate(
                refined,
                case.name,
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
        val postgres = parsePostgresPlan(POSTGRES_LIMITED_SEQ_SCAN)
        val mysql = parseMySqlPlan(MYSQL_LIMITED_TABLE_SCAN, queryLimit = 1)
        assertNotNull(postgres)
        assertNotNull(mysql)

        for (case in
            listOf(
                PlanGateCase("postgres-explain-limit", static, postgres, schema),
                PlanGateCase("mysql-explain-limit", static, mysql, schema),
            )) {
            val refined = refineRiskWithPlan(case.staticAssessment, case.plan, spec, case.schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, case.name)
            assertGate(
                refined,
                case.name,
                blocked = emptySet(),
                allowed = QueryRiskGate.entries.toSet(),
            )
        }
    }

    @Test
    fun unboundedFkJoinIsHighAtEveryGateForBothEngines() {
        val spec = scanEventsJoinSpec()
        val postgresSchema = scanEventsJoinSchema(supportingFkIndex = false)
        val mysqlSchema = scanEventsJoinSchema(supportingFkIndex = true)
        val postgresStatic = staticRisk(spec, postgresSchema)
        val mysqlStatic = staticRisk(spec, mysqlSchema)
        val postgresExplain = parsePostgresPlan(POSTGRES_UNBOUNDED_NESTED_LOOP)
        val mysqlScanEventsOuter = parseMySqlPlan(MYSQL_SCAN_EVENTS_OUTER_EQ_REF_JOIN)
        val mysqlCustomersDrive = parseMySqlPlan(MYSQL_CUSTOMERS_DRIVE_REF_JOIN)
        assertNotNull(postgresExplain)
        assertNotNull(mysqlScanEventsOuter)
        assertNotNull(mysqlCustomersDrive)

        for (case in
            listOf(
                PlanGateCase(
                    "postgres-explain-nested-loop",
                    postgresStatic,
                    postgresExplain,
                    postgresSchema,
                ),
                PlanGateCase(
                    "mysql-explain-nested-loop",
                    mysqlStatic,
                    mysqlScanEventsOuter,
                    mysqlSchema,
                ),
                PlanGateCase(
                    "mysql-explain-customers-drive-ref",
                    mysqlStatic,
                    mysqlCustomersDrive,
                    mysqlSchema,
                ),
            )) {
            val refined = refineRiskWithPlan(case.staticAssessment, case.plan, spec, case.schema)
            assertEquals(
                RiskSignalCode.PlanConfirmedJoinExpansion,
                refined.signals
                    .single { it.code == RiskSignalCode.PlanConfirmedJoinExpansion }
                    .code,
                case.name,
            )
            assertEquals(QueryRiskSeverity.High, refined.severity, case.name)
            assertGate(
                refined,
                case.name,
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
        val postgres = parsePostgresPlan(POSTGRES_SMALL_INDEXED_JOIN)
        val mysql = parseMySqlPlan(MYSQL_SMALL_INDEXED_JOIN)
        assertNotNull(postgres)
        assertNotNull(mysql)

        for (case in
            listOf(
                PlanGateCase("postgres-explain-small-indexed-join", static, postgres, schema),
                PlanGateCase("mysql-explain-small-indexed-join", static, mysql, schema),
            )) {
            val refined = refineRiskWithPlan(case.staticAssessment, case.plan, spec, case.schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, case.name)
            assertGate(
                refined,
                case.name,
                blocked = emptySet(),
                allowed = QueryRiskGate.entries.toSet(),
            )
        }
    }

    @Test
    fun limitedJoinIsAllowedAtEveryGateForBothEngines() {
        val spec = scanEventsJoinSpec(limit = 1, uncapped = false)
        val postgresSchema = scanEventsJoinSchema(supportingFkIndex = false)
        val mysqlSchema = scanEventsJoinSchema(supportingFkIndex = true)
        val postgresStatic = staticRisk(spec, postgresSchema)
        val mysqlStatic = staticRisk(spec, mysqlSchema)
        val postgresExplain = parsePostgresPlan(POSTGRES_LIMITED_NESTED_LOOP)
        val mysqlExplain = parseMySqlPlan(MYSQL_SCAN_EVENTS_OUTER_EQ_REF_JOIN, queryLimit = 1)
        val mysqlCustomersDrive = parseMySqlPlan(MYSQL_CUSTOMERS_DRIVE_REF_JOIN, queryLimit = 1)
        assertNotNull(postgresExplain)
        assertNotNull(mysqlExplain)
        assertNotNull(mysqlCustomersDrive)
        assertEquals(
            QueryRiskSeverity.Elevated,
            postgresStatic.severity,
            "postgres static FK-without-index must not decide the LIMITed gate",
        )

        for (case in
            listOf(
                PlanGateCase(
                    "postgres-explain-limited-join",
                    postgresStatic,
                    postgresExplain,
                    postgresSchema,
                ),
                PlanGateCase(
                    "mysql-explain-limited-join",
                    mysqlStatic,
                    mysqlExplain,
                    mysqlSchema,
                ),
                PlanGateCase(
                    "mysql-explain-limited-customers-drive",
                    mysqlStatic,
                    mysqlCustomersDrive,
                    mysqlSchema,
                ),
            )) {
            val refined = refineRiskWithPlan(case.staticAssessment, case.plan, spec, case.schema)
            assertEquals(QueryRiskSeverity.Minimal, refined.severity, case.name)
            assertEquals(
                false,
                refined.signals.any {
                    it.code == RiskSignalCode.ForeignKeyWithoutSupportingIndex ||
                        it.code == RiskSignalCode.JoinExpansionPossible ||
                        it.code == RiskSignalCode.PlanConfirmedJoinExpansion ||
                        it.code == RiskSignalCode.PlanConfirmedLargeScan
                },
                case.name,
            )
            assertGate(
                refined,
                case.name,
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

private data class PlanGateCase(
    val name: String,
    val staticAssessment: QueryRiskAssessment,
    val plan: NormalizedQueryPlan,
    val schema: Schema,
)

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
