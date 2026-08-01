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
import com.safedb.model.SafeDbJson
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import java.security.MessageDigest
import kotlin.math.max

const val QUERY_RISK_SCORE_VERSION: Int = 1

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

enum class SignalBasis { StaticSchema, PlanEvidence, OrganizationPolicy }

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
    val replacementKey: String? = null,
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

enum class RiskGateState { Allowed, AssessmentPending, Blocked }

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

data class OrganizationRiskPolicy(
    /** The most flexible personal choice the organization permits. */
    val maximumFlexibility: QueryRiskGate = QueryRiskGate.Disabled,
    val minimumGateByResource: Map<String, QueryRiskGate> = emptyMap(),
    val blockedResources: Set<String> = emptySet(),
    val requirePlanForResources: Set<String> = emptySet(),
)

data class QueryRiskEvaluation(
    val assessment: QueryRiskAssessment?,
    val decision: QueryRiskDecision,
)

fun evaluateQueryRisk(
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    dialect: Dialect,
    organizationPolicy: OrganizationRiskPolicy = OrganizationRiskPolicy(),
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
    val resources = validated.spec().tables.mapTo(mutableSetOf()) { "${it.schema}.${it.name}" }
    return Outcome.ok(
        QueryRiskEvaluation(
            assessment,
            applyRiskGate(assessment, settings.queryRiskGate, organizationPolicy, resources),
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
    val leavesByAlias = predicate.leaves.groupBy { it.tableAlias }
    val boundedAliases = mutableSetOf<String>()

    for ((alias, table) in tablesByAlias) {
        if (table == null) continue
        val subject = table.subject(alias)
        val leaves = leavesByAlias[alias].orEmpty()
        if (table.tableSize.sizeClass != TableSizeClass.Small &&
            (predicate.constant == PredicateConstant.True || predicate.constant == PredicateConstant.Empty)
        ) {
            signals += RiskSignal(
                RiskSignalCode.NoEffectiveRestriction,
                RiskCategory.Access,
                subject,
                points = 2,
                basis = SignalBasis.StaticSchema,
                confidence = EvidenceConfidence.High,
                replacementKey = "access:$alias",
            )
        }

        if (leaves.isEmpty()) continue
        val path = analyzeAccessPaths(table, alias, predicate, dialect)
        if (path.state == AccessPathState.Compatible) boundedAliases += alias
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
                EvidenceConfidence.Medium,
                replacementKey = "access:$alias",
            )
            if (path.hasIncompatibleOrBranch) {
                signals += RiskSignal(
                    RiskSignalCode.OrBranchWithoutCompatiblePath,
                    RiskCategory.Access,
                    subject,
                    1,
                    SignalBasis.StaticSchema,
                    EvidenceConfidence.Medium,
                    replacementKey = "or:$alias",
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
                    EvidenceConfidence.High,
                    replacementKey = "text:$alias:${broad.column}",
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
                    EvidenceConfidence.Medium,
                    replacementKey = "negative:$alias:${negative.column}",
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
    addOperationSignals(spec, tablesByAlias, leavesByAlias, boundedAliases, signals, uncertainties)
    addVolumeSignal(validated, tablesByAlias, signals)
    return buildAssessment(queryFingerprint(validated), signals, uncertainties)
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
    organizationPolicy: OrganizationRiskPolicy = OrganizationRiskPolicy(),
    resources: Set<String> = emptySet(),
    validationBlocked: Boolean = false,
    policyBlocked: Boolean = resources.any(organizationPolicy.blockedResources::contains),
): QueryRiskDecision {
    val effective = effectiveGate(userSetting, organizationPolicy, resources)
    val fingerprint = assessment?.queryFingerprint.orEmpty()
    if (validationBlocked || policyBlocked) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.Blocked,
            effective,
            blockingBand(effective),
            listOf(RiskDecisionReason("policy_or_validation_block", "A validation or organization policy blocks this query.", true)),
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

fun effectiveGate(
    userSetting: QueryRiskGate,
    policy: OrganizationRiskPolicy,
    resources: Set<String>,
): QueryRiskGate {
    var result = stricterGate(userSetting, policy.maximumFlexibility)
    for (resource in resources) {
        policy.minimumGateByResource[resource]?.let { result = stricterGate(result, it) }
    }
    return result
}

private fun stricterGate(first: QueryRiskGate, second: QueryRiskGate): QueryRiskGate =
    if (first.ordinal <= second.ordinal) first else second

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
    repeat(max(0, relationships.size)) { index ->
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
        val fkMatch = matchingForeignKey(firstAlias, first, secondAlias, second, joins) ?: continue
        val referencingAlias = fkMatch.first
        val referencingTable = tablesByAlias[referencingAlias] ?: continue
        if (!referencingTable.indexMetadata.isComplete) {
            uncertainties += RiskUncertainty(
                "join_index_metadata_unknown",
                referencingTable.subject(referencingAlias),
                referencingTable.indexMetadata.reasonCode ?: "index_metadata_unavailable",
            )
            continue
        }
        val supporting = referencingTable.indexes.any { index ->
            index.capabilities.equality == true &&
                index.isPartial == false &&
                normalizedKeys(index).mapNotNull(IndexKey::column).take(fkMatch.second.columns.size) == fkMatch.second.columns
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
        val unique = listOf(firstAlias to first, secondAlias to second).any { (alias, table) ->
            val joinedColumns = joins.mapNotNull { join ->
                when (alias) {
                    join.leftAlias -> join.leftColumn
                    join.rightAlias -> join.rightColumn
                    else -> null
                }
            }
            table.indexes.any { it.isUnique && normalizedKeys(it).mapNotNull(IndexKey::column).take(joinedColumns.size) == joinedColumns }
        }
        if (!unique) {
            signals += RiskSignal(
                RiskSignalCode.JoinExpansionPossible,
                RiskCategory.Joins,
                RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                1,
                SignalBasis.StaticSchema,
                EvidenceConfidence.Medium,
                replacementKey = "join:${aliases.sorted().joinToString(":")}",
            )
        }
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
    var operations = spec.groups.size + if (spec.distinct && !distinctRedundant(spec)) 1 else 0
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
            AccessPathState.Incompatible -> operations++
            AccessPathState.Unknown -> uncertainties += RiskUncertainty(
                "sort_compatibility_unknown",
                RiskSubject(operation = "ordered sort"),
                "index_direction_or_capability_unknown",
            )
        }
    }
    if (operations == 0) return
    if (boundedAliases.isEmpty()) {
        signals += RiskSignal(
            RiskSignalCode.LimitCannotBoundWork,
            RiskCategory.Operations,
            RiskSubject(operation = "blocking operation"),
            3,
            SignalBasis.StaticSchema,
            EvidenceConfidence.Medium,
            replacementKey = "operation:blocking",
        )
    } else {
        repeat(operations) { index ->
            signals += RiskSignal(
                RiskSignalCode.BoundedBlockingOperation,
                RiskCategory.Operations,
                RiskSubject(operation = "blocking operation ${index + 1}"),
                1,
                SignalBasis.StaticSchema,
                EvidenceConfidence.Medium,
                replacementKey = "operation:bounded:$index",
            )
        }
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
            key.column != null && leaves.any { it.column == key.column && it.op in equalityOps }
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

enum class PlanAccessMethod { BoundedLookup, BoundedRange, FullIndexScan, TableScan, Unknown, Other }
enum class EstimatedRowBand { Low, Material, High }

data class PlanAccessStep(
    val replacementKey: String,
    val subject: RiskSubject,
    val method: PlanAccessMethod,
    val estimatedRows: EstimatedRowBand? = null,
    val confidentLargeCatalog: Boolean = false,
    val specializedTextIndex: Boolean = false,
)

data class PlanJoinStep(
    val replacementKey: String,
    val subject: RiskSubject,
    val estimatedRows: EstimatedRowBand? = null,
    val highConfidence: Boolean = false,
)

data class PlanOperationStep(
    val replacementKey: String,
    val subject: RiskSubject,
    val blockingOperationPresent: Boolean,
    val estimatedRows: EstimatedRowBand? = null,
)

data class PlanEvidence(
    val accessSteps: List<PlanAccessStep> = emptyList(),
    val joins: List<PlanJoinStep> = emptyList(),
    val operations: List<PlanOperationStep> = emptyList(),
    val unavailableReasonCode: String? = null,
)

fun refineRiskWithPlan(
    staticAssessment: QueryRiskAssessment,
    planEvidence: PlanEvidence,
): QueryRiskAssessment {
    if (planEvidence.unavailableReasonCode != null) {
        return staticAssessment.copy(
            uncertainties = staticAssessment.uncertainties + RiskUncertainty(
                "plan_unavailable",
                RiskSubject(operation = "query plan"),
                planEvidence.unavailableReasonCode,
            ),
        )
    }
    val replacements = mutableMapOf<String, RiskSignal?>()
    val uncertainties = staticAssessment.uncertainties.toMutableList()
    for (step in planEvidence.accessSteps) {
        if (step.specializedTextIndex) {
            replacements[step.replacementKey] = RiskSignal(
                RiskSignalCode.ScanProneTextPredicate,
                RiskCategory.Access,
                step.subject,
                1,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                step.replacementKey,
            )
            continue
        }
        val replacement = when (step.method) {
            PlanAccessMethod.BoundedLookup -> null
            PlanAccessMethod.BoundedRange -> if (step.estimatedRows == EstimatedRowBand.Low) null else RiskSignal(
                RiskSignalCode.NoKnownCompatibleAccessPath, RiskCategory.Access, step.subject, 1,
                SignalBasis.PlanEvidence, EvidenceConfidence.Medium, step.replacementKey,
            )
            PlanAccessMethod.TableScan -> RiskSignal(
                RiskSignalCode.PlanConfirmedLargeScan,
                RiskCategory.Access,
                step.subject,
                4,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                step.replacementKey,
                mandatoryBlockWhenGateEnabled = step.estimatedRows == EstimatedRowBand.High && step.confidentLargeCatalog,
            )
            PlanAccessMethod.FullIndexScan -> continue
            PlanAccessMethod.Unknown, PlanAccessMethod.Other -> {
                uncertainties += RiskUncertainty(
                    "plan_access_method_unknown",
                    step.subject,
                    step.method.name.lowercase(),
                )
                continue
            }
        }
        replacements[step.replacementKey] = replacement
    }
    for (step in planEvidence.operations) {
        replacements[step.replacementKey] = when {
            !step.blockingOperationPresent -> null
            step.estimatedRows == EstimatedRowBand.Low -> RiskSignal(
                RiskSignalCode.BoundedBlockingOperation, RiskCategory.Operations, step.subject, 1,
                SignalBasis.PlanEvidence, EvidenceConfidence.High, step.replacementKey,
            )
            else -> RiskSignal(
                RiskSignalCode.LimitCannotBoundWork, RiskCategory.Operations, step.subject, 3,
                SignalBasis.PlanEvidence, EvidenceConfidence.High, step.replacementKey,
            )
        }
    }
    for (step in planEvidence.joins) {
        if (step.estimatedRows == EstimatedRowBand.High && step.highConfidence) {
            replacements[step.replacementKey] = RiskSignal(
                RiskSignalCode.PlanConfirmedJoinExpansion,
                RiskCategory.Joins,
                step.subject,
                3,
                SignalBasis.PlanEvidence,
                EvidenceConfidence.High,
                step.replacementKey,
                mandatoryBlockWhenGateEnabled = true,
            )
        }
    }
    val retained = staticAssessment.signals.filterNot { it.replacementKey in replacements }
    val active = retained + replacements.values.filterNotNull()
    return buildAssessment(staticAssessment.queryFingerprint, active, uncertainties)
}
