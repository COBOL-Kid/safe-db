package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registerExploreTools(server: Server, resultStore: ResultStore) {
    server.addTool(
        name = "pivot_result",
        description =
            "Pivot the in-memory sample of a previous run_query by result_id. With no " +
                "dimensions or measures, uses the default pivot (first non-identifier column " +
                "grouped with a row count and a Total row). row_dimensions and " +
                "column_dimensions accept column " +
                "names or objects with column, label, grouping (exact, year, quarter, month, " +
                "iso_week, day, or {bin_size, bin_start}), sort_mode, sort_measure_alias, and " +
                "show_subtotals. measures accept alias, fn, source_column, label, formula, and " +
                "show_as; label names the output column (defaults to the function label) while " +
                "alias is for formulas and sort.measure_alias. Use receipt column names " +
                "verbatim (dialect casing). filters support members, label, and value " +
                "comparison objects. sort " +
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
                "limits the projected fields. Use receipt column names verbatim (dialect " +
                "casing); an unknown column reports the known columns. offset defaults to 0 " +
                "(negative clamps to 0); " +
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
        parseDimensions(it, columns, "row_dimensions").getOrElse {
            return it
        }
    }
    val columnDimensions = columnDimensionsArg?.let {
        parseDimensions(it, columns, "column_dimensions").getOrElse {
            return it
        }
    }
    val measures = measuresArg?.let {
        parseMeasures(it, columns).getOrElse {
            return it
        }
    }
    val filters = filtersArg?.let {
        parsePivotFilters(it, columns).getOrElse {
            return it
        }
    }
    val sort = sortArg?.let {
        parseExploreSort(it, columns).getOrElse {
            return it
        }
    }

    val defaults =
        if (rowDimensionsArg == null && columnDimensionsArg == null && measuresArg == null) {
            ExploreConfig.defaultFor(entry.result, entry.tables)
        } else {
            ExploreConfig()
        }
    val config =
        defaults.copy(
            rowDimensions = rowDimensions ?: defaults.rowDimensions,
            columnDimensions = columnDimensions ?: defaults.columnDimensions,
            measures = measures ?: defaults.measures,
            filters = filters ?: defaults.filters,
            sort = sort ?: defaults.sort,
            showRowTotals = showRowTotals ?: defaults.showRowTotals,
            showColumnTotals = showColumnTotals ?: defaults.showColumnTotals,
            showSubtotals = showSubtotals ?: defaults.showSubtotals,
        )

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
    return objectToolResult(receipt)
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
    return objectToolResult(page)
}

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
