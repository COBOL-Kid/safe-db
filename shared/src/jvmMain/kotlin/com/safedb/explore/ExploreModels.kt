package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SafeDbJson
import com.safedb.model.TableRef
import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val EXPLORE_SCHEMA_VERSION = 1

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
    val columnDimension: PivotDimension? = null,
    val measures: List<PivotMeasure> = listOf(PivotMeasure.countRows()),
    val showRowTotals: Boolean = true,
    val showColumnTotals: Boolean = true,
    val nullBucketLabel: String = "(blank)",
    val sort: ExploreSort? = null,
) {
    companion object {
        fun defaultFor(sample: QueryResult, tables: List<TableRef> = emptyList()): ExploreConfig {
            val labels = displayColumnLabels(sample.columns, tables)
            val firstDimension = sample.columns
                .firstOrNull { !looksUniqueIdentifier(it.name) }
                ?: sample.columns.firstOrNull()
            return ExploreConfig(
                rowDimensions = firstDimension?.let {
                    listOf(PivotDimension(column = it.name, label = labels.getValue(it.name)))
                }.orEmpty(),
                measures = listOf(PivotMeasure.countRows()),
            )
        }

        private fun looksUniqueIdentifier(column: String): Boolean {
            val label = displayColumnLabel(column).lowercase()
            return label == "id" || label.endsWith("_id") || label.endsWith(" id")
        }
    }
}

@Serializable
data class PivotDimension(
    val column: String,
    val label: String = displayColumnLabel(column),
)

@Serializable
data class PivotMeasure(
    val alias: String,
    val fn: MeasureFn,
    val sourceColumn: String? = null,
    val label: String = defaultMeasureLabel(fn, sourceColumn),
) {
    companion object {
        fun countRows(alias: String = "count"): PivotMeasure =
            PivotMeasure(alias = alias, fn = MeasureFn.Count, sourceColumn = null, label = "Count")
    }
}

@Serializable
enum class MeasureFn {
    Count,
    CountDistinct,
    Sum,
    Avg,
    Min,
    Max,
}

@Serializable
data class ExploreSort(
    val target: ExploreSortTarget,
    val dir: SortDir = SortDir.Asc,
)

@Serializable
sealed class ExploreSortTarget {
    @Serializable
    data class Dimension(val column: String) : ExploreSortTarget()

    @Serializable
    data class Measure(val alias: String) : ExploreSortTarget()
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
    val measures: List<PivotMeasure>,
    val columnGroups: List<ExploreColumnGroup>,
    val hasGrandTotalRow: Boolean,
)

data class ExploreColumnGroup(
    val label: String?,
    val startColumnIndex: Int,
    val measureAliases: List<String>,
    val isTotal: Boolean = false,
)

fun displayColumnLabel(raw: String): String =
    raw.replace(Regex("^t\\d+__(.+)$"), "$1")

/**
 * Produces short labels for ordinary results and table-qualified labels only
 * where a joined result would otherwise show duplicate field names.
 */
fun displayColumnLabels(
    columns: List<ResultColumn>,
    tables: List<TableRef> = emptyList(),
): Map<String, String> {
    val baseLabels = columns.associate { it.name to displayColumnLabel(it.name) }
    val duplicateBases = baseLabels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
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

    val duplicateQualified = qualified.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
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
    return when (fn) {
        MeasureFn.Count -> source?.let { "Count $it" } ?: "Count"
        MeasureFn.CountDistinct -> "Distinct ${source ?: "values"}"
        MeasureFn.Sum -> "Sum ${source ?: "value"}"
        MeasureFn.Avg -> "Avg ${source ?: "value"}"
        MeasureFn.Min -> "Min ${source ?: "value"}"
        MeasureFn.Max -> "Max ${source ?: "value"}"
    }
}
