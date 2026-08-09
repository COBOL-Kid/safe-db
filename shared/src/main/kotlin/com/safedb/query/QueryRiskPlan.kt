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

enum class EstimatedRowBand {
    Low,
    Material,
    High,
}

fun estimatedRowBand(rows: Long): EstimatedRowBand =
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
    val tablesByAlias =
        spec.tables.associate { ref ->
            ref.alias to
                schema.tables.firstOrNull { it.schema == ref.schema && it.name == ref.name }
        }
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

    for (step in plan.relations) {
        val alias = resolvePlanAlias(step.alias, step.schema, step.table, spec)
        if (alias == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_relation_unmapped",
                    RiskSubject(schema = step.schema, table = step.table, tableAlias = step.alias),
                    "ambiguous_or_unmapped_relation",
                )
            continue
        }
        val table = tablesByAlias[alias]
        val subject =
            table?.subject(alias)
                ?: RiskSubject(tableAlias = alias, schema = step.schema, table = step.table)
        if (step.specializedTextEvidence) {
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
        val band = step.estimatedRows?.let(::estimatedRowBand)
        val replacement =
            when (step.method) {
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
                                    band == EstimatedRowBand.High && table.isConfidentLarge(),
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
                            step.method.name.lowercase(),
                        )
                    continue
                }
            }
        replace(target, replacement)
    }

    for (step in plan.blockingOperations) {
        val aliases = resolvePlanAliases(step.aliases, spec)
        if (step.aliases.isEmpty() || aliases.size != step.aliases.size) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_unmapped",
                    RiskSubject(operation = step.kind.name.lowercase()),
                    "ambiguous_or_unmapped_operation_alias",
                )
            continue
        }
        val target = matchingOperationTarget(step.kind, aliases, staticAssessment.signals)
        if (target == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_unmapped",
                    RiskSubject(operation = step.kind.name.lowercase()),
                    "ambiguous_or_unmapped_operation",
                )
            continue
        }
        val band = step.estimatedRows?.let(::estimatedRowBand)
        if (band == null) {
            uncertainties +=
                RiskUncertainty(
                    "plan_operation_rows_unknown",
                    RiskSubject(operation = step.kind.name.lowercase()),
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
                RiskSubject(operation = step.kind.name.lowercase()),
                if (band == EstimatedRowBand.Low) 1 else 3,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                target,
            ),
        )
    }

    for (step in plan.joins) {
        val aliases = resolvePlanAliases(step.aliases, spec)
        if (step.aliases.isEmpty() || aliases.size != step.aliases.size) {
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
        when (step.estimatedOutputRows?.let(::estimatedRowBand)) {
            EstimatedRowBand.Low -> replace(target, null)
            EstimatedRowBand.Material -> Unit
            EstimatedRowBand.High ->
                replace(
                    target,
                    RiskSignal(
                        RiskSignalCode.PlanConfirmedJoinExpansion,
                        RiskCategory.Joins,
                        RiskSubject(
                            operation = "join ${target.aliases.sorted().joinToString("-")}"
                        ),
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
    if (joins.isEmpty()) return false
    return aliases.all { alias ->
        val table = tablesByAlias[alias] ?: return false
        table.indexMetadata.isComplete && !exactUniqueJoinKey(table, joinedColumns(alias, joins))
    }
}
