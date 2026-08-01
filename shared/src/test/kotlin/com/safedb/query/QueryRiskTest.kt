package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.IndexCapabilities
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.LiteralKind
import com.safedb.model.MetadataCoverage
import com.safedb.model.Outcome
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import com.safedb.model.JoinSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueryRiskTest {
    @Test
    fun categoryCapsAndDominantCategoryArithmeticAreStable() {
        val signals = listOf(
            signal(RiskCategory.Access, 10),
            signal(RiskCategory.Joins, 3),
            signal(RiskCategory.Operations, 7),
            signal(RiskCategory.Volume, 3),
        )
        val assessment = buildAssessment("fingerprint", signals, emptyList())

        assertEquals(mapOf(RiskCategory.Access to 6, RiskCategory.Joins to 2, RiskCategory.Operations to 4, RiskCategory.Volume to 2), assessment.categoryScores)
        assertEquals(10, assessment.score)
    }

    @Test
    fun descriptiveSeverityUsesVersionedBands() {
        assertEquals(QueryRiskSeverity.Minimal, severityForScore(2))
        assertEquals(QueryRiskSeverity.Elevated, severityForScore(3))
        assertEquals(QueryRiskSeverity.High, severityForScore(6))
        assertEquals(QueryRiskSeverity.VeryHigh, severityForScore(8))
    }

    @Test
    fun gateBandsBlockOnlyAtTheirNamedSeverity() {
        val elevated = assessmentWithScore(3)
        val high = assessmentWithScore(6)
        val veryHigh = assessmentWithScore(8)

        assertEquals(RiskGateState.Blocked, applyRiskGate(elevated, QueryRiskGate.Cautious).state)
        assertEquals(RiskGateState.Allowed, applyRiskGate(elevated, QueryRiskGate.Standard).state)
        assertEquals(RiskGateState.Blocked, applyRiskGate(high, QueryRiskGate.Standard).state)
        assertEquals(RiskGateState.Allowed, applyRiskGate(high, QueryRiskGate.Flexible).state)
        assertEquals(RiskGateState.Blocked, applyRiskGate(veryHigh, QueryRiskGate.Flexible).state)
    }

    @Test
    fun disabledGateDoesNotRequireAssessmentWhileEnabledGateIsPending() {
        assertEquals(RiskGateState.Allowed, applyRiskGate(null, QueryRiskGate.Disabled).state)
        assertEquals(RiskGateState.AssessmentPending, applyRiskGate(null, QueryRiskGate.Standard).state)
    }

    @Test
    fun corroboratedHighRowPlanScanIsMandatoryAtEveryEnabledGate() {
        val base = buildAssessment(
            "f",
            listOf(
                RiskSignal(
                    RiskSignalCode.NoKnownCompatibleAccessPath,
                    RiskCategory.Access,
                    RiskSubject(tableAlias = "t0", table = "orders"),
                    2,
                    SignalBasis.StaticSchema,
                    EvidenceConfidence.Medium,
                    "access:t0",
                ),
            ),
            emptyList(),
        )
        val refined = refineRiskWithPlan(
            base,
            PlanEvidence(
                accessSteps = listOf(
                    PlanAccessStep(
                        "access:t0",
                        RiskSubject(tableAlias = "t0", table = "orders"),
                        PlanAccessMethod.TableScan,
                        EstimatedRowBand.High,
                        confidentLargeCatalog = true,
                    ),
                ),
            ),
        )

        assertTrue(refined.signals.single().mandatoryBlockWhenGateEnabled)
        assertEquals(RiskGateState.Blocked, applyRiskGate(refined, QueryRiskGate.Flexible).state)
        assertEquals(RiskGateState.Allowed, applyRiskGate(refined, QueryRiskGate.Disabled).state)
    }

    @Test
    fun fingerprintBindsFilterLiteralWithoutExposingItInSignals() {
        val first = assess(spec(filters = group(leaf("t0", "id", FilterOp.Eq, "1"))), listOf(table()))
        val second = assess(spec(filters = group(leaf("t0", "id", FilterOp.Eq, "2"))), listOf(table()))

        assertNotEquals(first.queryFingerprint, second.queryFingerprint)
        assertTrue(first.signals.none { it.subject.displayName().contains("1") })
    }

    @Test
    fun unavailableIndexMetadataProducesUncertaintyInsteadOfPenalty() {
        val assessment = assess(
            spec(filters = group(leaf("t0", "id", FilterOp.Eq, "1"))),
            listOf(table(indexMetadata = MetadataCoverage.unavailable("permission_denied"))),
        )

        assertTrue(assessment.uncertainties.any { it.reasonCode == "permission_denied" })
        assertFalse(assessment.signals.any { it.code == RiskSignalCode.NoKnownCompatibleAccessPath })
    }

    @Test
    fun broadTextAndMissingPathShareTheAccessCategoryCap() {
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "notes")),
                filters = group(leaf("t0", "notes", FilterOp.Contains, "needle")),
            ),
            listOf(table(columns = listOf(column("notes", ColumnCategory.Text)), indexes = emptyList())),
        )

        assertEquals(5, assessment.categoryScores.getValue(RiskCategory.Access))
        assertEquals(QueryRiskSeverity.Elevated, assessment.severity)
    }

    @Test
    fun emptyInIsFalseAndEmptyNotInIsEffectivelyUnfiltered() {
        val emptyIn = assess(spec(filters = group(listLeaf("t0", "id", FilterOp.In))), listOf(table(indexes = emptyList())))
        val emptyNotIn = assess(spec(filters = group(listLeaf("t0", "id", FilterOp.NotIn))), listOf(table(indexes = emptyList())))

        assertFalse(emptyIn.signals.any { it.code == RiskSignalCode.NoEffectiveRestriction })
        assertTrue(emptyNotIn.signals.any { it.code == RiskSignalCode.NoEffectiveRestriction })
        assertFalse(emptyNotIn.signals.any { it.code == RiskSignalCode.ScanProneNegativePredicate })
    }

    @Test
    fun foldedFalseAndDropsSiblingLeavesFromAccessScoring() {
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "notes")),
                filters = group(
                    listLeaf("t0", "id", FilterOp.In),
                    leaf("t0", "notes", FilterOp.Contains, "needle"),
                ),
            ),
            listOf(
                table(
                    columns = listOf(column("id"), column("notes", ColumnCategory.Text)),
                    indexes = emptyList(),
                ),
            ),
        )

        assertFalse(assessment.signals.any { it.code == RiskSignalCode.ScanProneTextPredicate })
        assertFalse(assessment.signals.any { it.code == RiskSignalCode.NoKnownCompatibleAccessPath })
        assertFalse(assessment.signals.any { it.code == RiskSignalCode.NoEffectiveRestriction })
    }

    @Test
    fun foldedTrueOrDropsSiblingLeavesAndScoresAsUnrestricted() {
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "notes")),
                filters = FilterGroup(
                    id = "root",
                    connector = GroupConnector.Or,
                    children = listOf(
                        listLeaf("t0", "id", FilterOp.NotIn),
                        leaf("t0", "notes", FilterOp.Contains, "needle"),
                    ),
                ),
            ),
            listOf(
                table(
                    columns = listOf(column("id"), column("notes", ColumnCategory.Text)),
                    indexes = emptyList(),
                ),
            ),
        )

        assertTrue(assessment.signals.any { it.code == RiskSignalCode.NoEffectiveRestriction })
        assertFalse(assessment.signals.any { it.code == RiskSignalCode.ScanProneTextPredicate })
    }

    @Test
    fun mixedConnectorsUseSqlAndPrecedenceDuringConstantFolding() {
        val broad = leaf("t0", "notes", FilterOp.Contains, "needle")
        val indexed = leaf("t0", "id", FilterOp.Eq, "1")
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "id")),
                filters = group(
                    listLeaf("t0", "id", FilterOp.NotIn),
                    broad,
                    indexed,
                ),
                groups = listOf(GroupSpec("t0", "id")),
                connectorOverrides = mapOf(
                    broad.spec.id to GroupConnector.Or,
                    indexed.spec.id to GroupConnector.And,
                ),
            ),
            listOf(table(columns = listOf(column("id"), column("notes", ColumnCategory.Text)))),
        )

        assertTrue(assessment.signals.any { it.code == RiskSignalCode.NoEffectiveRestriction })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.LimitCannotBoundWork })
        assertEquals(QueryRiskSeverity.Elevated, assessment.severity)
        assertEquals(RiskGateState.Blocked, applyRiskGate(assessment, QueryRiskGate.Cautious).state)
    }

    @Test
    fun everyOrBranchMustHaveACompatibleAccessPath() {
        val indexed = leaf("t0", "id", FilterOp.Eq, "1")
        val unindexed = leaf("t0", "notes", FilterOp.Contains, "needle")
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "notes")),
                filters = group(indexed, unindexed),
                connectorOverrides = mapOf(unindexed.spec.id to GroupConnector.Or),
            ),
            listOf(table(columns = listOf(column("id"), column("notes", ColumnCategory.Text)))),
        )

        assertTrue(assessment.signals.any { it.code == RiskSignalCode.NoKnownCompatibleAccessPath })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.OrBranchWithoutCompatiblePath })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.ScanProneTextPredicate })
        assertEquals(QueryRiskSeverity.High, assessment.severity)
        assertEquals(RiskGateState.Blocked, applyRiskGate(assessment, QueryRiskGate.Standard).state)
    }

    @Test
    fun mysqlFullTextIndexDoesNotBoundContainsLikePredicate() {
        val fullText = IndexInfo(
            name = "notes_fulltext",
            columns = listOf("notes"),
            keys = listOf(IndexKey("notes")),
            kind = "FULLTEXT",
            capabilities = IndexCapabilities(
                equality = false,
                ordering = false,
                specializedText = false,
                expressionKeys = true,
                partialPredicate = false,
                includedColumns = false,
            ),
            isPartial = false,
        )
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "notes")),
                filters = group(leaf("t0", "notes", FilterOp.Contains, "needle")),
                groups = listOf(GroupSpec("t0", "notes")),
                limit = 5_000,
            ),
            listOf(
                table(
                    columns = listOf(column("notes", ColumnCategory.Text)),
                    indexes = listOf(fullText),
                ),
            ),
            dialect = Dialect.MySql,
        )

        assertTrue(assessment.signals.any { it.code == RiskSignalCode.NoKnownCompatibleAccessPath })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.ScanProneTextPredicate })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.LimitCannotBoundWork })
        assertEquals(QueryRiskSeverity.High, assessment.severity)
        assertEquals(RiskGateState.Blocked, applyRiskGate(assessment, QueryRiskGate.Standard).state)
    }

    @Test
    fun compositeEqualityPrefixMakesFollowingRangeKeyCompatible() {
        val index = index(
            "orders_lookup",
            listOf(IndexKey("account_id", SortDirection.Asc), IndexKey("created_at", SortDirection.Asc)),
        )
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "account_id")),
                filters = FilterGroup(
                    id = "root",
                    children = listOf(
                        leaf("t0", "account_id", FilterOp.Eq, "1"),
                        leaf("t0", "created_at", FilterOp.Gt, "2"),
                    ),
                ),
            ),
            listOf(
                table(
                    columns = listOf(column("account_id"), column("created_at")),
                    indexes = listOf(index),
                ),
            ),
        )

        assertFalse(assessment.signals.any { it.code == RiskSignalCode.NoKnownCompatibleAccessPath })
    }

    @Test
    fun sortCompatibilityAllowsReverseIndexScan() {
        val table = table(
            columns = listOf(column("created_at", ColumnCategory.DateTime)),
            indexes = listOf(index("orders_created", listOf(IndexKey("created_at", SortDirection.Desc)))),
        )
        val descending = assess(spec(columns = listOf(ColumnSel("t0", "created_at")), sorts = listOf(SortSpec("t0", "created_at", SortDirection.Desc))), listOf(table))
        val ascending = assess(spec(columns = listOf(ColumnSel("t0", "created_at")), sorts = listOf(SortSpec("t0", "created_at", SortDirection.Asc))), listOf(table))

        assertFalse(descending.signals.any { it.category == RiskCategory.Operations })
        assertFalse(ascending.signals.any { it.category == RiskCategory.Operations })
    }

    @Test
    fun separateIndexesDoNotSatisfyCompositeSortSequence() {
        val table = table(
            columns = listOf(column("account_id"), column("created_at", ColumnCategory.DateTime)),
            indexes = listOf(
                index("orders_account", listOf(IndexKey("account_id", SortDirection.Asc))),
                index("orders_created", listOf(IndexKey("created_at", SortDirection.Asc))),
            ),
        )
        val assessment = assess(
            spec(
                columns = listOf(ColumnSel("t0", "account_id"), ColumnSel("t0", "created_at")),
                sorts = listOf(
                    SortSpec("t0", "account_id", SortDirection.Asc),
                    SortSpec("t0", "created_at", SortDirection.Asc),
                ),
            ),
            listOf(table),
        )

        assertTrue(assessment.signals.any { it.code == RiskSignalCode.LimitCannotBoundWork })
        assertEquals(QueryRiskSeverity.Elevated, assessment.severity)
        assertEquals(RiskGateState.Blocked, applyRiskGate(assessment, QueryRiskGate.Cautious).state)
    }

    @Test
    fun compositeForeignKeyJoinCountsOneRelationship() {
        val child = table(
            name = "line_items",
            columns = listOf(column("order_id"), column("store_id")),
            indexes = listOf(index("line_items_order", listOf(IndexKey("order_id"), IndexKey("store_id")))),
            foreignKeys = listOf(ForeignKeyInfo("fk_order", listOf("order_id", "store_id"), "public", "orders", listOf("id", "store_id"))),
        )
        val parent = table(
            name = "orders",
            columns = listOf(column("id"), column("store_id")),
            indexes = listOf(index("orders_pk", listOf(IndexKey("id"), IndexKey("store_id")), unique = true)),
        )
        val query = spec(
            tables = listOf(TableRef("public", "line_items", "t0"), TableRef("public", "orders", "t1")),
            columns = listOf(ColumnSel("t0", "order_id")),
            joins = listOf(JoinSpec("t0", "order_id", "t1", "id"), JoinSpec("t0", "store_id", "t1", "store_id")),
        )
        val assessment = assess(query, listOf(child, parent))

        assertEquals(1, assessment.signals.count { it.code == RiskSignalCode.AdditionalJoinedRelation })
        assertFalse(assessment.signals.any { it.code == RiskSignalCode.ForeignKeyWithoutSupportingIndex })
    }

    @Test
    fun projectedPayloadIsScoredOnceFromExpandedProjectionAndLimit() {
        val binaryColumns = (1..6).map { column("blob_$it", ColumnCategory.Binary) }
        val assessment = assess(
            spec(columns = emptyList(), limit = 5_000),
            listOf(table(columns = binaryColumns, indexes = emptyList())),
        )

        assertEquals(1, assessment.signals.count { it.category == RiskCategory.Volume })
        assertTrue(assessment.signals.any { it.code == RiskSignalCode.HighProjectedPayload })
    }

    @Test
    fun targetSpecificPlanReplacementLeavesUnrelatedSignalsActive() {
        val base = buildAssessment(
            "f",
            listOf(
                RiskSignal(RiskSignalCode.NoKnownCompatibleAccessPath, RiskCategory.Access, RiskSubject(tableAlias = "t0"), 2, SignalBasis.StaticSchema, EvidenceConfidence.Medium, "access:t0"),
                RiskSignal(RiskSignalCode.ScanProneTextPredicate, RiskCategory.Access, RiskSubject(tableAlias = "t1"), 3, SignalBasis.StaticSchema, EvidenceConfidence.High, "text:t1:notes"),
            ),
            emptyList(),
        )
        val refined = refineRiskWithPlan(
            base,
            PlanEvidence(accessSteps = listOf(PlanAccessStep("access:t0", RiskSubject(tableAlias = "t0"), PlanAccessMethod.BoundedLookup))),
        )

        assertEquals(listOf(RiskSignalCode.ScanProneTextPredicate), refined.signals.map(RiskSignal::code))
    }

    @Test
    fun organizationPolicyResolvesToTheStricterEffectiveGateAndHardBlock() {
        val policy = OrganizationRiskPolicy(
            maximumFlexibility = QueryRiskGate.Standard,
            minimumGateByResource = mapOf("public.audit" to QueryRiskGate.Cautious),
            blockedResources = setOf("public.secrets"),
        )

        assertEquals(
            QueryRiskGate.Cautious,
            effectiveGate(QueryRiskGate.Flexible, policy, setOf("public.audit")),
        )
        assertEquals(
            RiskGateState.Blocked,
            applyRiskGate(assessmentWithScore(0), QueryRiskGate.Disabled, policy, setOf("public.secrets")).state,
        )
    }

    @Test
    fun unavailablePlanKeepsStaticSignalsAndAddsNonScoringUncertainty() {
        val base = buildAssessment("f", listOf(signal(RiskCategory.Access, 2)), emptyList())
        val refined = refineRiskWithPlan(base, PlanEvidence(unavailableReasonCode = "permission_denied"))

        assertEquals(base.score, refined.score)
        assertEquals(base.signals, refined.signals)
        assertTrue(refined.uncertainties.any { it.code == "plan_unavailable" && it.reasonCode == "permission_denied" })
    }

    private fun signal(category: RiskCategory, points: Int) = RiskSignal(
        RiskSignalCode.NoEffectiveRestriction,
        category,
        RiskSubject(),
        points,
        SignalBasis.StaticSchema,
        EvidenceConfidence.High,
    )

    private fun assessmentWithScore(score: Int): QueryRiskAssessment = QueryRiskAssessment(
        QUERY_RISK_SCORE_VERSION,
        "f",
        score,
        severityForScore(score),
        emptyMap(),
        emptyList(),
        emptyList(),
    )
}

private fun assess(spec: QuerySpec, tables: List<TableInfo>, dialect: Dialect = Dialect.Postgres): QueryRiskAssessment {
    val validated = when (val result = validateQuery(spec, Schema(tables), emptyList(), dialect)) {
        is Outcome.Ok -> result.value.first
        is Outcome.Err -> error(result.message)
    }
    return assessStaticQueryRisk(validated, Schema(tables), dialect)
}

private fun spec(
    tables: List<TableRef> = listOf(TableRef("public", "orders", "t0")),
    columns: List<ColumnSel> = listOf(ColumnSel("t0", "id")),
    joins: List<JoinSpec> = emptyList(),
    filters: FilterGroup = FilterGroup("root"),
    limit: Int = 100,
    sorts: List<SortSpec> = emptyList(),
    groups: List<GroupSpec> = emptyList(),
    connectorOverrides: Map<String, GroupConnector> = emptyMap(),
): QuerySpec = QuerySpec(
    tables = tables,
    columns = columns,
    joins = joins,
    filters = filters,
    limit = limit,
    sorts = sorts,
    groups = groups,
    schemaVersion = CURRENT_SCHEMA_VERSION,
    connectorOverrides = connectorOverrides,
)

private fun group(vararg nodes: FilterNode): FilterGroup = FilterGroup("root", GroupConnector.And, nodes.toList())

private fun leaf(alias: String, column: String, op: FilterOp, value: String): FilterNode.Leaf = FilterNode.Leaf(
    FilterSpec(
        id = "$alias-$column-${op.name}",
        tableAlias = alias,
        column = column,
        op = op,
        value = FilterValue.Single(
            FilterLiteral(if (value.toLongOrNull() != null) LiteralKind.Int else LiteralKind.Text, value),
        ),
    ),
)

private fun listLeaf(alias: String, column: String, op: FilterOp): FilterNode.Leaf = FilterNode.Leaf(
    FilterSpec(
        id = "$alias-$column-${op.name}",
        tableAlias = alias,
        column = column,
        op = op,
        value = FilterValue.ListValue(emptyList()),
    ),
)

private fun table(
    name: String = "orders",
    columns: List<ColumnInfo> = listOf(column("id")),
    indexes: List<IndexInfo> = listOf(index("orders_pk", listOf(IndexKey("id")), unique = true)),
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
    indexMetadata: MetadataCoverage = MetadataCoverage.complete(),
): TableInfo = TableInfo(
    schema = "public",
    name = name,
    columns = columns,
    indexes = indexes,
    foreignKeys = foreignKeys,
    indexMetadata = indexMetadata,
    foreignKeyMetadata = MetadataCoverage.complete(),
    tableSize = TableSizeEstimate(TableSizeClass.Medium, MetadataCoverage.complete(), EvidenceConfidence.Medium),
)

private fun column(name: String, category: ColumnCategory = ColumnCategory.Integer): ColumnInfo = ColumnInfo(
    name = name,
    dataType = when (category) {
        ColumnCategory.Integer -> "int"
        ColumnCategory.Date -> "date"
        ColumnCategory.DateTime -> "timestamp"
        ColumnCategory.Binary -> "blob"
        else -> "text"
    },
    nullable = false,
    category = category,
)

private fun index(name: String, keys: List<IndexKey>, unique: Boolean = false): IndexInfo = IndexInfo(
    name = name,
    columns = keys.mapNotNull(IndexKey::column),
    keys = keys,
    capabilities = IndexCapabilities(
        equality = true,
        ordering = true,
        specializedText = false,
        expressionKeys = true,
        partialPredicate = true,
        includedColumns = true,
    ),
    supportsEquality = true,
    isUnique = unique,
    isPrimary = unique,
    isPartial = false,
)
