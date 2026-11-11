package com.safedb.mcp

import com.safedb.explore.DateGroupUnit
import com.safedb.explore.DimensionSortMode
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.LabelFilterOp
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.explore.SortDir
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.WorksheetFilter
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.displayColumnLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal fun parseDimensions(
    array: JsonArray,
    columns: Set<String>,
    argument: String,
): Arg<List<PivotDimension>> {
    val dimensions = mutableListOf<PivotDimension>()
    for ((index, element) in array.withIndex()) {
        when (element) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    return invalidArgument("$argument[$index] must be a string or object")
                }
                val column = element.content
                if (column !in columns) return unknownColumn(column, columns)
                dimensions += PivotDimension(column = column)
            }
            is JsonObject -> {
                val column =
                    requireColumn(element, columns, "$argument[$index]").getOrElse {
                        return Arg.Err(it)
                    }
                val label = element.stringField("label") ?: displayColumnLabel(column)
                val grouping =
                    parseGrouping(element["grouping"]).getOrElse {
                        return Arg.Err(it)
                    }
                val sortMode =
                    element.stringField("sort_mode")?.let {
                        parseDimensionSortMode(it)
                            ?: return invalidArgument("Unknown sort_mode '$it'")
                    } ?: DimensionSortMode.SourceOrder
                dimensions +=
                    PivotDimension(
                        column = column,
                        label = label,
                        grouping = grouping,
                        showSubtotals = element.booleanField("show_subtotals") ?: true,
                        sortMode = sortMode,
                        sortMeasureAlias = element.stringField("sort_measure_alias"),
                    )
            }
            else -> return invalidArgument("$argument[$index] must be a string or object")
        }
    }
    return Arg.Ok(dimensions)
}

private fun parseGrouping(element: JsonElement?): Arg<PivotGrouping> =
    when {
        element == null || element is JsonNull -> Arg.Ok(PivotGrouping.Exact)
        element is JsonPrimitive && element.isString -> {
            val grouping =
                when (normalize(element.content)) {
                    "exact" -> PivotGrouping.Exact
                    "year" -> PivotGrouping.Date(DateGroupUnit.Year)
                    "quarter" -> PivotGrouping.Date(DateGroupUnit.Quarter)
                    "month" -> PivotGrouping.Date(DateGroupUnit.Month)
                    "isoweek" -> PivotGrouping.Date(DateGroupUnit.IsoWeek)
                    "day" -> PivotGrouping.Date(DateGroupUnit.Day)
                    else -> return invalidArgument("Unknown grouping '${element.content}'")
                }
            Arg.Ok(grouping)
        }
        element is JsonObject -> {
            val size = element.stringField("bin_size")
            if (size == null) {
                return invalidArgument("grouping objects require bin_size")
            }
            Arg.Ok(PivotGrouping.NumberBin(size = size, start = element.stringField("bin_start")))
        }
        else -> invalidArgument("grouping must be a string or object")
    }

private fun parseDimensionSortMode(value: String): DimensionSortMode? =
    when (normalize(value)) {
        "sourceorder" -> DimensionSortMode.SourceOrder
        "labelasc",
        "labelascending" -> DimensionSortMode.LabelAscending
        "labeldesc",
        "labeldescending" -> DimensionSortMode.LabelDescending
        "valueasc",
        "valueascending" -> DimensionSortMode.ValueAscending
        "valuedesc",
        "valuedescending" -> DimensionSortMode.ValueDescending
        else -> null
    }

internal fun parseMeasures(array: JsonArray, columns: Set<String>): Arg<List<PivotMeasure>> {
    val measures = mutableListOf<PivotMeasure>()
    val objects =
        parseObjectArray(array, "measures").getOrElse {
            return Arg.Err(it)
        }
    for ((index, obj) in objects.withIndex()) {
        val alias =
            obj.stringField("alias") ?: return invalidArgument("measures[$index].alias is required")
        val fnText =
            obj.stringField("fn") ?: return invalidArgument("measures[$index].fn is required")
        val fn = parseMeasureFn(fnText) ?: return invalidArgument("Unknown measure fn '$fnText'")
        val sourceColumn = obj.stringField("source_column")
        if (sourceColumn != null && sourceColumn !in columns) {
            return unknownColumn(sourceColumn, columns)
        }
        val showAs =
            parseShowAs(obj["show_as"]).getOrElse {
                return Arg.Err(it)
            }
        val base =
            PivotMeasure(
                alias = alias,
                fn = fn,
                sourceColumn = sourceColumn,
                formula = obj.stringField("formula"),
                showAs = showAs,
            )
        measures += obj.stringField("label")?.let { base.copy(label = it) } ?: base
    }
    return Arg.Ok(measures)
}

private fun parseMeasureFn(value: String): MeasureFn? =
    MeasureFn.entries.firstOrNull { normalize(it.name) == normalize(value) }

private fun parseShowAs(element: JsonElement?): Arg<PivotShowAs> {
    if (element == null || element is JsonNull) return Arg.Ok(PivotShowAs())
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        return invalidArgument("show_as must be a string")
    }
    val mode =
        ShowAsMode.entries.firstOrNull { normalize(it.name) == normalize(primitive.content) }
            ?: return invalidArgument("Unknown show_as '${primitive.content}'")
    return Arg.Ok(PivotShowAs(mode = mode))
}

internal fun parsePivotFilters(array: JsonArray, columns: Set<String>): Arg<List<PivotFilter>> {
    val filters = mutableListOf<PivotFilter>()
    val objects =
        parseObjectArray(array, "filters").getOrElse {
            return Arg.Err(it)
        }
    for ((index, obj) in objects.withIndex()) {
        val column =
            requireColumn(obj, columns, "filters[$index]").getOrElse {
                return Arg.Err(it)
            }
        val op = obj.stringField("op") ?: return invalidArgument("filters[$index].op is required")
        val id = "filter-$index"
        val label = displayColumnLabel(column)
        val filter =
            when (normalize(op)) {
                "members" ->
                    PivotFilter.Members(
                        id = id,
                        column = column,
                        label = label,
                        includedKeys = obj.stringList("included_keys")?.toSet().orEmpty(),
                    )
                else -> {
                    val labelOp = parseLabelFilterOp(op)
                    if (labelOp != null) {
                        PivotFilter.Label(
                            id = id,
                            column = column,
                            label = label,
                            op = labelOp,
                            value = obj.stringField("value").orEmpty(),
                        )
                    } else {
                        val valueOp =
                            parseValueFilterOp(op)
                                ?: return invalidArgument("Unknown filter op '$op'")
                        val measureAlias =
                            obj.stringField("measure_alias")
                                ?: return invalidArgument(
                                    "filters[$index].measure_alias is required"
                                )
                        PivotFilter.Value(
                            id = id,
                            column = column,
                            label = label,
                            measureAlias = measureAlias,
                            op = valueOp,
                            value = obj.stringField("value").orEmpty(),
                            secondValue = obj.stringField("second_value"),
                            count = obj.intField("count") ?: 10,
                        )
                    }
                }
            }
        filters += filter
    }
    return Arg.Ok(filters)
}

private fun parseLabelFilterOp(value: String): LabelFilterOp? =
    when (normalize(value)) {
        "equals" -> LabelFilterOp.Equals
        "contains" -> LabelFilterOp.Contains
        "startswith" -> LabelFilterOp.StartsWith
        "endswith" -> LabelFilterOp.EndsWith
        else -> null
    }

private fun parseValueFilterOp(value: String): ValueFilterOp? =
    when (normalize(value)) {
        "gt",
        "greaterthan" -> ValueFilterOp.GreaterThan
        "gte",
        "ge",
        "greaterthanorequal" -> ValueFilterOp.GreaterThanOrEqual
        "lt",
        "lessthan" -> ValueFilterOp.LessThan
        "lte",
        "le",
        "lessthanorequal" -> ValueFilterOp.LessThanOrEqual
        "between" -> ValueFilterOp.Between
        "top" -> ValueFilterOp.Top
        "bottom" -> ValueFilterOp.Bottom
        else -> null
    }

internal fun parseExploreSort(obj: JsonObject, columns: Set<String>): Arg<ExploreSort> {
    val column = obj.stringField("column")
    val measureAlias = obj.stringField("measure_alias")
    val target =
        when {
            column != null -> {
                if (column !in columns) return unknownColumn(column, columns)
                ExploreSortTarget.Dimension(column)
            }
            measureAlias != null -> ExploreSortTarget.Measure(measureAlias)
            else -> return invalidArgument("sort requires column or measure_alias")
        }
    val dir =
        parseSortDir(obj).getOrElse {
            return Arg.Err(it)
        }
    return Arg.Ok(ExploreSort(target = target, dir = dir))
}

internal fun parseWorksheetFilters(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<WorksheetFilter>> {
    val filters = mutableListOf<WorksheetFilter>()
    val objects =
        parseObjectArray(array, "filters").getOrElse {
            return Arg.Err(it)
        }
    for ((index, obj) in objects.withIndex()) {
        val column =
            requireColumn(obj, columns, "filters[$index]").getOrElse {
                return Arg.Err(it)
            }
        val opText =
            obj.stringField("op") ?: return invalidArgument("filters[$index].op is required")
        val op =
            parseWorksheetFilterOp(opText) ?: return invalidArgument("Unknown filter op '$opText'")
        filters +=
            WorksheetFilter(
                id = "filter-$index",
                column = column,
                label = displayColumnLabel(column),
                op = op,
                value = obj.stringField("value").orEmpty(),
                secondValue = obj.stringField("second_value"),
                includedKeys = obj.stringList("included_keys")?.toSet().orEmpty(),
            )
    }
    return Arg.Ok(filters)
}

private fun parseWorksheetFilterOp(value: String): WorksheetFilterOp? =
    when (normalize(value)) {
        "members" -> WorksheetFilterOp.Members
        "equals",
        "eq" -> WorksheetFilterOp.Equals
        "notequals",
        "ne" -> WorksheetFilterOp.NotEquals
        "contains" -> WorksheetFilterOp.Contains
        "startswith" -> WorksheetFilterOp.StartsWith
        "endswith" -> WorksheetFilterOp.EndsWith
        "greaterthan",
        "gt" -> WorksheetFilterOp.GreaterThan
        "greaterthanorequal",
        "gte",
        "ge" -> WorksheetFilterOp.GreaterThanOrEqual
        "lessthan",
        "lt" -> WorksheetFilterOp.LessThan
        "lessthanorequal",
        "lte",
        "le" -> WorksheetFilterOp.LessThanOrEqual
        "between" -> WorksheetFilterOp.Between
        "isnull" -> WorksheetFilterOp.IsNull
        "isnotnull" -> WorksheetFilterOp.IsNotNull
        else -> null
    }

internal fun parseWorksheetSorts(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<WorksheetSort>> {
    val sorts = mutableListOf<WorksheetSort>()
    val objects =
        parseObjectArray(array, "sorts").getOrElse {
            return Arg.Err(it)
        }
    for ((index, obj) in objects.withIndex()) {
        val column =
            requireColumn(obj, columns, "sorts[$index]").getOrElse {
                return Arg.Err(it)
            }
        val dir =
            parseSortDir(obj).getOrElse {
                return Arg.Err(it)
            }
        sorts += WorksheetSort(WorksheetValueRef.Column(column), dir)
    }
    return Arg.Ok(sorts)
}

internal fun parseColumnProjection(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<String>> {
    val projection = mutableListOf<String>()
    for ((index, element) in array.withIndex()) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            return invalidArgument("columns[$index] must be a string")
        }
        if (primitive.content !in columns) return unknownColumn(primitive.content, columns)
        projection += primitive.content
    }
    return Arg.Ok(projection)
}

internal fun parseObjectArray(array: JsonArray, argument: String): Arg<List<JsonObject>> {
    val objects = mutableListOf<JsonObject>()
    for ((index, element) in array.withIndex()) {
        val obj =
            element as? JsonObject ?: return invalidArgument("$argument[$index] must be an object")
        objects += obj
    }
    return Arg.Ok(objects)
}

internal fun requireColumn(obj: JsonObject, columns: Set<String>, path: String): Arg<String> {
    val column = obj.stringField("column") ?: return invalidArgument("$path.column is required")
    if (column !in columns) return unknownColumn(column, columns)
    return Arg.Ok(column)
}

internal fun parseSortDir(obj: JsonObject): Arg<SortDir> {
    val text = obj.stringField("direction") ?: return Arg.Ok(SortDir.Asc)
    return when (normalize(text)) {
        "asc",
        "ascending" -> Arg.Ok(SortDir.Asc)
        "desc",
        "descending" -> Arg.Ok(SortDir.Desc)
        else -> invalidArgument("Unknown sort direction '$text'")
    }
}

internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.stringList(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
    }

private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun invalidArgument(message: String): Arg<Nothing> =
    Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, message))

private fun unknownColumn(column: String, knownColumns: Set<String>): Arg<Nothing> {
    val known = knownColumns.sorted().take(8).joinToString(", ")
    return invalidArgument("Unknown column '$column'. Known columns: $known")
}
