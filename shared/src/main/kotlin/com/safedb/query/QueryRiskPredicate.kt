package com.safedb.query

import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.TableInfo

internal enum class PredicateConstant {
    True,
    False,
    Unknown,
    Empty,
}

internal data class PredicateAnalysis(
    val constant: PredicateConstant,
    val leaves: List<com.safedb.model.FilterSpec>,
    // Null means disjunctive expansion exceeded the safety cap.
    val branches: List<List<com.safedb.model.FilterSpec>>?,
)

internal const val MAX_PREDICATE_BRANCHES = 256

internal fun analyzePredicate(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
): PredicateAnalysis {
    val analyzed =
        group.children.mapNotNull { node ->
            when (node) {
                is FilterNode.Leaf -> {
                    val constant = constantForLeaf(node)
                    PredicateAnalysis(
                        constant = constant,
                        leaves =
                            if (constant == PredicateConstant.Unknown) listOf(node.spec)
                            else emptyList(),
                        branches =
                            when (constant) {
                                PredicateConstant.True -> listOf(emptyList())
                                PredicateConstant.False -> emptyList()
                                PredicateConstant.Unknown -> listOf(listOf(node.spec))
                                PredicateConstant.Empty -> error("A filter leaf cannot be empty")
                            },
                    )
                }
                is FilterNode.Group ->
                    analyzePredicate(node.group, overrides).takeUnless {
                        it.constant == PredicateConstant.Empty
                    }
            }?.let { node to it }
        }
    if (analyzed.isEmpty())
        return PredicateAnalysis(PredicateConstant.Empty, emptyList(), listOf(emptyList()))
    val disjuncts = mutableListOf<PredicateAnalysis>()
    var conjunction = analyzed.first().second
    for ((node, analysis) in analyzed.drop(1)) {
        val connector = overrides[filterNodeId(node)] ?: group.connector
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

internal fun combineAnalysis(
    left: PredicateAnalysis,
    right: PredicateAnalysis,
    connector: GroupConnector,
): PredicateAnalysis =
    PredicateAnalysis(
        constant = combine(left.constant, right.constant, connector),
        leaves = combineLeaves(left.constant, left.leaves, right.constant, right.leaves, connector),
        branches = combineBranches(left, right, connector),
    )

internal fun combineBranches(
    left: PredicateAnalysis,
    right: PredicateAnalysis,
    connector: GroupConnector,
): List<List<com.safedb.model.FilterSpec>>? =
    when (connector) {
        GroupConnector.And ->
            when {
                left.constant == PredicateConstant.False ||
                    right.constant == PredicateConstant.False -> emptyList()
                left.constant == PredicateConstant.True -> right.branches
                right.constant == PredicateConstant.True -> left.branches
                left.branches == null || right.branches == null -> null
                left.branches.size.toLong() * right.branches.size > MAX_PREDICATE_BRANCHES -> null
                else ->
                    left.branches.flatMap { leftBranch ->
                        right.branches.map { rightBranch -> leftBranch + rightBranch }
                    }
            }
        GroupConnector.Or ->
            when {
                left.constant == PredicateConstant.True ||
                    right.constant == PredicateConstant.True -> listOf(emptyList())
                left.constant == PredicateConstant.False -> right.branches
                right.constant == PredicateConstant.False -> left.branches
                left.branches == null || right.branches == null -> null
                left.branches.size + right.branches.size > MAX_PREDICATE_BRANCHES -> null
                else -> left.branches + right.branches
            }
    }

internal fun constantForLeaf(node: FilterNode.Leaf): PredicateConstant {
    val values = (node.spec.value as? FilterValue.ListValue)?.literals
    return when (node.spec.op) {
        FilterOp.In if values?.isEmpty() == true -> PredicateConstant.False
        FilterOp.NotIn if values?.isEmpty() == true -> PredicateConstant.True
        else -> PredicateConstant.Unknown
    }
}

internal fun combine(
    left: PredicateConstant,
    right: PredicateConstant,
    connector: GroupConnector,
): PredicateConstant =
    when (connector) {
        GroupConnector.And ->
            when {
                left == PredicateConstant.False || right == PredicateConstant.False ->
                    PredicateConstant.False
                left == PredicateConstant.True -> right
                right == PredicateConstant.True -> left
                else -> PredicateConstant.Unknown
            }
        GroupConnector.Or ->
            when {
                left == PredicateConstant.True || right == PredicateConstant.True ->
                    PredicateConstant.True
                left == PredicateConstant.False -> right
                right == PredicateConstant.False -> left
                else -> PredicateConstant.Unknown
            }
    }

internal fun combineLeaves(
    leftConstant: PredicateConstant,
    leftLeaves: List<com.safedb.model.FilterSpec>,
    rightConstant: PredicateConstant,
    rightLeaves: List<com.safedb.model.FilterSpec>,
    connector: GroupConnector,
): List<com.safedb.model.FilterSpec> =
    when (connector) {
        GroupConnector.And ->
            when {
                leftConstant == PredicateConstant.False ||
                    rightConstant == PredicateConstant.False -> emptyList()
                leftConstant == PredicateConstant.True -> rightLeaves
                rightConstant == PredicateConstant.True -> leftLeaves
                else -> leftLeaves + rightLeaves
            }
        GroupConnector.Or ->
            when {
                leftConstant == PredicateConstant.True || rightConstant == PredicateConstant.True ->
                    emptyList()
                leftConstant == PredicateConstant.False -> rightLeaves
                rightConstant == PredicateConstant.False -> leftLeaves
                else -> leftLeaves + rightLeaves
            }
    }

internal enum class AccessPathState {
    Compatible,
    Incompatible,
    Unknown,
}

internal data class AccessPathAnalysis(
    val state: AccessPathState,
    val hasIncompatibleOrBranch: Boolean,
)

internal fun analyzeAccessPaths(
    table: TableInfo,
    alias: String,
    predicate: PredicateAnalysis,
): AccessPathAnalysis {
    val branches =
        predicate.branches
            ?: return AccessPathAnalysis(
                AccessPathState.Incompatible,
                hasIncompatibleOrBranch = true,
            )
    val branchStates = branches.map { branch ->
        val leaves = branch.filter { it.tableAlias == alias }
        if (leaves.isEmpty()) AccessPathState.Incompatible else knownCompatiblePath(table, leaves)
    }
    val state =
        when {
            branchStates.any { it == AccessPathState.Incompatible } -> AccessPathState.Incompatible
            branchStates.any { it == AccessPathState.Unknown } -> AccessPathState.Unknown
            branchStates.isNotEmpty() -> AccessPathState.Compatible
            else -> AccessPathState.Incompatible
        }
    return AccessPathAnalysis(
        state = state,
        hasIncompatibleOrBranch =
            branches.size > 1 && branchStates.any { it == AccessPathState.Incompatible },
    )
}

internal fun knownCompatiblePath(
    table: TableInfo,
    leaves: List<com.safedb.model.FilterSpec>,
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
        for ((column, _, _) in keys) {
            if (column == null) break
            val filter = leaves.firstOrNull { it.column == column } ?: break
            if (filter.op in equalityOps && equality) {
                constrainedPrefix++
                continue
            }
            // StartsWith compiles to a bare prefix LIKE, so it needs an ordered path like a range.
            if (
                (filter.op in rangeOps || filter.op == FilterOp.StartsWith) &&
                    index.capabilities.ordering == true
            )
                return AccessPathState.Compatible
            break
        }
        if (constrainedPrefix > 0) return AccessPathState.Compatible
        if (
            leaves.any { leaf ->
                isScanProneText(leaf) && index.capabilities.specializedText == true
            }
        ) {
            return AccessPathState.Compatible
        }
    }
    return if (unknown) AccessPathState.Unknown else AccessPathState.Incompatible
}

internal fun normalizedKeys(index: IndexInfo): List<IndexKey> =
    index.keys.ifEmpty { index.columns.map(::IndexKey) }

internal fun hasSpecializedTextPath(
    table: TableInfo,
    filter: com.safedb.model.FilterSpec,
): Boolean =
    table.indexMetadata.isComplete &&
        table.indexes.any { index ->
            index.capabilities.specializedText == true &&
                normalizedKeys(index).any { it.column == filter.column }
        }

internal fun isScanProneText(filter: com.safedb.model.FilterSpec): Boolean =
    when (filter.op) {
        FilterOp.Contains,
        FilterOp.ContainsIgnoreCase,
        FilterOp.NotContains,
        FilterOp.EndsWith -> true
        FilterOp.Like,
        FilterOp.Ilike,
        FilterOp.NotLike -> {
            val pattern = ((filter.value as? FilterValue.Single)?.literal?.text).orEmpty()
            pattern.startsWith('%') || pattern.startsWith('_')
        }
        else -> false
    }

internal val equalityOps = setOf(FilterOp.Eq, FilterOp.In, FilterOp.IsNull, FilterOp.IsEmpty)
internal val rangeOps =
    setOf(FilterOp.Gt, FilterOp.Gte, FilterOp.Lt, FilterOp.Lte, FilterOp.Between)
internal val negativeOps =
    setOf(FilterOp.Ne, FilterOp.NotIn, FilterOp.IsNotNull, FilterOp.IsNotEmpty)
