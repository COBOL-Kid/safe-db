package com.safedb.query

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.Dialect
import com.safedb.model.EvidenceConfidence
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.GroupSpec
import com.safedb.model.IndexCapabilities
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.MetadataCoverage
import com.safedb.model.Outcome
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.SafeDbJson
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class QueryRiskCorpusTest {
    @Test
    fun versionedCrossDialectCorpusMatchesGoldenOutcomes() {
        val resource =
            requireNotNull(javaClass.getResource("/query-risk/v1/normalized-corpus.json"))
        val corpus = SafeDbJson.lenient.decodeFromString<RiskCorpus>(resource.readText())
        assertEquals(QUERY_RISK_SCORE_VERSION, corpus.scoreVersion)

        for (case in corpus.cases) {
            val (spec, schema) = case.fixture()
            val validated =
                when (val result = validateQuery(spec, schema, emptyList(), case.dialect)) {
                    is Outcome.Ok -> result.value.first
                    is Outcome.Err -> error("${case.id}: ${result.message}")
                }
            val assessment = assessStaticQueryRisk(validated, schema, case.dialect)
            val decision = applyRiskGate(assessment, case.gate)

            assertEquals(case.expectedScore, assessment.score, case.id)
            assertEquals(case.expectedSeverity, assessment.severity, case.id)
            assertEquals(case.expectedCategoryScores, assessment.categoryScores, case.id)
            assertEquals(
                case.expectedSignalCodes,
                assessment.signals.map { it.code }.distinct(),
                case.id,
            )
            assertEquals(
                case.expectedUncertaintyCodes,
                assessment.uncertainties.map { it.code }.distinct(),
                case.id,
            )
            assertEquals(case.expectedGateState, decision.state, case.id)
        }
    }
}

@Serializable
private data class RiskCorpus(
    @SerialName("score_version") val scoreVersion: Int,
    val cases: List<RiskCorpusCase>,
)

@Serializable
private data class RiskCorpusCase(
    val id: String,
    val dialect: Dialect,
    val predicate: CorpusPredicate,
    @SerialName("blocking_operation") val blockingOperation: Boolean,
    val limit: Int,
    @SerialName("size_class") val sizeClass: TableSizeClass = TableSizeClass.Medium,
    val confidence: EvidenceConfidence = EvidenceConfidence.Medium,
    val gate: QueryRiskGate = QueryRiskGate.Standard,
    val join: CorpusJoin = CorpusJoin.None,
    @SerialName("expected_score") val expectedScore: Int,
    @SerialName("expected_severity") val expectedSeverity: QueryRiskSeverity,
    @SerialName("expected_category_scores") val expectedCategoryScores: Map<RiskCategory, Int>,
    @SerialName("expected_signal_codes") val expectedSignalCodes: List<RiskSignalCode>,
    @SerialName("expected_uncertainty_codes")
    val expectedUncertaintyCodes: List<String> = emptyList(),
    @SerialName("expected_gate_state") val expectedGateState: RiskGateState,
) {
    fun fixture(): Pair<QuerySpec, Schema> {
        val notes = predicate == CorpusPredicate.BroadTextWithoutIndex
        val column =
            if (notes) ColumnInfo("notes", "text", true, category = ColumnCategory.Text)
            else
                ColumnInfo(
                    "id",
                    "int",
                    false,
                    joinEligible = join != CorpusJoin.None,
                    category = ColumnCategory.Integer,
                )
        val indexes =
            if (predicate == CorpusPredicate.IndexedEquality) {
                listOf(
                    IndexInfo(
                        name = "orders_lookup",
                        columns = listOf("id"),
                        keys = listOf(IndexKey("id", SortDirection.Asc)),
                        capabilities =
                            IndexCapabilities(
                                equality = true,
                                ordering = true,
                                specializedText = false,
                                expressionKeys = false,
                                partialPredicate = false,
                                includedColumns = false,
                            ),
                        isPartial = false,
                    )
                )
            } else {
                emptyList()
            }
        val size =
            if (sizeClass == TableSizeClass.Unknown) TableSizeEstimate()
            else TableSizeEstimate(sizeClass, MetadataCoverage.complete(), confidence)
        val table =
            TableInfo(
                "public",
                "orders",
                listOf(column),
                indexes,
                indexMetadata = MetadataCoverage.complete(),
                foreignKeyMetadata = MetadataCoverage.complete(),
                tableSize = size,
            )
        val filters =
            when (predicate) {
                CorpusPredicate.None -> FilterGroup("root")
                CorpusPredicate.IndexedEquality ->
                    FilterGroup(
                        "root",
                        children =
                            listOf(
                                FilterNode.Leaf(
                                    FilterSpec(
                                        "id-eq",
                                        "t0",
                                        "id",
                                        FilterOp.Eq,
                                        FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
                                    )
                                )
                            ),
                    )
                CorpusPredicate.BroadTextWithoutIndex ->
                    FilterGroup(
                        "root",
                        children =
                            listOf(
                                FilterNode.Leaf(
                                    FilterSpec(
                                        "notes-contains",
                                        "t0",
                                        "notes",
                                        FilterOp.Contains,
                                        FilterValue.Single(FilterLiteral(LiteralKind.Text, "term")),
                                    )
                                )
                            ),
                    )
            }
        val tables = mutableListOf(table)
        val tableRefs = mutableListOf(TableRef("public", "orders", "t0"))
        val joins = mutableListOf<JoinSpec>()
        if (join != CorpusJoin.None) {
            tableRefs += TableRef("public", "items", "t1")
            joins += JoinSpec("t0", "id", "t1", "order_id")
            tables +=
                joinedTable(
                    "items",
                    size,
                    fkToOrders = join == CorpusJoin.FkWithoutSupportingIndex,
                )
        }
        if (join == CorpusJoin.ThreeTableChain) {
            tableRefs += TableRef("public", "events", "t2")
            joins += JoinSpec("t1", "id", "t2", "item_id")
            tables += joinedTable("events", size, keyColumn = "item_id")
        }
        val selected = if (notes) "notes" else "id"
        val spec =
            QuerySpec(
                tables = tableRefs,
                columns = listOf(ColumnSel("t0", selected)),
                joins = joins,
                filters = filters,
                limit = limit,
                groups = if (blockingOperation) listOf(GroupSpec("t0", selected)) else emptyList(),
            )
        return spec to Schema(tables)
    }
}

private fun joinedTable(
    name: String,
    size: TableSizeEstimate,
    keyColumn: String = "order_id",
    fkToOrders: Boolean = false,
): TableInfo =
    TableInfo(
        "public",
        name,
        listOf(
            ColumnInfo("id", "int", false, joinEligible = true, category = ColumnCategory.Integer),
            ColumnInfo(
                keyColumn,
                "int",
                false,
                joinEligible = true,
                category = ColumnCategory.Integer,
            ),
        ),
        indexes = emptyList(),
        foreignKeys =
            if (fkToOrders) {
                listOf(
                    ForeignKeyInfo(
                        "fk_${name}_order",
                        listOf(keyColumn),
                        "public",
                        "orders",
                        listOf("id"),
                    )
                )
            } else {
                emptyList()
            },
        indexMetadata = MetadataCoverage.complete(),
        foreignKeyMetadata = MetadataCoverage.complete(),
        tableSize = size,
    )

@Serializable
private enum class CorpusPredicate {
    None,
    IndexedEquality,
    BroadTextWithoutIndex,
}

@Serializable
private enum class CorpusJoin {
    None,
    FkWithoutSupportingIndex,
    NonUniqueJoin,
    ThreeTableChain,
}
