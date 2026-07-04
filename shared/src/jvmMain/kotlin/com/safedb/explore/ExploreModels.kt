package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SafeDbJson
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
        fun defaultFor(sample: QueryResult): ExploreConfig {
            val firstDimension = sample.columns
                .firstOrNull { !looksUniqueIdentifier(it.name) }
                ?: sample.columns.firstOrNull()
            return ExploreConfig(
                rowDimensions = firstDimension?.let {
                    listOf(PivotDimension(column = it.name, label = displayColumnLabel(it.name)))
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
)

fun displayColumnLabel(raw: String): String =
    raw.replace(Regex("^t\\d+__(.+)$"), "$1")

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
