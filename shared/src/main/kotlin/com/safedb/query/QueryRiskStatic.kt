package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterOp
import com.safedb.model.FilterValue
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexKey
import com.safedb.model.PlanOperationKind
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import kotlin.math.max

fun assessStaticQueryRisk(
    validated: ValidatedQuery,
    schema: Schema,
    dialect: Dialect,
): QueryRiskAssessment {
    val spec = validated.spec()
    val tablesByAlias =
        spec.tables.associate { ref ->
            ref.alias to
                schema.tables.firstOrNull { it.schema == ref.schema && it.name == ref.name }
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
            uncertainties +=
                RiskUncertainty(
                    "table_size_unknown",
                    subject,
                    table.tableSize.coverage.reasonCode ?: "table_size_metadata_unavailable",
                )
        }
        val directlyRestricted =
            predicate.branches?.let { branches ->
                branches.isNotEmpty() &&
                    branches.all { branch -> branch.any { it.tableAlias == alias } }
            } == true
        if (table.tableSize.sizeClass != TableSizeClass.Small && !directlyRestricted) {
            signals +=
                RiskSignal(
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
        if (directlyRestricted && path.state == AccessPathState.Compatible)
            directlyBoundedAliases += alias
        if (path.state == AccessPathState.Unknown) {
            uncertainties +=
                RiskUncertainty(
                    code = "index_compatibility_unknown",
                    subject = subject,
                    reasonCode =
                        table.indexMetadata.reasonCode ?: "advanced_index_capability_unknown",
                )
        } else if (
            path.state == AccessPathState.Incompatible &&
                table.tableSize.sizeClass != TableSizeClass.Small
        ) {
            signals +=
                RiskSignal(
                    RiskSignalCode.NoKnownCompatibleAccessPath,
                    RiskCategory.Access,
                    subject,
                    2,
                    SignalBasis.StaticSchema,
                    tableRiskConfidence(table),
                    target = RiskTarget.Access(alias),
                )
            if (path.hasIncompatibleOrBranch) {
                signals +=
                    RiskSignal(
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
                signals +=
                    RiskSignal(
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
                signals +=
                    RiskSignal(
                        RiskSignalCode.ScanProneNegativePredicate,
                        RiskCategory.Access,
                        table.subject(alias, negative.column),
                        1,
                        SignalBasis.StaticSchema,
                        tableRiskConfidence(table),
                        target = RiskTarget.Access(alias),
                    )
            } else {
                uncertainties +=
                    RiskUncertainty(
                        "negative_predicate_capability_unknown",
                        table.subject(alias, negative.column),
                        table.indexMetadata.reasonCode ?: "index_metadata_unavailable",
                    )
            }
        }
    }

    addJoinSignals(spec, tablesByAlias, signals, uncertainties)
    addOperationSignals(
        spec,
        tablesByAlias,
        leavesByAlias,
        directlyBoundedAliases,
        signals,
        uncertainties,
    )
    addVolumeSignal(validated, tablesByAlias, signals)
    return buildAssessment(queryFingerprint(validated), signals, uncertainties)
}

private fun tableRiskConfidence(table: TableInfo): EvidenceConfidence =
    when (table.tableSize.sizeClass) {
        TableSizeClass.Small -> EvidenceConfidence.High
        TableSizeClass.Medium,
        TableSizeClass.Large -> table.tableSize.confidence
        TableSizeClass.Unknown -> EvidenceConfidence.Unknown
    }

internal fun buildAssessment(
    fingerprint: String,
    signals: List<RiskSignal>,
    uncertainties: List<RiskUncertainty>,
): QueryRiskAssessment {
    val caps =
        mapOf(
            RiskCategory.Access to 6,
            RiskCategory.Joins to 2,
            RiskCategory.Operations to 4,
            RiskCategory.Volume to 2,
        )
    val categoryScores =
        RiskCategory.entries.associateWith { category ->
            signals
                .filter { it.category == category }
                .sumOf(RiskSignal::points)
                .coerceAtMost(caps.getValue(category))
        }
    val dominant = categoryScores.values.maxOrNull() ?: 0
    // Count the dominant category fully and the remaining categories at half weight.
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

fun severityForScore(score: Int): QueryRiskSeverity =
    when (score) {
        in 0..2 -> QueryRiskSeverity.Minimal
        in 3..5 -> QueryRiskSeverity.Elevated
        in 6..7 -> QueryRiskSeverity.High
        else -> QueryRiskSeverity.VeryHigh
    }

private fun addJoinSignals(
    spec: QuerySpec,
    tablesByAlias: Map<String, TableInfo?>,
    signals: MutableList<RiskSignal>,
    uncertainties: MutableList<RiskUncertainty>,
) {
    val relationships = spec.joins.groupBy { join -> setOf(join.leftAlias, join.rightAlias) }
    repeat(max(0, spec.tables.map { it.alias }.distinct().size - 1)) { index ->
        signals +=
            RiskSignal(
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
        val fkMetadataComplete =
            first.foreignKeyMetadata.isComplete && second.foreignKeyMetadata.isComplete
        if (!fkMetadataComplete) {
            uncertainties +=
                RiskUncertainty(
                    "join_foreign_key_metadata_unknown",
                    RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                    first.foreignKeyMetadata.reasonCode
                        ?: second.foreignKeyMetadata.reasonCode
                        ?: "foreign_key_metadata_unavailable",
                )
        }
        val fkMatch = matchingForeignKey(firstAlias, first, secondAlias, second, joins)
        if (fkMatch != null) {
            val referencingAlias = fkMatch.first
            val referencingTable = tablesByAlias[referencingAlias] ?: continue
            if (!referencingTable.indexMetadata.isComplete) {
                uncertainties +=
                    RiskUncertainty(
                        "join_index_metadata_unknown",
                        referencingTable.subject(referencingAlias),
                        referencingTable.indexMetadata.reasonCode ?: "index_metadata_unavailable",
                    )
            } else {
                val supporting =
                    referencingTable.indexes.any { index ->
                        index.capabilities.equality == true &&
                            index.isPartial == false &&
                            normalizedKeys(index)
                                .take(fkMatch.second.columns.size)
                                .map(IndexKey::column) == fkMatch.second.columns
                    }
                if (!supporting) {
                    signals +=
                        RiskSignal(
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
            uncertainties +=
                RiskUncertainty(
                    "join_uniqueness_metadata_unknown",
                    RiskSubject(operation = "join ${aliases.sorted().joinToString("-")}"),
                    sides
                        .firstOrNull { !it.second.indexMetadata.isComplete }
                        ?.second
                        ?.indexMetadata
                        ?.reasonCode ?: "index_metadata_unavailable",
                )
        }
        val unique = sides.any { (alias, table) ->
            exactUniqueJoinKey(table, joinedColumns(alias, joins))
        }
        if (!unique) {
            signals +=
                RiskSignal(
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

internal fun joinedColumns(alias: String, joins: List<com.safedb.model.JoinSpec>): Set<String> =
    joins.mapNotNullTo(linkedSetOf()) { join ->
        when (alias) {
            join.leftAlias -> join.leftColumn
            join.rightAlias -> join.rightColumn
            else -> null
        }
    }

internal fun exactUniqueJoinKey(table: TableInfo, joinedColumns: Set<String>): Boolean =
    table.indexMetadata.isComplete &&
        joinedColumns.isNotEmpty() &&
        table.indexes.any { index ->
            val keys = normalizedKeys(index)
            index.isUnique &&
                index.isPartial == false &&
                keys.isNotEmpty() &&
                keys.none { it.expression || it.column == null } &&
                keys.mapNotNull(IndexKey::column).toSet() == joinedColumns &&
                keys.size == joinedColumns.size
        }

private fun matchingForeignKey(
    firstAlias: String,
    first: TableInfo,
    secondAlias: String,
    second: TableInfo,
    joins: List<com.safedb.model.JoinSpec>,
): Pair<String, ForeignKeyInfo>? {
    fun matches(
        fromAlias: String,
        from: TableInfo,
        toAlias: String,
        to: TableInfo,
    ): ForeignKeyInfo? =
        from.foreignKeys.firstOrNull { fk ->
            fk.referencedSchema == to.schema &&
                fk.referencedTable == to.name &&
                fk.columns.indices.all { index ->
                    joins.any { join ->
                        (join.leftAlias == fromAlias &&
                            join.leftColumn == fk.columns[index] &&
                            join.rightAlias == toAlias &&
                            join.rightColumn == fk.referencedColumns[index]) ||
                            (join.rightAlias == fromAlias &&
                                join.rightColumn == fk.columns[index] &&
                                join.leftAlias == toAlias &&
                                join.leftColumn == fk.referencedColumns[index])
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
    data class Operation(
        val kind: PlanOperationKind,
        val aliases: Set<String>,
        val compatible: Boolean = false,
    )
    val operations = mutableListOf<Operation>()
    if (spec.groups.isNotEmpty()) {
        operations +=
            Operation(
                PlanOperationKind.Grouping,
                spec.groups.mapTo(linkedSetOf()) { it.tableAlias },
            )
    }
    if (spec.distinct && !distinctRedundant(spec)) {
        val aliases =
            spec.columns
                .mapTo(linkedSetOf()) { it.tableAlias }
                .ifEmpty { spec.tables.mapTo(linkedSetOf()) { it.alias } }
        operations += Operation(PlanOperationKind.Distinct, aliases)
    }
    if (spec.sorts.isNotEmpty()) {
        val sortAliases = spec.sorts.mapTo(linkedSetOf()) { it.tableAlias }
        val compatibility =
            if (sortAliases.size == 1) {
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
            AccessPathState.Incompatible ->
                operations += Operation(PlanOperationKind.Sort, sortAliases)
            AccessPathState.Unknown ->
                uncertainties +=
                    RiskUncertainty(
                        "sort_compatibility_unknown",
                        RiskSubject(operation = "ordered sort"),
                        "index_direction_or_capability_unknown",
                    )
        }
    }
    for ((kind, aliases, _) in operations) {
        val bounded = aliases.all { alias ->
            alias in boundedAliases ||
                tablesByAlias[alias]?.tableSize?.sizeClass == TableSizeClass.Small
        }
        signals +=
            RiskSignal(
                if (bounded) RiskSignalCode.BoundedBlockingOperation
                else RiskSignalCode.LimitCannotBoundWork,
                RiskCategory.Operations,
                RiskSubject(operation = kind.name.lowercase()),
                if (bounded) 1 else 3,
                SignalBasis.StaticSchema,
                EvidenceConfidence.Medium,
                target = RiskTarget.Operation(kind, aliases),
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
        val prefixConstrained =
            keys.take(sortPosition).all { key ->
                key.column != null &&
                    leaves.any { it.column == key.column && isPointConstraint(it) }
            }
        if (!prefixConstrained) continue
        val orderedKeys = keys.drop(sortPosition).take(sorts.size)
        if (
            orderedKeys.size != sorts.size ||
                orderedKeys.map(IndexKey::column) != sorts.map { it.column }
        )
            continue
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

private fun isPointConstraint(filter: com.safedb.model.FilterSpec): Boolean =
    when (filter.op) {
        FilterOp.Eq,
        FilterOp.IsNull,
        FilterOp.IsEmpty -> true
        FilterOp.In -> (filter.value as? FilterValue.ListValue)?.literals?.size == 1
        else -> false
    }

private fun SortDirection.reverse(): SortDirection =
    when (this) {
        SortDirection.Asc -> SortDirection.Desc
        SortDirection.Desc -> SortDirection.Asc
    }

private fun addVolumeSignal(
    validated: ValidatedQuery,
    tablesByAlias: Map<String, TableInfo?>,
    signals: MutableList<RiskSignal>,
) {
    val width =
        validated.columns().sumOf { selection ->
            val category =
                tablesByAlias[selection.tableAlias]
                    ?.columns
                    ?.firstOrNull { it.name == selection.column }
                    ?.category
            widthClassBytes(category)
        }
    val projectedBytes = width.toLong() * validated.spec().limit
    val (code, points) =
        when {
            projectedBytes >= 5L * 1024 * 1024 -> RiskSignalCode.HighProjectedPayload to 2
            projectedBytes >= 1024L * 1024 -> RiskSignalCode.MaterialProjectedPayload to 1
            else -> return
        }
    signals +=
        RiskSignal(
            code,
            RiskCategory.Volume,
            RiskSubject(operation = "projected result payload"),
            points,
            SignalBasis.StaticSchema,
            EvidenceConfidence.Medium,
        )
}

private fun widthClassBytes(category: ColumnCategory?): Int =
    when (category) {
        ColumnCategory.Bool -> 1
        ColumnCategory.Integer,
        ColumnCategory.Decimal,
        ColumnCategory.Date,
        ColumnCategory.DateTime -> 16
        ColumnCategory.Text -> 256
        ColumnCategory.Json -> 512
        ColumnCategory.Binary -> 1024
        ColumnCategory.Other,
        null -> 256
    }

internal fun TableInfo.subject(alias: String, column: String? = null) =
    RiskSubject(tableAlias = alias, schema = schema, table = name, column = column)

internal fun signalMessage(signal: RiskSignal): String =
    when (signal.code) {
        RiskSignalCode.NoEffectiveRestriction ->
            "No effective restriction applies to ${signal.subject.displayName()}."
        RiskSignalCode.NoKnownCompatibleAccessPath ->
            "No known compatible index applies to ${signal.subject.displayName()}."
        RiskSignalCode.ScanProneTextPredicate ->
            "Text search on ${signal.subject.displayName()} may scan many rows."
        RiskSignalCode.ScanProneNegativePredicate ->
            "Negative predicate on ${signal.subject.displayName()} may scan many rows."
        RiskSignalCode.OrBranchWithoutCompatiblePath ->
            "An OR branch has no known compatible access path."
        RiskSignalCode.AdditionalJoinedRelation -> "The query joins an additional relation."
        RiskSignalCode.ForeignKeyWithoutSupportingIndex ->
            "A foreign-key join has no known supporting index."
        RiskSignalCode.JoinExpansionPossible ->
            "No joined key is known to be unique, so results may expand."
        RiskSignalCode.LimitCannotBoundWork ->
            "Grouping, distinct rows, or sorting may require processing beyond the result limit."
        RiskSignalCode.BoundedBlockingOperation ->
            "A blocking operation remains above a bounded access path."
        RiskSignalCode.MaterialProjectedPayload -> "The projected result payload is material."
        RiskSignalCode.HighProjectedPayload -> "The projected result payload is high."
        RiskSignalCode.PlanConfirmedLargeScan ->
            "Plan evidence indicates a high-row scan of a large table."
        RiskSignalCode.PlanConfirmedJoinExpansion -> "Plan evidence indicates high join expansion."
    }
