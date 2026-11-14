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
                val path = "$argument[$index]"
                requireKnownKeys(
                        element,
                        path,
                        "column",
                        "label",
                        "grouping",
                        "show_subtotals",
                        "sort_mode",
                        "sort_measure_alias",
                    )
                    .getOrElse {
                        return Arg.Err(it)
                    }
                val column =
                    requireColumn(element, columns, path).getOrElse {
                        return Arg.Err(it)
                    }
                val label =
                    element.optionalStringField("label", path).getOrElse {
                        return Arg.Err(it)
                    } ?: displayColumnLabel(column)
                val grouping =
                    parseGrouping(element["grouping"], path).getOrElse {
                        return Arg.Err(it)
                    }
                val sortMode =
                    element
                        .optionalStringField("sort_mode", path)
                        .getOrElse {
                            return Arg.Err(it)
                        }
                        ?.let {
                            parseDimensionSortMode(it)
                                ?: return invalidArgument("Unknown sort_mode '$it'")
                        } ?: DimensionSortMode.SourceOrder
                val showSubtotals =
                    element.optionalBooleanField("show_subtotals", path).getOrElse {
                        return Arg.Err(it)
                    } ?: true
                val sortMeasureAlias =
                    element.optionalStringField("sort_measure_alias", path).getOrElse {
                        return Arg.Err(it)
                    }
                dimensions +=
                    PivotDimension(
                        column = column,
                        label = label,
                        grouping = grouping,
                        showSubtotals = showSubtotals,
                        sortMode = sortMode,
                        sortMeasureAlias = sortMeasureAlias,
                    )
            }
            else -> return invalidArgument("$argument[$index] must be a string or object")
        }
    }
    return Arg.Ok(dimensions)
}

private fun parseGrouping(element: JsonElement?, parentPath: String): Arg<PivotGrouping> =
    when (element) {
        null,
        is JsonNull -> Arg.Ok(PivotGrouping.Exact)
        is JsonPrimitive -> {
            if (!element.isString) {
                return invalidArgument("grouping must be a string or object")
            }
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
        is JsonObject -> {
            val path = "$parentPath.grouping"
            requireKnownKeys(element, path, "bin_size", "bin_start").getOrElse {
                return Arg.Err(it)
            }
            val size =
                element.optionalStringField("bin_size", path).getOrElse {
                    return Arg.Err(it)
                }
            if (size.isNullOrBlank()) {
                return invalidArgument("grouping objects require bin_size")
            }
            val start =
                element.optionalStringField("bin_start", path).getOrElse {
                    return Arg.Err(it)
                }
            Arg.Ok(PivotGrouping.NumberBin(size = size, start = start))
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
        val path = "measures[$index]"
        requireKnownKeys(
                obj,
                path,
                "alias",
                "fn",
                "source_column",
                "formula",
                "show_as",
                "label",
            )
            .getOrElse {
                return Arg.Err(it)
            }
        val alias =
            obj.requireNonBlankString("alias", path).getOrElse {
                return Arg.Err(it)
            }
        val fnText =
            obj.requireNonBlankString("fn", path).getOrElse {
                return Arg.Err(it)
            }
        val fn = parseMeasureFn(fnText) ?: return invalidArgument("Unknown measure fn '$fnText'")
        val sourceColumn =
            obj.optionalStringField("source_column", path).getOrElse {
                return Arg.Err(it)
            }
        if (sourceColumn != null && sourceColumn !in columns) {
            return unknownColumn(sourceColumn, columns)
        }
        val showAs =
            parseShowAs(obj["show_as"]).getOrElse {
                return Arg.Err(it)
            }
        val formula =
            obj.optionalStringField("formula", path).getOrElse {
                return Arg.Err(it)
            }
        val label =
            obj.optionalStringField("label", path).getOrElse {
                return Arg.Err(it)
            }
        val base =
            PivotMeasure(
                alias = alias,
                fn = fn,
                sourceColumn = sourceColumn,
                formula = formula,
                showAs = showAs,
            )
        measures += label?.let { base.copy(label = it) } ?: base
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
        val path = "filters[$index]"
        val column =
            requireColumn(obj, columns, path).getOrElse {
                return Arg.Err(it)
            }
        val op =
            obj.requireNonBlankString("op", path).getOrElse {
                return Arg.Err(it)
            }
        val id = "filter-$index"
        val label = displayColumnLabel(column)
        val filter =
            when (normalize(op)) {
                "members" -> {
                    requireKnownKeys(obj, path, "column", "op", "included_keys").getOrElse {
                        return Arg.Err(it)
                    }
                    val includedKeys =
                        obj.optionalStringListField("included_keys", path).getOrElse {
                            return Arg.Err(it)
                        }
                    PivotFilter.Members(
                        id = id,
                        column = column,
                        label = label,
                        includedKeys = includedKeys?.toSet().orEmpty(),
                    )
                }
                else -> {
                    val labelOp = parseLabelFilterOp(op)
                    if (labelOp != null) {
                        requireKnownKeys(obj, path, "column", "op", "value").getOrElse {
                            return Arg.Err(it)
                        }
                        val value =
                            obj.optionalStringField("value", path).getOrElse {
                                return Arg.Err(it)
                            }
                        PivotFilter.Label(
                            id = id,
                            column = column,
                            label = label,
                            op = labelOp,
                            value = value.orEmpty(),
                        )
                    } else {
                        val valueOp =
                            parseValueFilterOp(op)
                                ?: return invalidArgument("Unknown filter op '$op'")
                        requireKnownKeys(
                                obj,
                                path,
                                "column",
                                "op",
                                "measure_alias",
                                "value",
                                "second_value",
                                "count",
                            )
                            .getOrElse {
                                return Arg.Err(it)
                            }
                        val measureAlias =
                            obj.requireNonBlankString("measure_alias", path).getOrElse {
                                return Arg.Err(it)
                            }
                        val value =
                            obj.optionalStringField("value", path).getOrElse {
                                return Arg.Err(it)
                            }
                        val secondValue =
                            obj.optionalStringField("second_value", path).getOrElse {
                                return Arg.Err(it)
                            }
                        val count =
                            obj.optionalIntField("count", path).getOrElse {
                                return Arg.Err(it)
                            }
                        PivotFilter.Value(
                            id = id,
                            column = column,
                            label = label,
                            measureAlias = measureAlias,
                            op = valueOp,
                            value = value.orEmpty(),
                            secondValue = secondValue,
                            count = count ?: 10,
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
    requireKnownKeys(obj, "sort", "column", "measure_alias", "direction").getOrElse {
        return Arg.Err(it)
    }
    val column =
        obj.optionalStringField("column", "sort").getOrElse {
            return Arg.Err(it)
        }
    val measureAlias =
        obj.optionalStringField("measure_alias", "sort").getOrElse {
            return Arg.Err(it)
        }
    if (column != null && measureAlias != null) {
        return invalidArgument("sort cannot include both column and measure_alias")
    }
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
        parseSortDir(obj, "sort").getOrElse {
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
        val path = "filters[$index]"
        requireKnownKeys(obj, path, "column", "op", "value", "second_value", "included_keys")
            .getOrElse {
                return Arg.Err(it)
            }
        val column =
            requireColumn(obj, columns, path).getOrElse {
                return Arg.Err(it)
            }
        val opText =
            obj.requireNonBlankString("op", path).getOrElse {
                return Arg.Err(it)
            }
        val op =
            parseWorksheetFilterOp(opText) ?: return invalidArgument("Unknown filter op '$opText'")
        val value =
            obj.optionalStringField("value", path).getOrElse {
                return Arg.Err(it)
            }
        val secondValue =
            obj.optionalStringField("second_value", path).getOrElse {
                return Arg.Err(it)
            }
        val includedKeys =
            obj.optionalStringListField("included_keys", path).getOrElse {
                return Arg.Err(it)
            }
        filters +=
            WorksheetFilter(
                id = "filter-$index",
                column = column,
                label = displayColumnLabel(column),
                op = op,
                value = value.orEmpty(),
                secondValue = secondValue,
                includedKeys = includedKeys?.toSet().orEmpty(),
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
        val path = "sorts[$index]"
        requireKnownKeys(obj, path, "column", "direction").getOrElse {
            return Arg.Err(it)
        }
        val column =
            requireColumn(obj, columns, path).getOrElse {
                return Arg.Err(it)
            }
        val dir =
            parseSortDir(obj, path).getOrElse {
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
    val column =
        obj.requireNonBlankString("column", path).getOrElse {
            return Arg.Err(it)
        }
    if (column !in columns) return unknownColumn(column, columns)
    return Arg.Ok(column)
}

internal fun parseSortDir(obj: JsonObject, path: String): Arg<SortDir> {
    val text =
        obj.optionalStringField("direction", path).getOrElse {
            return Arg.Err(it)
        } ?: return Arg.Ok(SortDir.Asc)
    return when (normalize(text)) {
        "asc",
        "ascending" -> Arg.Ok(SortDir.Asc)
        "desc",
        "descending" -> Arg.Ok(SortDir.Desc)
        else -> invalidArgument("Unknown sort direction '$text'")
    }
}

internal fun requireKnownKeys(obj: JsonObject, path: String, vararg allowed: String): Arg<Unit> {
    val extra = obj.keys.filter { it !in allowed }.sorted()
    if (extra.isEmpty()) return Arg.Ok(Unit)
    return invalidArgument("unknown field '$path.${extra.first()}'")
}

internal fun JsonObject.optionalStringField(name: String, path: String): Arg<String?> {
    val element = this[name] ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        return invalidArgument("$path.$name must be a string")
    }
    return Arg.Ok(primitive.content)
}

internal fun JsonObject.optionalBooleanField(name: String, path: String): Arg<Boolean?> {
    val element = this[name] ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.booleanOrNull
    if (value == null) {
        return invalidArgument("$path.$name must be a boolean")
    }
    return Arg.Ok(value)
}

internal fun JsonObject.optionalIntField(name: String, path: String): Arg<Int?> {
    val element = this[name] ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val primitive = element as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.intOrNull
    if (value == null) {
        return invalidArgument("$path.$name must be an integer")
    }
    return Arg.Ok(value)
}

internal fun JsonObject.optionalStringListField(name: String, path: String): Arg<List<String>?> {
    val element = this[name] ?: return Arg.Ok(null)
    if (element is JsonNull) return Arg.Ok(null)
    val array = element as? JsonArray ?: return invalidArgument("$path.$name must be an array")
    val values = mutableListOf<String>()
    for ((index, item) in array.withIndex()) {
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            return invalidArgument("$path.$name[$index] must be a string")
        }
        values += primitive.content
    }
    return Arg.Ok(values)
}

private fun JsonObject.requireNonBlankString(name: String, path: String): Arg<String> {
    val value =
        optionalStringField(name, path).getOrElse {
            return Arg.Err(it)
        }
    if (value.isNullOrBlank()) return invalidArgument("$path.$name is required")
    return Arg.Ok(value)
}

private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun invalidArgument(message: String): Arg<Nothing> =
    Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, message))

private fun unknownColumn(column: String, knownColumns: Set<String>): Arg<Nothing> {
    val known = knownColumns.sorted().take(8).joinToString(", ")
    return invalidArgument("Unknown column '$column'. Known columns: $known")
}
