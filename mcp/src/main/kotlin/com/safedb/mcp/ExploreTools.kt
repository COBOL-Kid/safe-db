package com.safedb.mcp

import com.safedb.explore.DateGroupUnit
import com.safedb.explore.DimensionSortMode
import com.safedb.explore.ExploreConfig
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
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetFilter
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.explore.displayColumnLabel
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal fun registerExploreTools(server: Server, resultStore: ResultStore) {
    server.addTool(
        name = "pivot_result",
        description =
            "Pivot the in-memory sample of a previous run_query by result_id. With no " +
                "dimensions or measures, uses the default pivot (first non-identifier column " +
                "grouped with a row count). row_dimensions and column_dimensions accept column " +
                "names or objects with column, label, grouping (exact, year, quarter, month, " +
                "iso_week, day, or {bin_size, bin_start}), sort_mode, sort_measure_alias, and " +
                "show_subtotals. measures accept alias, fn, source_column, label, formula, and " +
                "show_as. filters support members, label, and value comparison objects. sort " +
                "accepts column or measure_alias with asc/desc. Returns pivoted columns, " +
                "row_count, pivot_truncated, warnings, and at most $GET_RESULT_ROWS_MAX rows " +
                "capped at $MCP_PAYLOAD_MAX_BYTES bytes. Unknown or expired result_id returns " +
                "not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "result_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "result_id from a successful run_query receipt")
                            },
                        )
                        put(
                            "row_dimensions",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Row grouping fields. Each item is a column name or an object.",
                                )
                            },
                        )
                        put(
                            "column_dimensions",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Column grouping fields. Each item is a column name or an object.",
                                )
                            },
                        )
                        put(
                            "measures",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Measure objects with alias and fn, plus optional " +
                                        "source_column, label, formula, and show_as.",
                                )
                            },
                        )
                        put(
                            "filters",
                            buildJsonObject {
                                put("type", "array")
                                put("description", "Pivot filter objects.")
                            },
                        )
                        put(
                            "sort",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Sort by column or measure_alias with direction asc or desc.",
                                )
                            },
                        )
                        put(
                            "show_row_totals",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "Show a total column. Default true.")
                            },
                        )
                        put(
                            "show_column_totals",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "Show a grand total row. Default true.")
                            },
                        )
                        put(
                            "show_subtotals",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "Show subtotal rows and columns. Default true.")
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("pivot_result failed") { handlePivotResult(resultStore, request) }
    }

    server.addTool(
        name = "filter_result_rows",
        description =
            "Filter, sort, project, and page the in-memory sample of a previous run_query by " +
                "result_id. filters accept column plus op (equals, not_equals, contains, " +
                "starts_with, ends_with, greater_than, greater_than_or_equal, less_than, " +
                "less_than_or_equal, between, is_null, is_not_null, members) with value, " +
                "second_value, or included_keys. sorts accept column and asc/desc. columns " +
                "limits the projected fields. offset defaults to 0 (negative clamps to 0); " +
                "limit defaults to $GET_RESULT_ROWS_MAX and caps at $GET_RESULT_ROWS_MAX. " +
                "matched_row_count reports rows after filtering; sample_row_count reports the " +
                "stored sample size. Pages stop around $MCP_PAYLOAD_MAX_BYTES bytes. Unknown " +
                "or expired result_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "result_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "result_id from a successful run_query receipt")
                            },
                        )
                        put(
                            "filters",
                            buildJsonObject {
                                put("type", "array")
                                put("description", "Worksheet filter objects.")
                            },
                        )
                        put(
                            "sorts",
                            buildJsonObject {
                                put("type", "array")
                                put("description", "Sort objects with column and direction.")
                            },
                        )
                        put(
                            "columns",
                            buildJsonObject {
                                put("type", "array")
                                put("description", "Optional column name projection.")
                            },
                        )
                        put(
                            "offset",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "Row offset. Default 0.")
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Max rows (1–$GET_RESULT_ROWS_MAX). Default " +
                                        "$GET_RESULT_ROWS_MAX. 0 or negative is " +
                                        "invalid_arguments.",
                                )
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("filter_result_rows failed") { handleFilterResultRows(resultStore, request) }
    }
}

private suspend fun handlePivotResult(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(
            request,
            "result_id",
            "row_dimensions",
            "column_dimensions",
            "measures",
            "filters",
            "sort",
            "show_row_totals",
            "show_column_totals",
            "show_subtotals",
        )
        .getOrElse {
            return it
        }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val rowDimensionsArg =
        optionalArray(request, "row_dimensions").getOrElse {
            return it
        }
    val columnDimensionsArg =
        optionalArray(request, "column_dimensions").getOrElse {
            return it
        }
    val measuresArg =
        optionalArray(request, "measures").getOrElse {
            return it
        }
    val filtersArg =
        optionalArray(request, "filters").getOrElse {
            return it
        }
    val sortArg =
        optionalObject(request, "sort").getOrElse {
            return it
        }
    val showRowTotals =
        optionalBoolean(request, "show_row_totals").getOrElse {
            return it
        }
    val showColumnTotals =
        optionalBoolean(request, "show_column_totals").getOrElse {
            return it
        }
    val showSubtotals =
        optionalBoolean(request, "show_subtotals").getOrElse {
            return it
        }
    val entry =
        resultStore.getEntry(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val columns = entry.result.columns.map { it.name }.toSet()

    val rowDimensions = rowDimensionsArg?.let {
        when (val parsed = parseDimensions(it, columns, "row_dimensions")) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }
    val columnDimensions = columnDimensionsArg?.let {
        when (val parsed = parseDimensions(it, columns, "column_dimensions")) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }
    val measures = measuresArg?.let {
        when (val parsed = parseMeasures(it, columns)) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }
    val filters = filtersArg?.let {
        when (val parsed = parsePivotFilters(it, columns)) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }
    val sort = sortArg?.let {
        when (val parsed = parseExploreSort(it, columns)) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }

    var config =
        if (rowDimensionsArg == null && columnDimensionsArg == null && measuresArg == null) {
            ExploreConfig.defaultFor(entry.result, entry.tables)
        } else {
            ExploreConfig()
        }
    rowDimensions?.let { config = config.copy(rowDimensions = it) }
    columnDimensions?.let { config = config.copy(columnDimensions = it) }
    measures?.let { config = config.copy(measures = it) }
    filters?.let { config = config.copy(filters = it) }
    sort?.let { config = config.copy(sort = it) }
    showRowTotals?.let { config = config.copy(showRowTotals = it) }
    showColumnTotals?.let { config = config.copy(showColumnTotals = it) }
    showSubtotals?.let { config = config.copy(showSubtotals = it) }

    val preview = applyExplore(entry.result, config)
    val slice =
        sliceRows(
            preview.result,
            offset = 0,
            limit = GET_RESULT_ROWS_MAX,
            maxBytes = MCP_PAYLOAD_MAX_BYTES,
        )
    val receipt =
        PivotResultReceipt(
            resultId = resultId,
            columns = preview.result.columns,
            rowCount = preview.result.rowCount,
            sampleCapped = entry.result.truncated,
            pivotTruncated =
                slice.moreRowsRemain ||
                    slice.cellsTruncated ||
                    preview.layout.overflowMessage != null,
            warnings = preview.warnings,
            rows = slice.rows,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
}

private suspend fun handleFilterResultRows(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "result_id", "filters", "sorts", "columns", "offset", "limit")
        .getOrElse {
            return it
        }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val filtersArg =
        optionalArray(request, "filters").getOrElse {
            return it
        }
    val sortsArg =
        optionalArray(request, "sorts").getOrElse {
            return it
        }
    val columnsArg =
        optionalArray(request, "columns").getOrElse {
            return it
        }
    val offsetArg =
        optionalInt(request, "offset").getOrElse {
            return it
        } ?: 0
    val limitArg =
        optionalInt(request, "limit").getOrElse {
            return it
        }
    if (limitArg != null && limitArg <= 0) {
        return toolError(ERROR_INVALID_ARGUMENTS, "limit must be 1 or more")
    }
    val entry =
        resultStore.getEntry(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val knownColumns = entry.result.columns.map { it.name }.toSet()

    val filters =
        filtersArg?.let {
            when (val parsed = parseWorksheetFilters(it, knownColumns)) {
                is Arg.Ok -> parsed.value
                is Arg.Err -> return parsed.result
            }
        } ?: emptyList()
    val sorts =
        sortsArg?.let {
            when (val parsed = parseWorksheetSorts(it, knownColumns)) {
                is Arg.Ok -> parsed.value
                is Arg.Err -> return parsed.result
            }
        } ?: emptyList()
    val projection = columnsArg?.let {
        when (val parsed = parseColumnProjection(it, knownColumns)) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    }

    val worksheet =
        applyWorksheet(
            entry.result,
            WorksheetConfig(filters = filters, sorts = sorts),
            entry.tables,
        )
    val detailIndexes =
        worksheet.rows
            .filter { it.kind == WorksheetRowKind.Detail }
            .mapNotNull { it.sourceRowIndex }
    val columnIndexes =
        projection?.map { name -> entry.result.columns.indexOfFirst { it.name == name } }
            ?: entry.result.columns.indices.toList()
    val projectedColumns = columnIndexes.map { entry.result.columns[it] }
    val projectedRows = detailIndexes.map { rowIndex ->
        val source = entry.result.rows.getOrNull(rowIndex)
        columnIndexes.map { columnIndex -> source?.getOrNull(columnIndex) ?: ResultCell.Null }
    }
    val projected =
        QueryResult(
            columns = projectedColumns,
            rows = projectedRows,
            rowCount = projectedRows.size,
            truncated = entry.result.truncated,
            warnings = worksheet.warnings,
        )
    val offset = offsetArg.coerceAtLeast(0)
    val limit = (limitArg ?: GET_RESULT_ROWS_MAX).coerceAtMost(GET_RESULT_ROWS_MAX)
    val slice = sliceRows(projected, offset, limit, MCP_PAYLOAD_MAX_BYTES)
    val page =
        FilteredResultRowsPage(
            resultId = resultId,
            offset = offset,
            matchedRowCount = projected.rowCount,
            sampleRowCount = entry.result.rowCount,
            sampleCapped = entry.result.truncated,
            pageTruncated = slice.moreRowsRemain,
            nextOffset = slice.nextOffset,
            warnings = entry.result.warnings + worksheet.warnings,
            rows = slice.rows,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(page))))
}

private fun parseDimensions(
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
                if (column !in columns) return unknownColumn(column)
                dimensions += PivotDimension(column = column)
            }
            is JsonObject -> {
                val column =
                    element.stringField("column")
                        ?: return invalidArgument("$argument[$index].column is required")
                if (column !in columns) return unknownColumn(column)
                val label = element.stringField("label") ?: displayColumnLabel(column)
                val grouping =
                    when (val parsed = parseGrouping(element["grouping"])) {
                        is Arg.Ok -> parsed.value
                        is Arg.Err -> return parsed
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

private fun parseMeasures(array: JsonArray, columns: Set<String>): Arg<List<PivotMeasure>> {
    val measures = mutableListOf<PivotMeasure>()
    for ((index, element) in array.withIndex()) {
        val obj =
            element as? JsonObject ?: return invalidArgument("measures[$index] must be an object")
        val alias =
            obj.stringField("alias") ?: return invalidArgument("measures[$index].alias is required")
        val fnText =
            obj.stringField("fn") ?: return invalidArgument("measures[$index].fn is required")
        val fn = parseMeasureFn(fnText) ?: return invalidArgument("Unknown measure fn '$fnText'")
        val sourceColumn = obj.stringField("source_column")
        if (sourceColumn != null && sourceColumn !in columns) return unknownColumn(sourceColumn)
        val showAs =
            when (val parsed = parseShowAs(obj["show_as"])) {
                is Arg.Ok -> parsed.value
                is Arg.Err -> return parsed
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

private fun parsePivotFilters(array: JsonArray, columns: Set<String>): Arg<List<PivotFilter>> {
    val filters = mutableListOf<PivotFilter>()
    for ((index, element) in array.withIndex()) {
        val obj =
            element as? JsonObject ?: return invalidArgument("filters[$index] must be an object")
        val column =
            obj.stringField("column")
                ?: return invalidArgument("filters[$index].column is required")
        if (column !in columns) return unknownColumn(column)
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
                "equals" ->
                    PivotFilter.Label(
                        id = id,
                        column = column,
                        label = label,
                        op = LabelFilterOp.Equals,
                        value = obj.stringField("value").orEmpty(),
                    )
                "contains" ->
                    PivotFilter.Label(
                        id = id,
                        column = column,
                        label = label,
                        op = LabelFilterOp.Contains,
                        value = obj.stringField("value").orEmpty(),
                    )
                "startswith" ->
                    PivotFilter.Label(
                        id = id,
                        column = column,
                        label = label,
                        op = LabelFilterOp.StartsWith,
                        value = obj.stringField("value").orEmpty(),
                    )
                "endswith" ->
                    PivotFilter.Label(
                        id = id,
                        column = column,
                        label = label,
                        op = LabelFilterOp.EndsWith,
                        value = obj.stringField("value").orEmpty(),
                    )
                else -> {
                    val valueOp =
                        parseValueFilterOp(op) ?: return invalidArgument("Unknown filter op '$op'")
                    val measureAlias =
                        obj.stringField("measure_alias")
                            ?: return invalidArgument("filters[$index].measure_alias is required")
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
        filters += filter
    }
    return Arg.Ok(filters)
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

private fun parseExploreSort(obj: JsonObject, columns: Set<String>): Arg<ExploreSort> {
    val column = obj.stringField("column")
    val measureAlias = obj.stringField("measure_alias")
    val target =
        when {
            column != null -> {
                if (column !in columns) return unknownColumn(column)
                ExploreSortTarget.Dimension(column)
            }
            measureAlias != null -> ExploreSortTarget.Measure(measureAlias)
            else -> return invalidArgument("sort requires column or measure_alias")
        }
    val dir =
        obj.stringField("direction")?.let {
            when (normalize(it)) {
                "asc",
                "ascending" -> SortDir.Asc
                "desc",
                "descending" -> SortDir.Desc
                else -> return invalidArgument("Unknown sort direction '$it'")
            }
        } ?: SortDir.Asc
    return Arg.Ok(ExploreSort(target = target, dir = dir))
}

private fun parseWorksheetFilters(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<WorksheetFilter>> {
    val filters = mutableListOf<WorksheetFilter>()
    for ((index, element) in array.withIndex()) {
        val obj =
            element as? JsonObject ?: return invalidArgument("filters[$index] must be an object")
        val column =
            obj.stringField("column")
                ?: return invalidArgument("filters[$index].column is required")
        if (column !in columns) return unknownColumn(column)
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

private fun parseWorksheetSorts(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<WorksheetSort>> {
    val sorts = mutableListOf<WorksheetSort>()
    for ((index, element) in array.withIndex()) {
        val obj =
            element as? JsonObject ?: return invalidArgument("sorts[$index] must be an object")
        val column =
            obj.stringField("column") ?: return invalidArgument("sorts[$index].column is required")
        if (column !in columns) return unknownColumn(column)
        val dir =
            obj.stringField("direction")?.let {
                when (normalize(it)) {
                    "asc",
                    "ascending" -> SortDir.Asc
                    "desc",
                    "descending" -> SortDir.Desc
                    else -> return invalidArgument("Unknown sort direction '$it'")
                }
            } ?: SortDir.Asc
        sorts += WorksheetSort(WorksheetValueRef.Column(column), dir)
    }
    return Arg.Ok(sorts)
}

private fun parseColumnProjection(
    array: JsonArray,
    columns: Set<String>,
): Arg<List<String>> {
    val projection = mutableListOf<String>()
    for ((index, element) in array.withIndex()) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            return invalidArgument("columns[$index] must be a string")
        }
        if (primitive.content !in columns) return unknownColumn(primitive.content)
        projection += primitive.content
    }
    return Arg.Ok(projection)
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.stringList(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
    }

private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun invalidArgument(message: String): Arg<Nothing> =
    Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, message))

private fun unknownColumn(column: String): Arg<Nothing> =
    invalidArgument("Unknown column '$column'")

@Serializable
internal data class PivotResultReceipt(
    @SerialName("result_id") val resultId: String,
    val columns: List<ResultColumn>,
    @SerialName("row_count") val rowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    @SerialName("pivot_truncated") val pivotTruncated: Boolean,
    val warnings: List<String>,
    val rows: List<JsonObject>,
)

@Serializable
internal data class FilteredResultRowsPage(
    @SerialName("result_id") val resultId: String,
    val offset: Int,
    @SerialName("matched_row_count") val matchedRowCount: Int,
    @SerialName("sample_row_count") val sampleRowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    @SerialName("page_truncated") val pageTruncated: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("next_offset") val nextOffset: Int? = null,
    val warnings: List<String>,
    val rows: List<JsonObject>,
)
