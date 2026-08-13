package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SafeDbJson
import com.safedb.model.TableRef
import java.security.MessageDigest
import java.util.Currency
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

const val EXPLORE_SCHEMA_VERSION = 1
const val MAX_VISIBLE_PIVOT_CELLS = 100_000
const val MAX_VISIBLE_COLUMN_LEAVES = 500

@Serializable
data class ExploreSession(
    val connectionId: String,
    val connectionLabel: String,
    val baseSpec: QuerySpec,
    val baseSpecHash: String,
    val sample: QueryResult,
    val sampleFetchedAtEpochSec: Long,
    val builderLimit: Int,
)

@Serializable
data class ExploreConfig(
    val schemaVersion: Int = EXPLORE_SCHEMA_VERSION,
    val rowDimensions: List<PivotDimension> = emptyList(),
    val columnDimensions: List<PivotDimension> = emptyList(),
    val measures: List<PivotMeasure> = listOf(PivotMeasure.countRows()),
    val filters: List<PivotFilter> = emptyList(),
    val showRowTotals: Boolean = true,
    val showColumnTotals: Boolean = true,
    val showSubtotals: Boolean = true,
    val subtotalPosition: SubtotalPosition = SubtotalPosition.Bottom,
    val collapsedRowPaths: Set<String> = emptySet(),
    val collapsedColumnPaths: Set<String> = emptySet(),
    val nullBucketLabel: String = "(blank)",
    val sort: ExploreSort? = null,
) {
    fun validate(): ExploreConfig {
        require(schemaVersion == EXPLORE_SCHEMA_VERSION) {
            "Unsupported Explore view version $schemaVersion"
        }
        return this
    }

    companion object {
        fun defaultFor(sample: QueryResult, tables: List<TableRef> = emptyList()): ExploreConfig {
            val labels = displayColumnLabels(sample.columns, tables)
            val firstDimension =
                sample.columns.firstOrNull { !looksUniqueIdentifier(it.name) }
                    ?: sample.columns.firstOrNull()
            return ExploreConfig(
                rowDimensions =
                    firstDimension
                        ?.let {
                            listOf(
                                PivotDimension(column = it.name, label = labels.getValue(it.name))
                            )
                        }
                        .orEmpty(),
                measures = listOf(PivotMeasure.countRows()),
            )
        }

        private fun looksUniqueIdentifier(column: String): Boolean {
            val label = displayColumnLabel(column).lowercase()
            return label == "id" || label.endsWith("_id") || label.endsWith(" id")
        }
    }
}

object RejectLegacyPivotColumnDimension :
    JsonTransformingSerializer<ExploreConfig>(ExploreConfig.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val fields = element as? JsonObject ?: return element
        val legacy = fields["columnDimension"]
        if (legacy != null && legacy !is JsonNull) {
            throw SerializationException(
                "Legacy pivot field columnDimension is unsupported; recreate the view in the current format"
            )
        }
        return element
    }
}

@Serializable
data class PivotDimension(
    val column: String,
    val label: String = displayColumnLabel(column),
    val id: String = column,
    val grouping: PivotGrouping = PivotGrouping.Exact,
    val showSubtotals: Boolean = true,
    val sortMode: DimensionSortMode = DimensionSortMode.SourceOrder,
    val sortMeasureAlias: String? = null,
)

@Serializable
enum class DimensionSortMode {
    SourceOrder,
    LabelAscending,
    LabelDescending,
    ValueAscending,
    ValueDescending,
}

@Serializable
sealed class PivotGrouping {
    @Serializable data object Exact : PivotGrouping()

    @Serializable data class Date(val unit: DateGroupUnit) : PivotGrouping()

    @Serializable
    data class NumberBin(val size: String, val start: String? = null) : PivotGrouping()
}

@Serializable
enum class DateGroupUnit {
    Year,
    Quarter,
    Month,
    IsoWeek,
    Day,
}

@Serializable
enum class SubtotalPosition {
    Top,
    Bottom,
}

@Serializable
data class PivotMeasure(
    val alias: String,
    val fn: MeasureFn,
    val sourceColumn: String? = null,
    val label: String = defaultMeasureLabel(fn, sourceColumn),
    val formula: String? = null,
    val showAs: PivotShowAs = PivotShowAs(),
    val numberFormat: PivotNumberFormat = PivotNumberFormat(),
) {
    companion object {
        fun countRows(alias: String = "count"): PivotMeasure =
            PivotMeasure(alias = alias, fn = MeasureFn.Count, sourceColumn = null, label = "Count")
    }
}

@Serializable
enum class MeasureFn(val shortLabel: String, val label: String) {
    Count("Count", "Count rows"),
    CountNumbers("Count numbers", "Count numbers"),
    CountDistinct("Distinct", "Count distinct"),
    Sum("Sum", "Sum"),
    Avg("Avg", "Average"),
    Min("Min", "Minimum"),
    Max("Max", "Maximum"),
    Product("Product", "Product"),
    StdDev("StdDev", "Standard deviation"),
    StdDevPopulation("StdDevP", "Population standard deviation"),
    Variance("Variance", "Variance"),
    VariancePopulation("VarianceP", "Population variance"),
}

@Serializable
data class PivotShowAs(
    val mode: ShowAsMode = ShowAsMode.Value,
    val baseDimensionId: String? = null,
    val baseItemKey: String? = null,
)

@Serializable
enum class ShowAsMode {
    Value,
    PercentGrandTotal,
    PercentRowTotal,
    PercentColumnTotal,
    PercentParent,
    DifferenceFrom,
    PercentDifferenceFrom,
    RunningTotal,
    PercentRunningTotal,
    RankAscending,
    RankDescending,
}

@Serializable
data class PivotNumberFormat(
    val kind: NumberFormatKind = NumberFormatKind.Auto,
    val decimals: Int = 2,
    val thousandsSeparator: Boolean = true,
    val currencyCode: String = defaultCurrencyCode(),
)

@Serializable
enum class NumberFormatKind {
    Auto,
    Number,
    Percent,
    Currency,
    Scientific,
}

@Serializable
sealed class PivotFilter {
    abstract val id: String
    abstract val column: String
    abstract val label: String
    abstract val pinned: Boolean

    @Serializable
    data class Members(
        override val id: String,
        override val column: String,
        override val label: String,
        val includedKeys: Set<String> = emptySet(),
        override val pinned: Boolean = true,
    ) : PivotFilter()

    @Serializable
    data class Label(
        override val id: String,
        override val column: String,
        override val label: String,
        val op: LabelFilterOp,
        val value: String,
        override val pinned: Boolean = false,
    ) : PivotFilter()

    @Serializable
    data class Value(
        override val id: String,
        override val column: String,
        override val label: String,
        val measureAlias: String,
        val op: ValueFilterOp,
        val value: String = "",
        val secondValue: String? = null,
        val count: Int = 10,
        override val pinned: Boolean = false,
    ) : PivotFilter()
}

@Serializable
enum class LabelFilterOp {
    Equals,
    Contains,
    StartsWith,
    EndsWith,
}

@Serializable
enum class ValueFilterOp {
    GreaterThan,
    GreaterThanOrEqual,
    LessThan,
    LessThanOrEqual,
    Between,
    Top,
    Bottom,
}

@Serializable data class ExploreSort(val target: ExploreSortTarget, val dir: SortDir = SortDir.Asc)

@Serializable
sealed class ExploreSortTarget {
    @Serializable data class Dimension(val column: String) : ExploreSortTarget()

    @Serializable data class Measure(val alias: String) : ExploreSortTarget()
}

@Serializable
enum class SortDir {
    Asc,
    Desc,
}

data class ExplorePreviewResult(
    val result: QueryResult,
    val warnings: List<String>,
    val layout: ExplorePivotLayout,
)

data class ExplorePivotLayout(
    val rowDimensions: List<PivotDimension>,
    val columnDimension: PivotDimension?,
    val columnDimensions: List<PivotDimension> = listOfNotNull(columnDimension),
    val measures: List<PivotMeasure>,
    val columnGroups: List<ExploreColumnGroup>,
    val hasGrandTotalRow: Boolean,
    val rowEntries: List<PivotRowEntry> = emptyList(),
    val columnLeaves: List<PivotColumnLeaf> = emptyList(),
    val columnHeaderRows: List<List<PivotHeaderCell>> = emptyList(),
    val formattedRows: List<List<String>> = emptyList(),
    val cellLineage: Map<String, List<Int>> = emptyMap(),
    val overflowMessage: String? = null,
)

data class ExploreColumnGroup(
    val label: String?,
    val startColumnIndex: Int,
    val measureAliases: List<String>,
    val isTotal: Boolean = false,
)

enum class PivotRowKind {
    Group,
    Leaf,
    Subtotal,
    GrandTotal,
}

data class PivotRowEntry(
    val pathKey: String,
    val label: String,
    val depth: Int,
    val kind: PivotRowKind,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

data class PivotColumnLeaf(
    val pathKey: String,
    val labels: List<String>,
    val isSubtotal: Boolean,
    val isGrandTotal: Boolean,
)

data class PivotHeaderCell(
    val pathKey: String,
    val label: String,
    val startLeafIndex: Int,
    val leafSpan: Int,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val isTotal: Boolean,
)

fun displayColumnLabel(raw: String): String = raw.replace(Regex("^t\\d+__(.+)$"), "$1")

fun displayColumnLabels(
    columns: List<ResultColumn>,
    tables: List<TableRef> = emptyList(),
): Map<String, String> {
    val baseLabels = columns.associate { it.name to displayColumnLabel(it.name) }
    val duplicateBases =
        baseLabels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateBases.isEmpty()) return baseLabels

    val tableByAlias = tables.associate { it.alias to it.name }
    val qualified = columns.associate { column ->
        val base = baseLabels.getValue(column.name)
        if (base !in duplicateBases) {
            column.name to base
        } else {
            val alias = column.name.substringBefore("__", missingDelimiterValue = "")
            val qualifier = tableByAlias[alias] ?: alias.ifEmpty { "field" }
            column.name to "$qualifier.$base"
        }
    }

    val duplicateQualified =
        qualified.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateQualified.isEmpty()) return qualified
    return qualified.mapValues { (raw, label) ->
        if (label !in duplicateQualified) {
            label
        } else {
            val alias = raw.substringBefore("__", missingDelimiterValue = raw)
            "$label ($alias)"
        }
    }
}

fun exploreSpecHash(spec: QuerySpec): String {
    val json = SafeDbJson.lenient.encodeToString(QuerySpec.serializer(), spec)
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun defaultMeasureLabel(fn: MeasureFn, sourceColumn: String?): String {
    val source = sourceColumn?.let(::displayColumnLabel)
    if (fn == MeasureFn.Count) return source?.let { "Count $it" } ?: "Count"
    val counting = fn == MeasureFn.CountNumbers || fn == MeasureFn.CountDistinct
    return "${fn.shortLabel} ${source ?: if (counting) "values" else "value"}"
}

private fun defaultCurrencyCode(): String = runCatching {
    Currency.getInstance(Locale.getDefault()).currencyCode
}
    .getOrDefault("USD")
