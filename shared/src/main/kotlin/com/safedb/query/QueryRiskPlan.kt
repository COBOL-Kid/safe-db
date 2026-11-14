package com.safedb.query

import com.safedb.model.EvidenceConfidence
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanJoinEvidence
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanRelationAccess
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.TableInfo

internal enum class EstimatedRowBand {
    Low,
    Material,
    High,
}

internal fun estimatedRowBand(rows: Long): EstimatedRowBand =
    when {
        rows < 10_000 -> EstimatedRowBand.Low
        rows < 100_000 -> EstimatedRowBand.Material
        else -> EstimatedRowBand.High
    }

fun refineRiskWithPlan(
    staticAssessment: QueryRiskAssessment,
    plan: NormalizedQueryPlan,
    spec: QuerySpec,
    schema: Schema,
): QueryRiskAssessment {
    val tablesByAlias = tablesByAlias(spec, schema)
    val replacements = mutableMapOf<RiskTarget, RiskSignal?>()
    val uncertainties = staticAssessment.uncertainties.toMutableList()

    fun replace(target: RiskTarget, signal: RiskSignal?) {
        val existing = replacements[target]
        if (
            target !in replacements ||
                (signal != null && (existing == null || signal.points > existing.points))
        ) {
            replacements[target] = signal
        }
    }

    val mergedRelations = mergePartitionedRelations(plan.relations, spec)
    val resolvedRelations = ArrayList<ResolvedPlanRelation>(mergedRelations.size)
    for (relation in mergedRelations) {
        val alias = resolvePlanAlias(relation.alias, relation.schema, relation.table, spec)
        if (alias == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_relation_unmapped",
                    RiskSubject(
                        schema = relation.schema,
                        table = relation.table,
                        tableAlias = relation.alias,
                    ),
                    "ambiguous_or_unmapped_relation",
                )
            continue
        }
        resolvedRelations += ResolvedPlanRelation(alias, relation)
        val tableInfo = tablesByAlias[alias]
        val subject =
            tableInfo?.subject(alias)
                ?: RiskSubject(
                    tableAlias = alias,
                    schema = relation.schema,
                    table = relation.table,
                )
        if (relation.specializedTextEvidence) {
            val textTarget = RiskTarget.Access(alias, AccessRiskKind.Text)
            replace(
                textTarget,
                RiskSignal(
                    RiskSignalCode.ScanProneTextPredicate,
                    RiskCategory.Access,
                    subject,
                    1,
                    SignalBasis.PlanEvidence,
                    EvidenceConfidence.High,
                    textTarget,
                ),
            )
        }
        val target = RiskTarget.Access(alias)
        val band = relation.estimatedRows?.let(::estimatedRowBand)
        val replacement =
            when (relation.method) {
                PlanAccessMethod.BoundedLookup -> null
                PlanAccessMethod.BoundedRange ->
                    when (band) {
                        EstimatedRowBand.Low -> null
                        EstimatedRowBand.Material,
                        EstimatedRowBand.High ->
                            RiskSignal(
                                RiskSignalCode.NoKnownCompatibleAccessPath,
                                RiskCategory.Access,
                                subject,
                                1,
                                SignalBasis.PlanEvidence,
                                EvidenceConfidence.High,
                                target,
                            )
                        null -> {
                            uncertainties +=
                                RiskUncertainty(
                                    "plan_access_rows_unknown",
                                    subject,
                                    "missing_estimated_rows",
                                )
                            continue
                        }
                    }
                // A full index scan reads the whole index, so it bands like a table scan.
                PlanAccessMethod.TableScan,
                PlanAccessMethod.FullIndexScan ->
                    when (band) {
                        EstimatedRowBand.Low -> null
                        EstimatedRowBand.Material,
                        EstimatedRowBand.High -> planConfirmedLargeScan(alias, subject)

                        null -> {
                            uncertainties +=
                                RiskUncertainty(
                                    "plan_access_rows_unknown",
                                    subject,
                                    "missing_estimated_rows",
                                )
                            continue
                        }
                    }
                PlanAccessMethod.Unknown,
                PlanAccessMethod.Other -> {
                    uncertainties +=
                        RiskUncertainty(
                            "plan_access_method_unknown",
                            subject,
                            relation.method.name.lowercase(),
                        )
                    continue
                }
            }
        replace(target, replacement)
    }

    for ((kind, planAliases, estimatedRows) in plan.blockingOperations) {
        val aliases = resolvePlanAliases(planAliases, spec)
        if (planAliases.isEmpty() || aliases == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_unmapped",
                    RiskSubject(operation = kind.name.lowercase()),
                    "ambiguous_or_unmapped_operation_alias",
                )
            continue
        }
        val target = matchingOperationTarget(kind, aliases, staticAssessment.signals)
        if (target == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_unmapped",
                    RiskSubject(operation = kind.name.lowercase()),
                    "ambiguous_or_unmapped_operation",
                )
            continue
        }
        val band = estimatedRows?.let(::estimatedRowBand)
        if (band == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_rows_unknown",
                    RiskSubject(operation = kind.name.lowercase()),
                    "missing_estimated_rows",
                )
            continue
        }
        replace(
            target,
            RiskSignal(
                if (band == EstimatedRowBand.Low) RiskSignalCode.BoundedBlockingOperation
                else RiskSignalCode.LimitCannotBoundWork,
                RiskCategory.Operations,
                RiskSubject(operation = kind.name.lowercase()),
                if (band == EstimatedRowBand.Low) 1 else 3,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                target,
            ),
        )
    }

    refineJoins(
        plan.joins,
        resolvedRelations,
        spec,
        tablesByAlias,
        staticAssessment.signals,
        ::replace,
        uncertainties,
    )

    // Every static signal sharing a refined target drops out together, so several static signals
    // collapse into the single strongest plan-based replacement for that target.
    val retained = staticAssessment.signals.filterNot { it.target in replacements }
    val active =
        if (planRowsAreSmall(mergedRelations, plan.joins)) {
            retained.filterNot(::staticJoinRiskClearedBySmallPlan)
        } else {
            retained
        } + replacements.values.filterNotNull()
    return buildAssessment(staticAssessment.queryFingerprint, active, uncertainties)
}

fun preserveStaticRiskForUnavailablePlan(
    staticAssessment: QueryRiskAssessment,
    reason: PlanUnavailableReason,
): QueryRiskAssessment =
    staticAssessment.copy(
        uncertainties =
            staticAssessment.uncertainties +
                RiskUncertainty(
                    "plan_unavailable",
                    RiskSubject(operation = "query plan"),
                    reason.name,
                ),
    )

// Postgres appends a per-partition suffix to the query alias (t0_1, t0_2).
private val partitionAliasSuffix = Regex("_\\d+$")

// High by itself (6) so a large join does not need a synthesized Access scan. When a join
// side already confirmed a large scan, keep 3 so Access 4 + Joins 3 (typically plus a
// 1-point static join signal) scores High rather than VeryHigh.
private const val JOIN_EXPANSION_POINTS = 6
private const val JOIN_EXPANSION_POINTS_WITH_SIDE_SCAN = 3

// Partitioned scans arrive as one relation per child; collapse them onto the resolved alias so
// summed rows band by the total scanned work instead of per-partition slices.
private fun mergePartitionedRelations(
    relations: List<PlanRelationAccess>,
    spec: QuerySpec,
): List<PlanRelationAccess> =
    relations
        .groupBy { relation ->
            resolvePlanAlias(relation.alias, relation.schema, relation.table, spec)?.let { alias ->
                alias to relation.method
            }
        }
        .flatMap { (key, group) ->
            if (key == null || group.size == 1) {
                group
            } else {
                val rows = group.mapNotNull(PlanRelationAccess::estimatedRows)
                listOf(
                    group
                        .first()
                        .copy(
                            estimatedRows = if (rows.isEmpty()) null else rows.sum(),
                            specializedTextEvidence =
                                group.any(PlanRelationAccess::specializedTextEvidence),
                        ),
                )
            }
        }

private fun resolvePlanAlias(
    alias: String?,
    schema: String?,
    table: String?,
    spec: QuerySpec,
): String? {
    alias?.let { explicit ->
        spec.tables
            .singleOrNull { it.alias.equals(explicit, ignoreCase = true) }
            ?.let {
                return it.alias
            }
    }
    if (table != null) {
        val matches =
            spec.tables.filter { ref ->
                ref.name.equals(table, ignoreCase = true) &&
                    (schema == null || ref.schema.equals(schema, ignoreCase = true))
            }
        matches.singleOrNull()?.let {
            return it.alias
        }
    }
    // Exact matches win above, so a spec alias that genuinely ends in _N is never misrouted.
    val stripped = alias?.replace(partitionAliasSuffix, "")
    if (stripped != null && stripped != alias) {
        return spec.tables.singleOrNull { it.alias.equals(stripped, ignoreCase = true) }?.alias
    }
    return null
}

// Partition children resolve many-to-one onto the parent alias, so the resolved set may be
// smaller than the plan's; null (an alias that resolves to nothing) marks true ambiguity.
private fun resolvePlanAliases(aliases: Set<String>, spec: QuerySpec): Set<String>? {
    val resolved = linkedSetOf<String>()
    for (alias in aliases) {
        resolved += resolvePlanAlias(alias, null, alias, spec) ?: return null
    }
    return resolved
}

private fun matchingOperationTarget(
    kind: PlanOperationKind,
    aliases: Set<String>,
    signals: List<RiskSignal>,
): RiskTarget.Operation? {
    val candidates =
        signals
            .mapNotNull { it.target as? RiskTarget.Operation }
            .filter { it.kind == kind }
            .distinct()
    return candidates.singleOrNull { it.aliases == aliases }
}

private fun matchingJoinTarget(aliases: Set<String>, signals: List<RiskSignal>): RiskTarget.Join? {
    val candidates = signals.mapNotNull { it.target as? RiskTarget.Join }.distinct()
    return candidates.singleOrNull { it.aliases == aliases }
}

private data class ResolvedPlanRelation(val alias: String, val relation: PlanRelationAccess)

// Unique FK joins have no static JoinExpansionPossible target. Still score the join from
// plan rows, including a large nested-loop / ref / eq_ref inner that an indexed FK drive
// order can hide from the outer-scan path.
private fun refineJoins(
    joins: List<PlanJoinEvidence>,
    resolvedRelations: List<ResolvedPlanRelation>,
    spec: QuerySpec,
    tablesByAlias: Map<String, TableInfo?>,
    staticSignals: List<RiskSignal>,
    replace: (RiskTarget, RiskSignal?) -> Unit,
    uncertainties: MutableList<RiskUncertainty>,
) {
    for ((planAliases, estimatedOutputRows) in joins) {
        val aliases = resolvePlanAliases(planAliases, spec)
        if (planAliases.isEmpty() || aliases == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_join_unmapped",
                    RiskSubject(operation = "join"),
                    "ambiguous_or_unmapped_join_alias",
                )
            continue
        }
        val target = matchingJoinTarget(aliases, staticSignals) ?: RiskTarget.Join(aliases)
        val joinSides = resolvedRelations.mapNotNull { resolved ->
            if (resolved.alias !in aliases) {
                null
            } else {
                JoinSide(resolved.alias, resolved.relation, joinAccessRows(resolved.relation))
            }
        }
        val drivingScanRows = joinSides.mapNotNull(JoinSide::examinedRows).maxOrNull()
        val joinRows =
            if (estimatedOutputRows != null && drivingScanRows != null) {
                maxOf(estimatedOutputRows, drivingScanRows)
            } else {
                estimatedOutputRows ?: drivingScanRows
            }
        var largePerSideScan = false
        for (side in joinSides) {
            when (side.examinedRows?.let(::estimatedRowBand)) {
                EstimatedRowBand.Low,
                null -> Unit
                EstimatedRowBand.Material,
                EstimatedRowBand.High -> {
                    largePerSideScan = true
                    // Table/index scans already confirmed a large scan in the relation pass.
                    // Lookups and ranges do not, so join examined-rows still need to.
                    if (joinSideNeedsAccessScanSignal(side.relation.method)) {
                        replace(
                            RiskTarget.Access(side.alias),
                            planConfirmedLargeScan(
                                side.alias,
                                joinSideSubject(side, tablesByAlias),
                            ),
                        )
                    }
                }
            }
        }
        when (joinRows?.let(::estimatedRowBand)) {
            EstimatedRowBand.Low -> replace(target, null)
            EstimatedRowBand.Material -> Unit
            EstimatedRowBand.High ->
                replace(target, planConfirmedJoinExpansion(target, largePerSideScan))
            null ->
                uncertainties +=
                    RiskUncertainty(
                        "plan_join_rows_unknown",
                        RiskSubject(operation = "join"),
                        "missing_estimated_rows",
                    )
        }
    }
}

private fun joinSideNeedsAccessScanSignal(method: PlanAccessMethod): Boolean =
    when (method) {
        PlanAccessMethod.BoundedLookup,
        PlanAccessMethod.BoundedRange -> true
        PlanAccessMethod.TableScan,
        PlanAccessMethod.FullIndexScan,
        PlanAccessMethod.Unknown,
        PlanAccessMethod.Other -> false
    }

private data class JoinSide(
    val alias: String,
    val relation: PlanRelationAccess,
    val examinedRows: Long?,
)

// Nested-loop / ref / eq_ref inners examine their estimated row set even when the access
// path is an indexed lookup, so join scoring must not ignore those methods.
private fun joinAccessRows(relation: PlanRelationAccess): Long? =
    when (relation.method) {
        PlanAccessMethod.TableScan,
        PlanAccessMethod.FullIndexScan,
        PlanAccessMethod.BoundedLookup,
        PlanAccessMethod.BoundedRange -> relation.estimatedRows
        PlanAccessMethod.Unknown,
        PlanAccessMethod.Other -> null
    }

private fun planConfirmedLargeScan(alias: String, subject: RiskSubject): RiskSignal =
    RiskSignal(
        RiskSignalCode.PlanConfirmedLargeScan,
        RiskCategory.Access,
        subject,
        4,
        SignalBasis.PlanEvidence,
        EvidenceConfidence.High,
        RiskTarget.Access(alias),
    )

private fun planConfirmedJoinExpansion(
    target: RiskTarget.Join,
    largePerSideScan: Boolean,
): RiskSignal =
    RiskSignal(
        RiskSignalCode.PlanConfirmedJoinExpansion,
        RiskCategory.Joins,
        RiskSubject(operation = target.displayName()),
        if (largePerSideScan) JOIN_EXPANSION_POINTS_WITH_SIDE_SCAN else JOIN_EXPANSION_POINTS,
        SignalBasis.PlanEvidence,
        EvidenceConfidence.High,
        target,
    )

private fun joinSideSubject(
    side: JoinSide,
    tablesByAlias: Map<String, TableInfo?>,
): RiskSubject =
    tablesByAlias[side.alias]?.subject(side.alias)
        ?: RiskSubject(
            tableAlias = side.alias,
            schema = side.relation.schema,
            table = side.relation.table,
        )

private fun planRowsAreSmall(
    relations: List<PlanRelationAccess>,
    joins: List<PlanJoinEvidence>,
): Boolean =
    relations.isNotEmpty() &&
        relations.all { relation ->
            relation.estimatedRows?.let { estimatedRowBand(it) == EstimatedRowBand.Low } == true
        } &&
        joins.all { join ->
            join.estimatedOutputRows?.let { estimatedRowBand(it) == EstimatedRowBand.Low } == true
        }

private fun staticJoinRiskClearedBySmallPlan(signal: RiskSignal): Boolean =
    when (signal.code) {
        RiskSignalCode.ForeignKeyWithoutSupportingIndex,
        RiskSignalCode.JoinExpansionPossible -> true
        RiskSignalCode.NoEffectiveRestriction,
        RiskSignalCode.NoKnownCompatibleAccessPath,
        RiskSignalCode.ScanProneTextPredicate,
        RiskSignalCode.ScanProneNegativePredicate,
        RiskSignalCode.OrBranchWithoutCompatiblePath,
        RiskSignalCode.AdditionalJoinedRelation,
        RiskSignalCode.LimitCannotBoundWork,
        RiskSignalCode.BoundedBlockingOperation,
        RiskSignalCode.MaterialProjectedPayload,
        RiskSignalCode.PlanConfirmedLargeScan,
        RiskSignalCode.PlanConfirmedJoinExpansion -> false
    }
