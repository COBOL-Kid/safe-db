package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterValue
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.GroupConnector
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.Outcome
import com.safedb.model.NormalizedQueryPlan
import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.SafeDbJson
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import java.security.MessageDigest
import kotlin.math.max

const val QUERY_RISK_SCORE_VERSION: Int = 2

enum class RiskCategory { Access, Joins, Operations, Volume }

enum class RiskSignalCode {
    NoEffectiveRestriction,
    NoKnownCompatibleAccessPath,
    ScanProneTextPredicate,
    ScanProneNegativePredicate,
    OrBranchWithoutCompatiblePath,
    AdditionalJoinedRelation,
    ForeignKeyWithoutSupportingIndex,
    JoinExpansionPossible,
    LimitCannotBoundWork,
    BoundedBlockingOperation,
    MaterialProjectedPayload,
    HighProjectedPayload,
    PlanConfirmedLargeScan,
    PlanConfirmedJoinExpansion,
}

enum class SignalBasis { StaticSchema, PlanEvidence }

enum class AccessRiskKind { General, Text }

sealed interface RiskTarget {
    data class Access(val alias: String, val kind: AccessRiskKind = AccessRiskKind.General) : RiskTarget
    data class Join(val aliases: Set<String>) : RiskTarget
    data class Operation(val kind: PlanOperationKind, val aliases: Set<String>) : RiskTarget
}

data class RiskSubject(
    val tableAlias: String? = null,
    val schema: String? = null,
    val table: String? = null,
    val column: String? = null,
    val operation: String? = null,
) {
    fun displayName(): String = when {
        table != null && column != null -> "$table.$column"
        table != null -> table
        operation != null -> operation
        tableAlias != null -> tableAlias
        else -> "query"
    }
}

data class RiskSignal(
    val code: RiskSignalCode,
    val category: RiskCategory,
    val subject: RiskSubject,
    val points: Int,
    val basis: SignalBasis,
    val confidence: EvidenceConfidence,
    val target: RiskTarget? = null,
    val mandatoryBlockWhenGateEnabled: Boolean = false,
)

data class RiskUncertainty(
    val code: String,
    val subject: RiskSubject,
    val reasonCode: String,
)

enum class QueryRiskSeverity(val label: String) {
    Minimal("Minimal concern"),
    Elevated("Elevated concern"),
    High("High concern"),
    VeryHigh("Very high concern"),
}

data class QueryRiskAssessment(
    val scoreVersion: Int,
    val queryFingerprint: String,
    val score: Int,
    val severity: QueryRiskSeverity,
    val categoryScores: Map<RiskCategory, Int>,
    val signals: List<RiskSignal>,
    val uncertainties: List<RiskUncertainty>,
)

enum class RiskGateState { Allowed, AssessmentPending, ConfirmationRequired, Blocked }

data class RiskDecisionReason(
    val code: String,
    val message: String,
    val mandatory: Boolean = false,
)

data class QueryRiskDecision(
    val queryFingerprint: String,
    val state: RiskGateState,
    val effectiveGate: QueryRiskGate,
    val blockingBand: QueryRiskSeverity?,
    val reasons: List<RiskDecisionReason>,
)

enum class QueryConfirmationReasonCode {
    PlanUnavailable,
    OptimizerCostUnavailable,
    OptimizerCostExceeded,
}

data class QueryConfirmationCondition(
    val reasonCode: QueryConfirmationReasonCode,
    /** Stable condition identity, excluding optimizer observations that may vary between retries. */
    val conditionKey: String,
)

/** An acknowledgement scoped to the exact connection, query, and exceptional plan conditions. */
data class QueryExecutionConfirmation(
    val connectionId: String,
    val connectionFingerprint: String,
    val queryFingerprint: String,
    val conditions: Set<QueryConfirmationCondition>,
) {
    val reasonCodes: Set<QueryConfirmationReasonCode>
        get() = conditions.mapTo(linkedSetOf(), QueryConfirmationCondition::reasonCode)
}

data class QueryConfirmationRequirement(
    val confirmation: QueryExecutionConfirmation,
    val reasons: List<RiskDecisionReason>,
)

enum class QueryPlanStatus { NotRequested, Available, Incomplete, Unavailable, Disabled }

data class QueryRiskEvaluation(
    val staticAssessment: QueryRiskAssessment?,
    val finalAssessment: QueryRiskAssessment?,
    val planStatus: QueryPlanStatus,
    val planUnavailableReason: PlanUnavailableReason? = null,
    val decision: QueryRiskDecision,
    val optimizerCost: Double? = null,
    val optimizerCostThreshold: Double? = null,
    val confirmationRequirement: QueryConfirmationRequirement? = null,
    val confirmationAccepted: Boolean = false,
) {
    val assessment: QueryRiskAssessment?
        get() = finalAssessment
}

fun evaluateQueryRisk(
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    dialect: Dialect,
): Outcome<QueryRiskEvaluation> {
    val validated = when (val result = validateQuery(spec, schema, settings.blockedSchemas, dialect)) {
        is Outcome.Ok -> result.value.first
        is Outcome.Err -> return Outcome.err(result.message)
    }
    val assessment = if (settings.queryRiskGate == QueryRiskGate.Disabled) {
        null
    } else {
        assessStaticQueryRisk(validated, schema, dialect)
    }
    return Outcome.ok(
        QueryRiskEvaluation(
            staticAssessment = assessment,
            finalAssessment = assessment,
            planStatus = if (assessment == null) QueryPlanStatus.Disabled else QueryPlanStatus.NotRequested,
            decision = applyRiskGate(assessment, settings.queryRiskGate),
        ),
    )
}

fun queryFingerprint(validated: ValidatedQuery): String {
    val canonical = SafeDbJson.lenient.encodeToString(QuerySpec.serializer(), validated.spec())
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun assessStaticQueryRisk(
    validated: ValidatedQuery,
    schema: Schema,
    dialect: Dialect,
): QueryRiskAssessment {
    val spec = validated.spec()
    val tablesByAlias = spec.tables.associate { ref ->
        ref.alias to schema.tables.firstOrNull { it.schema == ref.schema && it.name == ref.name }
    }
    val signals = mutableListOf<RiskSignal>()
    val uncertainties = mutableListOf<RiskUncertainty>()
    val predicate = analyzePredicate(spec.filters, spec.connectorOverrides)
    if (predicate.constant == PredicateConstant.False) {
        return buildAssessment(queryFingerprint(validated), emptyList(), emptyList())
    }
    val leavesByAlias = predicate.leaves.groupBy { it.tableAlias }
    val directlyBoundedAliases = mutableSetOf<String>()

    for ((alias, table) in tablesByAlias) {
        if (table == null) continue
        val subject = table.subject(alias)
        val leaves = leavesByAlias[alias].orEmpty()
        if (table.tableSize.sizeClass == TableSizeClass.Unknown) {
            uncertainties += RiskUncertainty(
                "table_size_unknown",
                subject,
                table.tableSize.coverage.reasonCode ?: "table_size_metadata_unavailable",
            )
        }
        val directlyRestricted = predicate.branches?.let { branches ->
            branches.isNotEmpty() && branches.all { branch -> branch.any { it.tableAlias == alias } }
        } == true
        if (table.tableSize.sizeClass != TableSizeClass.Small && !directlyRestricted) {
            signals += RiskSignal(
                RiskSignalCode.NoEffectiveRestriction,
                RiskCategory.Access,
                subject,
                points = 2,
                basis = SignalBasis.StaticSchema,
                confidence = tableRiskConfidence(table),
                target = RiskTarget.Access(alias),
            )
        }

        if (leaves.isEmpty()) continue
        val path = analyzeAccessPaths(table, alias, predicate, dialect)
        if (directlyRestricted && path.state == AccessPathState.Compatible) directlyBoundedAliases += alias
        if (path.state == AccessPathState.Unknown) {
            uncertainties += RiskUncertainty(
                code = "index_compatibility_unknown",
                subject = subject,
                reasonCode = table.indexMetadata.reasonCode ?: "advanced_index_capability_unknown",
            )
        } else if (path.state == AccessPathState.Incompatible && table.tableSize.sizeClass != TableSizeClass.Small) {
            signals += RiskSignal(
                RiskSignalCode.NoKnownCompatibleAccessPath,
                RiskCategory.Access,
                subject,
                2,
                SignalBasis.StaticSchema,
                tableRiskConfidence(table),
                target = RiskTarget.Access(alias),
            )
            if (path.hasIncompatibleOrBranch) {
                signals += RiskSignal(
                    RiskSignalCode.OrBranchWithoutCompatiblePath,
                    RiskCategory.Access,
                    subject,
                    1,
                    SignalBasis.StaticSchema,
                    tableRiskConfidence(table),
                    target = RiskTarget.Access(alias),
                )
            }
        }

        val broad = leaves.firstOrNull(::isScanProneText)
        if (broad != null && table.tableSize.sizeClass != TableSizeClass.Small) {
            val specialized = hasSpecializedTextPath(table, broad)
            if (!specialized) {
                signals += RiskSignal(
                    RiskSignalCode.ScanProneTextPredicate,
                    RiskCategory.Access,
                    table.subject(alias, broad.column),
                    3,
                    SignalBasis.StaticSchema,
                    tableRiskConfidence(table),
                    target = RiskTarget.Access(alias, AccessRiskKind.Text),
                )
            }
        }

        val negative = leaves.firstOrNull { it.op in negativeOps }
        if (negative != null && table.tableSize.sizeClass != TableSizeClass.Small) {
            if (table.indexMetadata.isComplete) {
                signals += RiskSignal(
                    RiskSignalCode.ScanProneNegativePredicate,
                    RiskCategory.Access,
                    table.subject(alias, negative.column),
                    1,
                    SignalBasis.StaticSchema,
                    tableRiskConfidence(table),
                    target = RiskTarget.Access(alias),
                )
            } else {
                uncertainties += RiskUncertainty(
                    "negative_predicate_capability_unknown",
                    table.subject(alias, negative.column),
                    table.indexMetadata.reasonCode ?: "index_metadata_unavailable",
                )
            }
        }
    }

    addJoinSignals(spec, tablesByAlias, signals, uncertainties)
    addOperationSignals(spec, tablesByAlias, leavesByAlias, directlyBoundedAliases, signals, uncertainties)
    addVolumeSignal(validated, tablesByAlias, signals)
    return buildAssessment(queryFingerprint(validated), signals, uncertainties)
}

private fun tableRiskConfidence(table: TableInfo): EvidenceConfidence = when (table.tableSize.sizeClass) {
    TableSizeClass.Small -> EvidenceConfidence.High
    TableSizeClass.Medium, TableSizeClass.Large -> table.tableSize.confidence
    TableSizeClass.Unknown -> EvidenceConfidence.Unknown
}

internal fun buildAssessment(
    fingerprint: String,
    signals: List<RiskSignal>,
    uncertainties: List<RiskUncertainty>,
): QueryRiskAssessment {
    val caps = mapOf(
        RiskCategory.Access to 6,
        RiskCategory.Joins to 2,
        RiskCategory.Operations to 4,
        RiskCategory.Volume to 2,
    )
    val categoryScores = RiskCategory.entries.associateWith { category ->
        signals.filter { it.category == category }.sumOf(RiskSignal::points)
            .coerceAtMost(caps.getValue(category))
    }
    val dominant = categoryScores.values.maxOrNull() ?: 0
    val score = dominant + (categoryScores.values.sum() - dominant) / 2
    return QueryRiskAssessment(
        scoreVersion = QUERY_RISK_SCORE_VERSION,
        queryFingerprint = fingerprint,
        score = score,
        severity = severityForScore(score),
        categoryScores = categoryScores,
        signals = signals,
        uncertainties = uncertainties,
    )
}

fun severityForScore(score: Int): QueryRiskSeverity = when (score) {
    in 0..2 -> QueryRiskSeverity.Minimal
    in 3..5 -> QueryRiskSeverity.Elevated
    in 6..7 -> QueryRiskSeverity.High
    else -> QueryRiskSeverity.VeryHigh
}

fun applyRiskGate(
    assessment: QueryRiskAssessment?,
    userSetting: QueryRiskGate,
    validationBlocked: Boolean = false,
): QueryRiskDecision {
    val effective = userSetting
    val fingerprint = assessment?.queryFingerprint.orEmpty()
    if (validationBlocked) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.Blocked,
            effective,
            blockingBand(effective),
            listOf(RiskDecisionReason("validation_block", "Query validation blocks this query.", true)),
        )
    }
    if (effective == QueryRiskGate.Disabled) {
        return QueryRiskDecision(fingerprint, RiskGateState.Allowed, effective, null, emptyList())
    }
    if (assessment == null) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.AssessmentPending,
            effective,
            blockingBand(effective),
            listOf(RiskDecisionReason("assessment_pending", "Query risk assessment is pending.")),
        )
    }
    val mandatory = assessment.signals.filter(RiskSignal::mandatoryBlockWhenGateEnabled)
    val band = blockingBand(effective)!!
    val blocked = mandatory.isNotEmpty() || assessment.severity.ordinal >= band.ordinal
    val reasons = if (blocked) {
        assessment.signals
            .sortedWith(
                compareByDescending<RiskSignal> { it.mandatoryBlockWhenGateEnabled }
                    .thenByDescending { it.confidence.ordinal.let { ordinal -> -ordinal } }
                    .thenByDescending(RiskSignal::points),
            )
            .take(3)
            .map { signal ->
                RiskDecisionReason(
                    signal.code.name,
                    signalMessage(signal),
                    signal.mandatoryBlockWhenGateEnabled,
                )
            }
            .ifEmpty { listOf(RiskDecisionReason("severity_gate", "Query risk is ${assessment.severity.label}.")) }
    } else {
        emptyList()
    }
    return QueryRiskDecision(
        fingerprint,
        if (blocked) RiskGateState.Blocked else RiskGateState.Allowed,
        effective,
        band,
        reasons,
    )
}

fun blockingBand(gate: QueryRiskGate): QueryRiskSeverity? = when (gate) {
    QueryRiskGate.Cautious -> QueryRiskSeverity.Elevated
    QueryRiskGate.Standard -> QueryRiskSeverity.High
    QueryRiskGate.Flexible -> QueryRiskSeverity.VeryHigh
    QueryRiskGate.Disabled -> null
}

private enum class PredicateConstant { True, False, Unknown, Empty }

private data class PredicateAnalysis(
    val constant: PredicateConstant,
    val leaves: List<com.safedb.model.FilterSpec>,
    /** Disjunctive branches after constant folding; null means expansion exceeded the safety cap. */
    val branches: List<List<com.safedb.model.FilterSpec>>?,
)

private const val MAX_PREDICATE_BRANCHES = 256

private fun analyzePredicate(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
): PredicateAnalysis {
    val analyzed = group.children.mapNotNull { node ->
        when (node) {
            is FilterNode.Leaf -> {
                val constant = constantForLeaf(node)
                PredicateAnalysis(
                    constant = constant,
                    leaves = if (constant == PredicateConstant.Unknown) listOf(node.spec) else emptyList(),
                    branches = when (constant) {
                        PredicateConstant.True -> listOf(emptyList())
                        PredicateConstant.False -> emptyList()
                        PredicateConstant.Unknown -> listOf(listOf(node.spec))
                        PredicateConstant.Empty -> error("A filter leaf cannot be empty")
                    },
                )
            }
            is FilterNode.Group -> analyzePredicate(node.group, overrides).takeUnless { it.constant == PredicateConstant.Empty }
        }?.let { node to it }
    }
    if (analyzed.isEmpty()) return PredicateAnalysis(PredicateConstant.Empty, emptyList(), listOf(emptyList()))
    val disjuncts = mutableListOf<PredicateAnalysis>()
    var conjunction = analyzed.first().second
    for ((node, analysis) in analyzed.drop(1)) {
        val connector = overrides[nodeId(node)] ?: group.connector
        if (connector == GroupConnector.And) {
            conjunction = combineAnalysis(conjunction, analysis, GroupConnector.And)
        } else {
            disjuncts += conjunction
            conjunction = analysis
        }
    }
    disjuncts += conjunction
    return disjuncts.reduce { left, right -> combineAnalysis(left, right, GroupConnector.Or) }
}

private fun combineAnalysis(
    left: PredicateAnalysis,
    right: PredicateAnalysis,
    connector: GroupConnector,
): PredicateAnalysis = PredicateAnalysis(
    constant = combine(left.constant, right.constant, connector),
    leaves = combineLeaves(left.constant, left.leaves, right.constant, right.leaves, connector),
    branches = combineBranches(left, right, connector),
)

private fun combineBranches(
    left: PredicateAnalysis,
    right: PredicateAnalysis,
    connector: GroupConnector,
): List<List<com.safedb.model.FilterSpec>>? = when (connector) {
    GroupConnector.And -> when {
        left.constant == PredicateConstant.False || right.constant == PredicateConstant.False -> emptyList()
        left.constant == PredicateConstant.True -> right.branches
        right.constant == PredicateConstant.True -> left.branches
        left.branches == null || right.branches == null -> null
        left.branches.size.toLong() * right.branches.size > MAX_PREDICATE_BRANCHES -> null
        else -> left.branches.flatMap { leftBranch ->
            right.branches.map { rightBranch -> leftBranch + rightBranch }
        }
    }
    GroupConnector.Or -> when {
        left.constant == PredicateConstant.True || right.constant == PredicateConstant.True -> listOf(emptyList())
        left.constant == PredicateConstant.False -> right.branches
        right.constant == PredicateConstant.False -> left.branches
        left.branches == null || right.branches == null -> null
        left.branches.size + right.branches.size > MAX_PREDICATE_BRANCHES -> null
        else -> left.branches + right.branches
    }
}

private fun nodeId(node: FilterNode): String = when (node) {
    is FilterNode.Leaf -> node.spec.id
    is FilterNode.Group -> node.group.id
}

private fun constantForLeaf(node: FilterNode.Leaf): PredicateConstant {
    val values = (node.spec.value as? FilterValue.ListValue)?.literals
    return when {
        node.spec.op == FilterOp.In && values?.isEmpty() == true -> PredicateConstant.False
        node.spec.op == FilterOp.NotIn && values?.isEmpty() == true -> PredicateConstant.True
        else -> PredicateConstant.Unknown
    }
}

private fun combine(
    left: PredicateConstant,
    right: PredicateConstant,
    connector: GroupConnector,
): PredicateConstant = when (connector) {
    GroupConnector.And -> when {
        left == PredicateConstant.False || right == PredicateConstant.False -> PredicateConstant.False
        left == PredicateConstant.True -> right
        right == PredicateConstant.True -> left
        else -> PredicateConstant.Unknown
    }
    GroupConnector.Or -> when {
        left == PredicateConstant.True || right == PredicateConstant.True -> PredicateConstant.True
        left == PredicateConstant.False -> right
        right == PredicateConstant.False -> left
        else -> PredicateConstant.Unknown
    }
}

/** Keep only filter leaves that still affect execution after constant folding. */
private fun combineLeaves(
    leftConstant: PredicateConstant,
    leftLeaves: List<com.safedb.model.FilterSpec>,
    rightConstant: PredicateConstant,
    rightLeaves: List<com.safedb.model.FilterSpec>,
    connector: GroupConnector,
): List<com.safedb.model.FilterSpec> = when (connector) {
    GroupConnector.And -> when {
        leftConstant == PredicateConstant.False || rightConstant == PredicateConstant.False -> emptyList()
        leftConstant == PredicateConstant.True -> rightLeaves
        rightConstant == PredicateConstant.True -> leftLeaves
        else -> leftLeaves + rightLeaves
    }
    GroupConnector.Or -> when {
        leftConstant == PredicateConstant.True || rightConstant == PredicateConstant.True -> emptyList()
        leftConstant == PredicateConstant.False -> rightLeaves
        rightConstant == PredicateConstant.False -> leftLeaves
        else -> leftLeaves + rightLeaves
    }
}

private enum class AccessPathState { Compatible, Incompatible, Unknown }

private data class AccessPathAnalysis(
    val state: AccessPathState,
    val hasIncompatibleOrBranch: Boolean,
)

private fun analyzeAccessPaths(
    table: TableInfo,
    alias: String,
    predicate: PredicateAnalysis,
    dialect: Dialect,
): AccessPathAnalysis {
    val branches = predicate.branches
        ?: return AccessPathAnalysis(AccessPathState.Incompatible, hasIncompatibleOrBranch = true)
    val branchStates = branches.map { branch ->
        val leaves = branch.filter { it.tableAlias == alias }
        if (leaves.isEmpty()) AccessPathState.Incompatible else knownCompatiblePath(table, leaves, dialect)
    }
    val state = when {
        branchStates.any { it == AccessPathState.Incompatible } -> AccessPathState.Incompatible
        branchStates.any { it == AccessPathState.Unknown } -> AccessPathState.Unknown
        branchStates.isNotEmpty() -> AccessPathState.Compatible
        else -> AccessPathState.Incompatible
    }
    return AccessPathAnalysis(
        state = state,
        hasIncompatibleOrBranch = branches.size > 1 && branchStates.any { it == AccessPathState.Incompatible },
    )
}

private fun knownCompatiblePath(
    table: TableInfo,
    leaves: List<com.safedb.model.FilterSpec>,
    dialect: Dialect,
): AccessPathState {
    if (!table.indexMetadata.isComplete) return AccessPathState.Unknown
    var unknown = false
    for (index in table.indexes) {
        if (index.isPartial != false) {
            unknown = true
            continue
        }
        val keys = normalizedKeys(index)
        if (keys.isEmpty() || keys.first().column == null) {
            unknown = true
            continue
        }
        val equality = index.capabilities.equality
        if (equality == null) {
            unknown = true
            continue
        }
        var constrainedPrefix = 0
        for (key in keys) {
            val column = key.column ?: break
            val filter = leaves.firstOrNull { it.column == column } ?: break
            if (filter.op in equalityOps && equality && expressionCompatible(filter.op, index, dialect)) {
                constrainedPrefix++
                continue
            }
            if (filter.op in rangeOps && index.capabilities.ordering == true) return AccessPathState.Compatible
            break
        }
        if (constrainedPrefix > 0) return AccessPathState.Compatible
        if (leaves.any { leaf -> isScanProneText(leaf) && index.capabilities.specializedText == true }) {
            return AccessPathState.Compatible
        }
    }
    return if (unknown) AccessPathState.Unknown else AccessPathState.Incompatible
}

private fun expressionCompatible(op: FilterOp, index: IndexInfo, dialect: Dialect): Boolean =
    op != FilterOp.ContainsIgnoreCase ||
        dialect == Dialect.Postgres ||
        index.capabilities.expressionKeys == true

private fun normalizedKeys(index: IndexInfo): List<IndexKey> =
    index.keys.ifEmpty { index.columns.map(::IndexKey) }

private fun hasSpecializedTextPath(table: TableInfo, filter: com.safedb.model.FilterSpec): Boolean =
    table.indexMetadata.isComplete && table.indexes.any { index ->
        index.capabilities.specializedText == true && normalizedKeys(index).any { it.column == filter.column }
    }

private fun isScanProneText(filter: com.safedb.model.FilterSpec): Boolean = when (filter.op) {
    FilterOp.Contains, FilterOp.ContainsIgnoreCase, FilterOp.NotContains, FilterOp.EndsWith -> true
    FilterOp.Like, FilterOp.Ilike, FilterOp.NotLike -> {
        val pattern = ((filter.value as? FilterValue.Single)?.literal?.text).orEmpty()
        pattern.startsWith('%') || pattern.startsWith('_')
    }
    else -> false
}

private val equalityOps = setOf(FilterOp.Eq, FilterOp.In, FilterOp.IsNull, FilterOp.IsEmpty, FilterOp.StartsWith)
private val rangeOps = setOf(FilterOp.Gt, FilterOp.Gte, FilterOp.Lt, FilterOp.Lte, FilterOp.Between)
private val negativeOps = setOf(FilterOp.Ne, FilterOp.NotIn, FilterOp.IsNotNull, FilterOp.IsNotEmpty)

private fun addJoinSignals(
    spec: QuerySpec,
    tablesByAlias: Map<String, TableInfo?>,
    signals: MutableList<RiskSignal>,
    uncertainties: MutableList<RiskUncertainty>,
) {
    val relationships = spec.joins.groupBy { join -> setOf(join.leftAlias, join.rightAlias) }
    repeat(max(0, spec.tables.map { it.alias }.distinct().size - 1)) { index ->
        signals += RiskSignal(
            RiskSignalCode.AdditionalJoinedRelation,
            RiskCategory.Joins,
            RiskSubject(operation = "join ${index + 1}"),
            1,
            SignalBasis.StaticSchema,
            EvidenceConfidence.High,
        )
    }
    for ((aliases, joins) in relationships) {
        val firstAlias = aliases.firstOrNull() ?: continue
        val secondAlias = aliases.drop(1).firstOrNull() ?: continue
        val first = tablesByAlias[firstAlias] ?: continue
        val second = tablesByAlias[secondAlias] ?: continue
        val target = RiskTarget.Join(aliases)
        val fkMetadataComplete = first.foreignKeyMetadata.isComplete && second.foreignKeyMetadata.isComplete
        if (!fkMetadataComplete) {
            uncertainties += RiskUncertainty(
                "join_foreign_key_metadata_unknown",
                RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                first.foreignKeyMetadata.reasonCode ?: second.foreignKeyMetadata.reasonCode
                    ?: "foreign_key_metadata_unavailable",
            )
        }
        val fkMatch = matchingForeignKey(firstAlias, first, secondAlias, second, joins)
        if (fkMatch != null) {
            val referencingAlias = fkMatch.first
            val referencingTable = tablesByAlias[referencingAlias] ?: continue
            if (!referencingTable.indexMetadata.isComplete) {
                uncertainties += RiskUncertainty(
                    "join_index_metadata_unknown",
                    referencingTable.subject(referencingAlias),
                    referencingTable.indexMetadata.reasonCode ?: "index_metadata_unavailable",
                )
            } else {
                val supporting = referencingTable.indexes.any { index ->
                    index.capabilities.equality == true &&
                        index.isPartial == false &&
                        normalizedKeys(index).take(fkMatch.second.columns.size).map(IndexKey::column) == fkMatch.second.columns
                }
                if (!supporting) {
                    signals += RiskSignal(
                        RiskSignalCode.ForeignKeyWithoutSupportingIndex,
                        RiskCategory.Joins,
                        referencingTable.subject(referencingAlias),
                        2,
                        SignalBasis.StaticSchema,
                        EvidenceConfidence.High,
                    )
                }
            }
        }

        val sides = listOf(firstAlias to first, secondAlias to second)
        val uniquenessComplete = sides.all { (_, table) -> table.indexMetadata.isComplete }
        if (!uniquenessComplete) {
            uncertainties += RiskUncertainty(
                "join_uniqueness_metadata_unknown",
                RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                sides.firstOrNull { !it.second.indexMetadata.isComplete }?.second?.indexMetadata?.reasonCode
                    ?: "index_metadata_unavailable",
            )
        }
        val unique = sides.any { (alias, table) -> exactUniqueJoinKey(table, joinedColumns(alias, joins)) }
        if (!unique) {
            signals += RiskSignal(
                RiskSignalCode.JoinExpansionPossible,
                RiskCategory.Joins,
                RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                1,
                SignalBasis.StaticSchema,
                if (uniquenessComplete) EvidenceConfidence.High else EvidenceConfidence.Unknown,
                target = target,
            )
        }
    }
}

private fun joinedColumns(alias: String, joins: List<com.safedb.model.JoinSpec>): Set<String> = joins.mapNotNullTo(linkedSetOf()) { join ->
    when (alias) {
        join.leftAlias -> join.leftColumn
        join.rightAlias -> join.rightColumn
        else -> null
    }
}

private fun exactUniqueJoinKey(table: TableInfo, joinedColumns: Set<String>): Boolean {
    if (!table.indexMetadata.isComplete || joinedColumns.isEmpty()) return false
    return table.indexes.any { index ->
        val keys = normalizedKeys(index)
        index.isUnique && index.isPartial == false &&
            keys.isNotEmpty() && keys.none { it.expression || it.column == null } &&
            keys.mapNotNull(IndexKey::column).toSet() == joinedColumns && keys.size == joinedColumns.size
    }
}

private fun matchingForeignKey(
    firstAlias: String,
    first: TableInfo,
    secondAlias: String,
    second: TableInfo,
    joins: List<com.safedb.model.JoinSpec>,
): Pair<String, ForeignKeyInfo>? {
    fun matches(fromAlias: String, from: TableInfo, toAlias: String, to: TableInfo): ForeignKeyInfo? =
        from.foreignKeys.firstOrNull { fk ->
            fk.referencedSchema == to.schema && fk.referencedTable == to.name &&
                fk.columns.indices.all { index ->
                    joins.any { join ->
                        (join.leftAlias == fromAlias && join.leftColumn == fk.columns[index] &&
                            join.rightAlias == toAlias && join.rightColumn == fk.referencedColumns[index]) ||
                            (join.rightAlias == fromAlias && join.rightColumn == fk.columns[index] &&
                                join.leftAlias == toAlias && join.leftColumn == fk.referencedColumns[index])
                    }
                }
        }
    return matches(firstAlias, first, secondAlias, second)?.let { firstAlias to it }
        ?: matches(secondAlias, second, firstAlias, first)?.let { secondAlias to it }
}

private fun addOperationSignals(
    spec: QuerySpec,
    tablesByAlias: Map<String, TableInfo?>,
    leavesByAlias: Map<String, List<com.safedb.model.FilterSpec>>,
    boundedAliases: Set<String>,
    signals: MutableList<RiskSignal>,
    uncertainties: MutableList<RiskUncertainty>,
) {
    data class Operation(val kind: PlanOperationKind, val aliases: Set<String>, val compatible: Boolean = false)
    val operations = mutableListOf<Operation>()
    if (spec.groups.isNotEmpty()) {
        operations += Operation(PlanOperationKind.Grouping, spec.groups.mapTo(linkedSetOf()) { it.tableAlias })
    }
    if (spec.distinct && !distinctRedundant(spec)) {
        val aliases = spec.columns.mapTo(linkedSetOf()) { it.tableAlias }.ifEmpty {
            spec.tables.mapTo(linkedSetOf()) { it.alias }
        }
        operations += Operation(PlanOperationKind.Distinct, aliases)
    }
    if (spec.sorts.isNotEmpty()) {
        val sortAliases = spec.sorts.mapTo(linkedSetOf()) { it.tableAlias }
        val compatibility = if (sortAliases.size == 1) {
            val alias = sortAliases.single()
            val table = tablesByAlias[alias]
            if (table == null) {
                AccessPathState.Unknown
            } else {
                sortCompatibility(table, spec.sorts, leavesByAlias[alias].orEmpty())
            }
        } else {
            AccessPathState.Incompatible
        }
        when (compatibility) {
            AccessPathState.Compatible -> Unit
            AccessPathState.Incompatible -> operations += Operation(PlanOperationKind.Sort, sortAliases)
            AccessPathState.Unknown -> uncertainties += RiskUncertainty(
                "sort_compatibility_unknown",
                RiskSubject(operation = "ordered sort"),
                "index_direction_or_capability_unknown",
            )
        }
    }
    for (operation in operations) {
        val bounded = operation.aliases.all { alias ->
            alias in boundedAliases || tablesByAlias[alias]?.tableSize?.sizeClass == TableSizeClass.Small
        }
        signals += RiskSignal(
            if (bounded) RiskSignalCode.BoundedBlockingOperation else RiskSignalCode.LimitCannotBoundWork,
            RiskCategory.Operations,
            RiskSubject(operation = operation.kind.name.lowercase()),
            if (bounded) 1 else 3,
            SignalBasis.StaticSchema,
            EvidenceConfidence.Medium,
            target = RiskTarget.Operation(operation.kind, operation.aliases),
        )
    }
}

private fun distinctRedundant(spec: QuerySpec): Boolean {
    if (!spec.distinct || spec.groups.isEmpty()) return false
    val selected = spec.columns.map { it.tableAlias to it.column }.toSet()
    val grouped = spec.groups.map { it.tableAlias to it.column }.toSet()
    return selected.isNotEmpty() && selected == grouped
}

private fun sortCompatibility(
    table: TableInfo,
    sorts: List<com.safedb.model.SortSpec>,
    leaves: List<com.safedb.model.FilterSpec>,
): AccessPathState {
    if (!table.indexMetadata.isComplete) return AccessPathState.Unknown
    var unknown = false
    for (index in table.indexes) {
        if (index.capabilities.ordering != true || index.isPartial != false) {
            if (index.capabilities.ordering == null || index.isPartial == null) unknown = true
            continue
        }
        val keys = normalizedKeys(index)
        val sortPosition = keys.indexOfFirst { it.column == sorts.first().column }
        if (sortPosition < 0) continue
        val prefixConstrained = keys.take(sortPosition).all { key ->
            key.column != null && leaves.any { it.column == key.column && isPointConstraint(it) }
        }
        if (!prefixConstrained) continue
        val orderedKeys = keys.drop(sortPosition).take(sorts.size)
        if (orderedKeys.size != sorts.size || orderedKeys.map(IndexKey::column) != sorts.map { it.column }) continue
        val keyDirections = orderedKeys.map(IndexKey::direction)
        if (keyDirections.any { it == null }) {
            unknown = true
            continue
        }
        val requestedDirections = sorts.map { it.direction }
        val forward = keyDirections == requestedDirections
        val reverse = keyDirections.map { direction -> direction?.reverse() } == requestedDirections
        if (forward || reverse) return AccessPathState.Compatible
    }
    return if (unknown) AccessPathState.Unknown else AccessPathState.Incompatible
}

private fun isPointConstraint(filter: com.safedb.model.FilterSpec): Boolean = when (filter.op) {
    FilterOp.Eq, FilterOp.IsNull, FilterOp.IsEmpty -> true
    FilterOp.In -> (filter.value as? FilterValue.ListValue)?.literals?.size == 1
    else -> false
}

private fun SortDirection.reverse(): SortDirection = when (this) {
    SortDirection.Asc -> SortDirection.Desc
    SortDirection.Desc -> SortDirection.Asc
}

private fun addVolumeSignal(
    validated: ValidatedQuery,
    tablesByAlias: Map<String, TableInfo?>,
    signals: MutableList<RiskSignal>,
) {
    val width = validated.columns().sumOf { selection ->
        val category = tablesByAlias[selection.tableAlias]
            ?.columns
            ?.firstOrNull { it.name == selection.column }
            ?.category
        widthClassBytes(category)
    }
    val projectedBytes = width.toLong() * validated.spec().limit
    val (code, points) = when {
        projectedBytes >= 5L * 1024 * 1024 -> RiskSignalCode.HighProjectedPayload to 2
        projectedBytes >= 1024L * 1024 -> RiskSignalCode.MaterialProjectedPayload to 1
        else -> return
    }
    signals += RiskSignal(
        code,
        RiskCategory.Volume,
        RiskSubject(operation = "projected result payload"),
        points,
        SignalBasis.StaticSchema,
        EvidenceConfidence.Medium,
    )
}

private fun widthClassBytes(category: ColumnCategory?): Int = when (category) {
    ColumnCategory.Bool -> 1
    ColumnCategory.Integer, ColumnCategory.Decimal, ColumnCategory.Date, ColumnCategory.DateTime -> 16
    ColumnCategory.Text -> 256
    ColumnCategory.Json -> 512
    ColumnCategory.Binary -> 1024
    ColumnCategory.Other, null -> 256
}

private fun TableInfo.subject(alias: String, column: String? = null) = RiskSubject(
    tableAlias = alias,
    schema = schema,
    table = name,
    column = column,
)

private fun signalMessage(signal: RiskSignal): String = when (signal.code) {
    RiskSignalCode.NoEffectiveRestriction -> "No effective restriction applies to ${signal.subject.displayName()}."
    RiskSignalCode.NoKnownCompatibleAccessPath -> "No known compatible index applies to ${signal.subject.displayName()}."
    RiskSignalCode.ScanProneTextPredicate -> "Text search on ${signal.subject.displayName()} may scan many rows."
    RiskSignalCode.ScanProneNegativePredicate -> "Negative predicate on ${signal.subject.displayName()} may scan many rows."
    RiskSignalCode.OrBranchWithoutCompatiblePath -> "An OR branch has no known compatible access path."
    RiskSignalCode.AdditionalJoinedRelation -> "The query joins an additional relation."
    RiskSignalCode.ForeignKeyWithoutSupportingIndex -> "A foreign-key join has no known supporting index."
    RiskSignalCode.JoinExpansionPossible -> "No joined key is known to be unique, so results may expand."
    RiskSignalCode.LimitCannotBoundWork -> "Grouping, distinct rows, or sorting may require processing beyond the result limit."
    RiskSignalCode.BoundedBlockingOperation -> "A blocking operation remains above a bounded access path."
    RiskSignalCode.MaterialProjectedPayload -> "The projected result payload is material."
    RiskSignalCode.HighProjectedPayload -> "The projected result payload is high."
    RiskSignalCode.PlanConfirmedLargeScan -> "Plan evidence indicates a high-row scan of a large table."
    RiskSignalCode.PlanConfirmedJoinExpansion -> "Plan evidence indicates high join expansion."
}

enum class EstimatedRowBand { Low, Material, High }

fun estimatedRowBand(rows: Long): EstimatedRowBand = when {
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
    val tablesByAlias = spec.tables.associate { ref ->
        ref.alias to schema.tables.firstOrNull { it.schema == ref.schema && it.name == ref.name }
    }
    val replacements = mutableMapOf<RiskTarget, RiskSignal?>()
    val uncertainties = staticAssessment.uncertainties.toMutableList()

    fun replace(target: RiskTarget, signal: RiskSignal?) {
        val existing = replacements[target]
        if (target !in replacements || (signal != null && (existing == null || signal.points > existing.points))) {
            replacements[target] = signal
        }
    }

    for (step in plan.relations) {
        val alias = resolvePlanAlias(step.alias, step.schema, step.table, spec)
        if (alias == null) {
            uncertainties += RiskUncertainty(
                "plan_relation_unmapped",
                RiskSubject(schema = step.schema, table = step.table, tableAlias = step.alias),
                "ambiguous_or_unmapped_relation",
            )
            continue
        }
        val table = tablesByAlias[alias]
        val subject = table?.subject(alias) ?: RiskSubject(tableAlias = alias, schema = step.schema, table = step.table)
        if (step.specializedTextEvidence) {
            val textTarget = RiskTarget.Access(alias, AccessRiskKind.Text)
            replace(textTarget, RiskSignal(
                RiskSignalCode.ScanProneTextPredicate,
                RiskCategory.Access,
                subject,
                1,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                textTarget,
            ))
        }
        val target = RiskTarget.Access(alias)
        val band = step.estimatedRows?.let(::estimatedRowBand)
        val replacement = when (step.method) {
            PlanAccessMethod.BoundedLookup -> null
            PlanAccessMethod.BoundedRange -> when (band) {
                EstimatedRowBand.Low -> null
                EstimatedRowBand.Material, EstimatedRowBand.High -> RiskSignal(
                    RiskSignalCode.NoKnownCompatibleAccessPath, RiskCategory.Access, subject, 1,
                    SignalBasis.PlanEvidence, EvidenceConfidence.High, target,
                )
                null -> {
                    uncertainties += RiskUncertainty("plan_access_rows_unknown", subject, "missing_estimated_rows")
                    continue
                }
            }
            PlanAccessMethod.TableScan -> when (band) {
                EstimatedRowBand.Low -> null
                EstimatedRowBand.Material, EstimatedRowBand.High -> RiskSignal(
                    RiskSignalCode.PlanConfirmedLargeScan,
                    RiskCategory.Access,
                    subject,
                    4,
                    SignalBasis.PlanEvidence,
                    EvidenceConfidence.High,
                    target,
                    mandatoryBlockWhenGateEnabled = band == EstimatedRowBand.High && table.isConfidentLarge(),
                )
                null -> {
                    uncertainties += RiskUncertainty("plan_access_rows_unknown", subject, "missing_estimated_rows")
                    continue
                }
            }
            PlanAccessMethod.FullIndexScan -> continue
            PlanAccessMethod.Unknown, PlanAccessMethod.Other -> {
                uncertainties += RiskUncertainty(
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
            uncertainties += RiskUncertainty(
                "plan_operation_unmapped",
                RiskSubject(operation = step.kind.name.lowercase()),
                "ambiguous_or_unmapped_operation_alias",
            )
            continue
        }
        val target = matchingOperationTarget(step.kind, aliases, staticAssessment.signals)
        if (target == null) {
            uncertainties += RiskUncertainty(
                "plan_operation_unmapped",
                RiskSubject(operation = step.kind.name.lowercase()),
                "ambiguous_or_unmapped_operation",
            )
            continue
        }
        val band = step.estimatedRows?.let(::estimatedRowBand)
        if (band == null) {
            uncertainties += RiskUncertainty("plan_operation_rows_unknown", RiskSubject(operation = step.kind.name.lowercase()), "missing_estimated_rows")
            continue
        }
        replace(target, RiskSignal(
            if (band == EstimatedRowBand.Low) RiskSignalCode.BoundedBlockingOperation else RiskSignalCode.LimitCannotBoundWork,
            RiskCategory.Operations,
            RiskSubject(operation = step.kind.name.lowercase()),
            if (band == EstimatedRowBand.Low) 1 else 3,
            SignalBasis.PlanEvidence,
            EvidenceConfidence.High,
            target,
        ))
    }

    for (step in plan.joins) {
        val aliases = resolvePlanAliases(step.aliases, spec)
        if (step.aliases.isEmpty() || aliases.size != step.aliases.size) {
            uncertainties += RiskUncertainty("plan_join_unmapped", RiskSubject(operation = "join"), "ambiguous_or_unmapped_join_alias")
            continue
        }
        val target = matchingJoinTarget(aliases, staticAssessment.signals)
        if (target == null) {
            uncertainties += RiskUncertainty("plan_join_unmapped", RiskSubject(operation = "join"), "ambiguous_or_unmapped_join")
            continue
        }
        when (step.estimatedOutputRows?.let(::estimatedRowBand)) {
            EstimatedRowBand.Low -> replace(target, null)
            EstimatedRowBand.Material -> Unit
            EstimatedRowBand.High -> replace(target, RiskSignal(
                RiskSignalCode.PlanConfirmedJoinExpansion,
                RiskCategory.Joins,
                RiskSubject(operation = "join ${target.aliases.sorted().joinToString("-")}"),
                3,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                target,
                mandatoryBlockWhenGateEnabled = joinUniquenessProvesNeitherSideUnique(target.aliases, spec, tablesByAlias),
            ))
            null -> uncertainties += RiskUncertainty("plan_join_rows_unknown", RiskSubject(operation = "join"), "missing_estimated_rows")
        }
    }

    val retained = staticAssessment.signals.filterNot { it.target in replacements }
    val active = retained + replacements.values.filterNotNull()
    return buildAssessment(staticAssessment.queryFingerprint, active, uncertainties)
}

fun preserveStaticRiskForUnavailablePlan(
    staticAssessment: QueryRiskAssessment,
    reason: PlanUnavailableReason,
): QueryRiskAssessment = staticAssessment.copy(
    uncertainties = staticAssessment.uncertainties + RiskUncertainty(
        "plan_unavailable",
        RiskSubject(operation = "query plan"),
        reason.name,
    ),
)

private fun resolvePlanAlias(alias: String?, schema: String?, table: String?, spec: QuerySpec): String? {
    alias?.let { explicit -> spec.tables.singleOrNull { it.alias.equals(explicit, ignoreCase = true) }?.let { return it.alias } }
    if (table == null) return null
    val matches = spec.tables.filter { ref ->
        ref.name.equals(table, ignoreCase = true) && (schema == null || ref.schema.equals(schema, ignoreCase = true))
    }
    return matches.singleOrNull()?.alias
}

private fun resolvePlanAliases(aliases: Set<String>, spec: QuerySpec): Set<String> = aliases.mapNotNullTo(linkedSetOf()) { value ->
    resolvePlanAlias(value, null, value, spec)
}

private fun matchingOperationTarget(
    kind: PlanOperationKind,
    aliases: Set<String>,
    signals: List<RiskSignal>,
): RiskTarget.Operation? {
    val candidates = signals.mapNotNull { it.target as? RiskTarget.Operation }.filter { it.kind == kind }.distinct()
    return candidates.singleOrNull { it.aliases == aliases }
}

private fun matchingJoinTarget(aliases: Set<String>, signals: List<RiskSignal>): RiskTarget.Join? {
    val candidates = signals.mapNotNull { it.target as? RiskTarget.Join }.distinct()
    return candidates.singleOrNull { it.aliases == aliases }
}

private fun TableInfo?.isConfidentLarge(): Boolean = this != null &&
    tableSize.sizeClass == TableSizeClass.Large && tableSize.coverage.isComplete &&
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
