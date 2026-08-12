package com.safedb.query

import com.safedb.model.EvidenceConfidence
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass

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

    for ((schema, table, planAlias, method, estimatedRows, specializedTextEvidence) in
        plan.relations) {
        val alias = resolvePlanAlias(planAlias, schema, table, spec)
        if (alias == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_relation_unmapped",
                    RiskSubject(schema = schema, table = table, tableAlias = planAlias),
                    "ambiguous_or_unmapped_relation",
                )
            continue
        }
        val tableInfo = tablesByAlias[alias]
        val subject =
            tableInfo?.subject(alias)
                ?: RiskSubject(tableAlias = alias, schema = schema, table = table)
        if (specializedTextEvidence) {
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
        val band = estimatedRows?.let(::estimatedRowBand)
        val replacement =
            when (method) {
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
                PlanAccessMethod.TableScan ->
                    when (band) {
                        EstimatedRowBand.Low -> null
                        EstimatedRowBand.Material,
                        EstimatedRowBand.High ->
                            RiskSignal(
                                RiskSignalCode.PlanConfirmedLargeScan,
                                RiskCategory.Access,
                                subject,
                                4,
                                SignalBasis.PlanEvidence,
                                EvidenceConfidence.High,
                                target,
                                mandatoryBlockWhenGateEnabled =
                                    band == EstimatedRowBand.High && tableInfo.isConfidentLarge(),
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
                PlanAccessMethod.FullIndexScan -> continue
                PlanAccessMethod.Unknown,
                PlanAccessMethod.Other -> {
                    uncertainties +=
                        RiskUncertainty(
                            "plan_access_method_unknown",
                            subject,
                            method.name.lowercase(),
                        )
                    continue
                }
            }
        replace(target, replacement)
    }

    for ((kind, planAliases, estimatedRows) in plan.blockingOperations) {
        val aliases = resolvePlanAliases(planAliases, spec)
        if (planAliases.isEmpty() || aliases.size != planAliases.size) {
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

    for ((planAliases, estimatedOutputRows) in plan.joins) {
        val aliases = resolvePlanAliases(planAliases, spec)
        if (planAliases.isEmpty() || aliases.size != planAliases.size) {
            uncertainties +=
                RiskUncertainty(
                    "plan_join_unmapped",
                    RiskSubject(operation = "join"),
                    "ambiguous_or_unmapped_join_alias",
                )
            continue
        }
        val target = matchingJoinTarget(aliases, staticAssessment.signals)
        if (target == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_join_unmapped",
                    RiskSubject(operation = "join"),
                    "ambiguous_or_unmapped_join",
                )
            continue
        }
        when (estimatedOutputRows?.let(::estimatedRowBand)) {
            EstimatedRowBand.Low -> replace(target, null)
            EstimatedRowBand.Material -> Unit
            EstimatedRowBand.High ->
                replace(
                    target,
                    RiskSignal(
                        RiskSignalCode.PlanConfirmedJoinExpansion,
                        RiskCategory.Joins,
                        RiskSubject(operation = target.displayName()),
                        3,
                        SignalBasis.PlanEvidence,
                        EvidenceConfidence.High,
                        target,
                        mandatoryBlockWhenGateEnabled =
                            joinUniquenessProvesNeitherSideUnique(
                                target.aliases,
                                spec,
                                tablesByAlias,
                            ),
                    ),
                )
            null ->
                uncertainties +=
                    RiskUncertainty(
                        "plan_join_rows_unknown",
                        RiskSubject(operation = "join"),
                        "missing_estimated_rows",
                    )
        }
    }

    // Every static signal sharing a refined target drops out together, so several static signals
    // collapse into the single strongest plan-based replacement for that target.
    val retained = staticAssessment.signals.filterNot { it.target in replacements }
    val active = retained + replacements.values.filterNotNull()
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
                )
    )

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
    if (table == null) return null
    val matches =
        spec.tables.filter { ref ->
            ref.name.equals(table, ignoreCase = true) &&
                (schema == null || ref.schema.equals(schema, ignoreCase = true))
        }
    return matches.singleOrNull()?.alias
}

private fun resolvePlanAliases(aliases: Set<String>, spec: QuerySpec): Set<String> =
    aliases.mapNotNullTo(linkedSetOf()) { value -> resolvePlanAlias(value, null, value, spec) }

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

private fun TableInfo?.isConfidentLarge(): Boolean =
    this != null &&
        tableSize.sizeClass == TableSizeClass.Large &&
        tableSize.coverage.isComplete &&
        tableSize.confidence in setOf(EvidenceConfidence.Medium, EvidenceConfidence.High)

private fun joinUniquenessProvesNeitherSideUnique(
    aliases: Set<String>,
    spec: QuerySpec,
    tablesByAlias: Map<String, TableInfo?>,
): Boolean {
    if (aliases.size != 2) return false
    val joins = spec.joins.filter { setOf(it.leftAlias, it.rightAlias) == aliases }
    return joins.isNotEmpty() &&
        aliases.all { alias ->
            val table = tablesByAlias[alias] ?: return false
            table.indexMetadata.isComplete &&
                !exactUniqueJoinKey(table, joinedColumns(alias, joins))
        }
}
