package com.safedb.query

import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterGroup
import com.safedb.model.IndexCapabilities
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.JoinSpec
import com.safedb.model.MetadataCoverage
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanBlockingOperation
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanOperationKind
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryPlanRefinementTest {
    @Test
    fun boundedLookupAndLowRangeRemoveGeneralAccessRisk() {
        for (method in listOf(PlanAccessMethod.BoundedLookup, PlanAccessMethod.BoundedRange)) {
            val refined = refineAccess(method, 12)
            assertFalse(refined.signals.any { it.target == RiskTarget.Access("t0") })
        }
    }

    @Test
    fun materialRangeReplacesGeneralAccessRiskWithOnePoint() {
        val refined = refineAccess(PlanAccessMethod.BoundedRange, 25_000)

        val signal = refined.signals.single { it.target == RiskTarget.Access("t0") }
        assertEquals(1, signal.points)
        assertEquals(SignalBasis.PlanEvidence, signal.basis)
    }

    @Test
    fun lowScanRemovesGenericRiskWhileMaterialAndCorroboratedHighScansStrengthenIt() {
        assertTrue(refineAccess(PlanAccessMethod.TableScan, 20).signals.isEmpty())
        assertEquals(4, refineAccess(PlanAccessMethod.TableScan, 25_000).signals.single().points)

        val high =
            refineAccess(
                PlanAccessMethod.TableScan,
                150_000,
                TableSizeClass.Large,
                EvidenceConfidence.Medium,
            )
        assertTrue(high.signals.single().mandatoryBlockWhenGateEnabled)
    }

    @Test
    fun corroboratedHighRowPlanScanIsMandatoryAtEveryEnabledGate() {
        val refined =
            refineAccess(
                PlanAccessMethod.TableScan,
                100_000,
                TableSizeClass.Large,
                EvidenceConfidence.High,
            )

        assertTrue(refined.signals.single().mandatoryBlockWhenGateEnabled)
        assertEquals(RiskGateState.Blocked, applyRiskGate(refined, QueryRiskGate.Flexible).state)
        assertEquals(RiskGateState.Allowed, applyRiskGate(refined, QueryRiskGate.Disabled).state)
    }

    @Test
    fun fullIndexScanBandsLikeATableScanButNeverBlocksOutright() {
        assertTrue(refineAccess(PlanAccessMethod.FullIndexScan, 20).signals.isEmpty())

        val material = refineAccess(PlanAccessMethod.FullIndexScan, 25_000)
        assertEquals(RiskSignalCode.PlanConfirmedLargeScan, material.signals.single().code)
        assertEquals(4, material.signals.single().points)

        val high =
            refineAccess(
                PlanAccessMethod.FullIndexScan,
                2_000_000,
                TableSizeClass.Large,
                EvidenceConfidence.High,
            )
        assertFalse(high.signals.single().mandatoryBlockWhenGateEnabled)
    }

    @Test
    fun fullIndexScanWithoutRowsAddsUncertaintyAndKeepsStaticSignal() {
        val refined =
            refineRiskWithPlan(
                buildAssessment("f", listOf(genericAccessSignal()), emptyList()),
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                table = "orders",
                                alias = "t0",
                                method = PlanAccessMethod.FullIndexScan,
                            )
                        )
                ),
                oneTableSpec(),
                Schema(listOf(table("orders"))),
            )

        assertEquals(
            listOf(RiskSignalCode.NoKnownCompatibleAccessPath),
            refined.signals.map(RiskSignal::code),
        )
        assertTrue(refined.uncertainties.any { it.code == "plan_access_rows_unknown" })
    }

    @Test
    fun highRowScanOnUnknownSizeTableIsMandatoryOnlyAtLargeEstimates() {
        val million = refineAccess(PlanAccessMethod.TableScan, 2_000_000, TableSizeClass.Unknown)
        assertTrue(million.signals.single().mandatoryBlockWhenGateEnabled)
        assertEquals(RiskGateState.Blocked, applyRiskGate(million, QueryRiskGate.Flexible).state)

        val moderate = refineAccess(PlanAccessMethod.TableScan, 150_000, TableSizeClass.Unknown)
        assertEquals(4, moderate.signals.single().points)
        assertFalse(moderate.signals.single().mandatoryBlockWhenGateEnabled)
    }

    @Test
    fun partitionChildRelationsCollapseOntoTheParentAliasWithSummedRows() {
        fun child(name: String, alias: String, rows: Long) =
            PlanRelationAccess(
                table = name,
                alias = alias,
                method = PlanAccessMethod.TableScan,
                estimatedRows = rows,
            )
        val refined =
            refineRiskWithPlan(
                buildAssessment("f", listOf(genericAccessSignal()), emptyList()),
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            child("orders_2024_01", "t0_1", 80_000),
                            child("orders_2024_02", "t0_2", 80_000),
                        )
                ),
                oneTableSpec(),
                Schema(listOf(table("orders", TableSizeClass.Large, EvidenceConfidence.High))),
            )

        // 80k per child is Material alone; the 160k sum bands High and blocks outright.
        val signal = refined.signals.single()
        assertEquals(RiskSignalCode.PlanConfirmedLargeScan, signal.code)
        assertTrue(signal.mandatoryBlockWhenGateEnabled)
        assertTrue(refined.uncertainties.none { it.code == "plan_relation_unmapped" })
    }

    @Test
    fun specAliasEndingInNumericSuffixStillResolvesExactly() {
        val suffixedSpec =
            QuerySpec(
                tables = listOf(TableRef("public", "orders", "t0_1")),
                columns = listOf(ColumnSel("t0_1", "id")),
                filters = FilterGroup.empty(),
                limit = 100,
            )
        val base =
            buildAssessment(
                "f",
                listOf(
                    RiskSignal(
                        RiskSignalCode.NoKnownCompatibleAccessPath,
                        RiskCategory.Access,
                        RiskSubject(tableAlias = "t0_1", table = "orders"),
                        2,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.Medium,
                        RiskTarget.Access("t0_1"),
                    )
                ),
                emptyList(),
            )
        val refined =
            refineRiskWithPlan(
                base,
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                table = "orders",
                                alias = "t0_1",
                                method = PlanAccessMethod.BoundedLookup,
                                estimatedRows = 5,
                            )
                        )
                ),
                suffixedSpec,
                Schema(listOf(table("orders"))),
            )

        assertTrue(refined.signals.isEmpty())
        assertTrue(refined.uncertainties.none { it.code == "plan_relation_unmapped" })
    }

    @Test
    fun targetSpecificPlanReplacementLeavesUnrelatedSignalsActive() {
        val refined =
            refineAccess(
                PlanAccessMethod.BoundedLookup,
                1,
                signals =
                    listOf(
                        genericAccessSignal(),
                        RiskSignal(
                            RiskSignalCode.ScanProneTextPredicate,
                            RiskCategory.Access,
                            RiskSubject(tableAlias = "t1"),
                            3,
                            SignalBasis.StaticSchema,
                            EvidenceConfidence.High,
                            RiskTarget.Access("t1", AccessRiskKind.Text),
                        ),
                    ),
            )

        assertEquals(
            listOf(RiskSignalCode.ScanProneTextPredicate),
            refined.signals.map(RiskSignal::code),
        )
    }

    @Test
    fun everySignalSharingOneAccessTargetCollapsesIntoAtMostOneReplacement() {
        val target = RiskTarget.Access("t0")
        val shared =
            listOf(
                genericAccessSignal(),
                RiskSignal(
                    RiskSignalCode.ScanProneNegativePredicate,
                    RiskCategory.Access,
                    RiskSubject(tableAlias = "t0", table = "orders", column = "status"),
                    1,
                    SignalBasis.StaticSchema,
                    EvidenceConfidence.Medium,
                    target,
                ),
            )

        val scanned = refineAccess(PlanAccessMethod.TableScan, 25_000, signals = shared)
        assertEquals(
            listOf(RiskSignalCode.PlanConfirmedLargeScan),
            scanned.signals.map(RiskSignal::code),
        )

        val bounded = refineAccess(PlanAccessMethod.BoundedLookup, 1, signals = shared)
        assertTrue(bounded.signals.isEmpty())
    }

    @Test
    fun specializedTextEvidenceReplacesBroadTextWithOnePoint() {
        val target = RiskTarget.Access("t0", AccessRiskKind.Text)
        val base =
            buildAssessment(
                "f",
                listOf(
                    RiskSignal(
                        RiskSignalCode.ScanProneTextPredicate,
                        RiskCategory.Access,
                        RiskSubject(tableAlias = "t0"),
                        3,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.High,
                        target,
                    )
                ),
                emptyList(),
            )
        val refined =
            refineRiskWithPlan(
                base,
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                table = "orders",
                                alias = "t0",
                                method = PlanAccessMethod.BoundedRange,
                                estimatedRows = 5,
                                specializedTextEvidence = true,
                            )
                        )
                ),
                oneTableSpec(),
                Schema(listOf(table("orders"))),
            )

        assertEquals(1, refined.signals.single { it.target == target }.points)
    }

    @Test
    fun unknownAccessAndMissingOperationRowsPreserveStaticSignalsAndAddUncertainty() {
        val operationTarget = RiskTarget.Operation(PlanOperationKind.Sort, setOf("t0"))
        val base =
            buildAssessment(
                "f",
                listOf(
                    genericAccessSignal(),
                    RiskSignal(
                        RiskSignalCode.LimitCannotBoundWork,
                        RiskCategory.Operations,
                        RiskSubject(operation = "sort"),
                        3,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.Medium,
                        operationTarget,
                    ),
                ),
                emptyList(),
            )
        val refined =
            refineRiskWithPlan(
                base,
                NormalizedQueryPlan(
                    relations =
                        listOf(
                            PlanRelationAccess(
                                table = "orders",
                                alias = "t0",
                                method = PlanAccessMethod.Unknown,
                                estimatedRows = 10,
                            )
                        ),
                    blockingOperations =
                        listOf(PlanBlockingOperation(PlanOperationKind.Sort, setOf("t0"), null)),
                ),
                oneTableSpec(),
                Schema(listOf(table("orders"))),
            )

        assertEquals(base.signals, refined.signals)
        assertTrue(refined.uncertainties.any { it.code == "plan_access_method_unknown" })
        assertTrue(refined.uncertainties.any { it.code == "plan_operation_rows_unknown" })
    }

    @Test
    fun planRowsRefineBlockingOperationsAndJoinExpansionBySemanticTarget() {
        val operationTarget = RiskTarget.Operation(PlanOperationKind.Sort, setOf("t0", "t1"))
        val joinTarget = RiskTarget.Join(setOf("t0", "t1"))
        val base =
            buildAssessment(
                "f",
                listOf(
                    RiskSignal(
                        RiskSignalCode.LimitCannotBoundWork,
                        RiskCategory.Operations,
                        RiskSubject(operation = "sort"),
                        3,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.Medium,
                        operationTarget,
                    ),
                    RiskSignal(
                        RiskSignalCode.JoinExpansionPossible,
                        RiskCategory.Joins,
                        RiskSubject(operation = "join"),
                        1,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.High,
                        joinTarget,
                    ),
                ),
                emptyList(),
            )
        val spec = twoTableSpec()
        val schema = Schema(listOf(table("orders"), table("items")))
        val low =
            refineRiskWithPlan(
                base,
                NormalizedQueryPlan(
                    joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), 50)),
                    blockingOperations =
                        listOf(
                            PlanBlockingOperation(PlanOperationKind.Sort, setOf("t0", "t1"), 50)
                        ),
                ),
                spec,
                schema,
            )
        assertFalse(low.signals.any { it.target == joinTarget })
        assertEquals(1, low.signals.single { it.target == operationTarget }.points)

        val high =
            refineRiskWithPlan(
                base,
                NormalizedQueryPlan(
                    joins = listOf(PlanJoinEvidence(setOf("t0", "t1"), 150_000)),
                    blockingOperations =
                        listOf(
                            PlanBlockingOperation(PlanOperationKind.Sort, setOf("t0", "t1"), 25_000)
                        ),
                ),
                spec,
                schema,
            )
        assertEquals(3, high.signals.single { it.target == operationTarget }.points)
        assertTrue(high.signals.single { it.target == joinTarget }.mandatoryBlockWhenGateEnabled)
        // Confirmed expansion must register in the score, not vanish under the category cap.
        assertEquals(3, high.categoryScores.getValue(RiskCategory.Joins))
        assertTrue(high.score > base.score)
    }

    private fun refineAccess(
        method: PlanAccessMethod,
        rows: Long,
        size: TableSizeClass = TableSizeClass.Medium,
        confidence: EvidenceConfidence = EvidenceConfidence.Medium,
        signals: List<RiskSignal> = listOf(genericAccessSignal()),
    ): QueryRiskAssessment =
        refineRiskWithPlan(
            buildAssessment("f", signals, emptyList()),
            NormalizedQueryPlan(
                relations =
                    listOf(
                        PlanRelationAccess(
                            table = "orders",
                            alias = "t0",
                            method = method,
                            estimatedRows = rows,
                        )
                    )
            ),
            oneTableSpec(),
            Schema(listOf(table("orders", size, confidence))),
        )
}

private fun genericAccessSignal() =
    RiskSignal(
        RiskSignalCode.NoKnownCompatibleAccessPath,
        RiskCategory.Access,
        RiskSubject(tableAlias = "t0", table = "orders"),
        2,
        SignalBasis.StaticSchema,
        EvidenceConfidence.Medium,
        RiskTarget.Access("t0"),
    )

private fun oneTableSpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = listOf(ColumnSel("t0", "id")),
        filters = FilterGroup.empty(),
        limit = 100,
    )

private fun twoTableSpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0"), TableRef("public", "items", "t1")),
        columns = listOf(ColumnSel("t0", "id")),
        joins = listOf(JoinSpec("t0", "id", "t1", "id")),
        filters = FilterGroup.empty(),
        limit = 100,
    )

private fun table(
    name: String,
    size: TableSizeClass = TableSizeClass.Medium,
    confidence: EvidenceConfidence = EvidenceConfidence.Medium,
): TableInfo =
    TableInfo(
        schema = "public",
        name = name,
        columns = listOf(ColumnInfo("id", "int", false, joinEligible = true)),
        indexes =
            listOf(
                IndexInfo(
                    "${name}_id",
                    columns = listOf("id"),
                    keys = listOf(IndexKey("id")),
                    capabilities = IndexCapabilities(equality = true, ordering = true),
                    isPartial = false,
                )
            ),
        indexMetadata = MetadataCoverage.complete(),
        foreignKeyMetadata = MetadataCoverage.complete(),
        tableSize = TableSizeEstimate(size, MetadataCoverage.complete(), confidence),
    )
