package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.explore.withTableAwareLabels
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
                        put("result_id", resultIdProperty())
                        put(
                            "row_dimensions",
                            arrayProp(
                                "Row grouping fields. Each item is a column name or an object."
                            ),
                        )
                        put(
                            "column_dimensions",
                            arrayProp(
                                "Column grouping fields. Each item is a column name or an object."
                            ),
                        )
                        put(
                            "measures",
                            arrayProp(
                                "Measure objects with alias and fn, plus optional " +
                                    "source_column, label, formula, and show_as."
                            ),
                        )
                        put(
                            "filters",
                            arrayProp(
                                "Pivot filter objects. members uses included_keys (not value); " +
                                    "label/value comparisons use value."
                            ),
                        )
                        put(
                            "sort",
                            objectProp(
                                "Sort by column or measure_alias with direction asc or desc."
                            ),
                        )
                        put("show_row_totals", boolProp("Show a total column. Default true."))
                        put(
                            "show_column_totals",
                            boolProp("Show a grand total row. Default true."),
                        )
                        put(
                            "show_subtotals",
                            boolProp("Show subtotal rows and columns. Default true."),
                        )
                    },
                required = listOf("result_id"),
            ),
        outputSchema =
            objectOutputSchema(
                listOf(
                    "result_id",
                    "columns",
                    "row_count",
                    "pivot_truncated",
                    "warnings",
                    "rows",
                )
            ) {
                put("result_id", stringProp())
                put("columns", arrayProp())
                put("row_count", intProp())
                put("sample_capped", boolProp())
                put("pivot_truncated", boolProp())
                put("warnings", arrayProp())
                put("rows", arrayProp())
            },
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
                "less_than_or_equal, between, is_null, is_not_null, members). Comparison ops " +
                "use value and optional second_value (between). members uses included_keys, " +
                "not value. sorts accept column and asc/desc. columns " +
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
                        put("result_id", resultIdProperty())
                        put(
                            "filters",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Worksheet filter objects. Each item has column, op, and " +
                                        "the value field for that op. Comparison ops " +
                                        "(equals, contains, between, ...) use value and " +
                                        "optional second_value. members uses included_keys " +
                                        "(an array of strings), not value.",
                                )
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("column", stringProp("Result column name"))
                                                put(
                                                    "op",
                                                    stringProp(
                                                        "equals, not_equals, contains, " +
                                                            "starts_with, ends_with, " +
                                                            "greater_than, " +
                                                            "greater_than_or_equal, " +
                                                            "less_than, less_than_or_equal, " +
                                                            "between, is_null, is_not_null, " +
                                                            "or members"
                                                    ),
                                                )
                                                put(
                                                    "value",
                                                    stringProp(
                                                        "Comparison value. Not used when op is members."
                                                    ),
                                                )
                                                put(
                                                    "second_value",
                                                    stringProp("Upper bound when op is between."),
                                                )
                                                put(
                                                    "included_keys",
                                                    arrayProp(
                                                        "Member keys to keep when op is members. Do not send value."
                                                    ),
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("sorts", arrayProp("Sort objects with column and direction."))
                        put("columns", arrayProp("Optional column name projection."))
                        put("offset", intProp("Row offset. Default 0."))
                        put(
                            "limit",
                            boundedLimitProperty(
                                defaultLimit = GET_RESULT_ROWS_MAX,
                                maxLimit = GET_RESULT_ROWS_MAX,
                                noun = "rows",
                            ),
                        )
                    },
                required = listOf("result_id"),
            ),
        outputSchema =
            objectOutputSchema(
                listOf(
                    "result_id",
                    "offset",
                    "matched_row_count",
                    "sample_row_count",
                    "page_truncated",
                    "warnings",
                    "rows",
                )
            ) {
                put("result_id", stringProp())
                put("offset", intProp())
                put("matched_row_count", intProp())
                put("sample_row_count", intProp())
                put("sample_capped", boolProp())
                put("page_truncated", boolProp())
                put("next_offset", intProp())
                put("warnings", arrayProp())
                put("rows", arrayProp())
            },
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

    val preview =
        applyExplore(
            entry.result,
            config.withTableAwareLabels(entry.result.columns, entry.tables),
        )
    return pivotResultReceipt(resultId, entry, preview)
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
            parseWorksheetFilters(it, knownColumns).getOrElse {
                return it
            }
        } ?: emptyList()
    val sorts =
        sortsArg?.let {
            parseWorksheetSorts(it, knownColumns).getOrElse {
                return it
            }
        } ?: emptyList()
    val projection = columnsArg?.let {
        parseColumnProjection(it, knownColumns).getOrElse {
            return it
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
    val columnIndexesByName =
        entry.result.columns.mapIndexed { index, column -> column.name to index }.toMap()
    val columnIndexes =
        projection?.map { name ->
            columnIndexesByName[name]
                ?: error("Projection column '$name' is missing from the stored result")
        } ?: entry.result.columns.indices.toList()
    val offset = offsetArg.coerceAtLeast(0)
    val limit = (limitArg ?: GET_RESULT_ROWS_MAX).coerceAtMost(GET_RESULT_ROWS_MAX)
    val matchedRowCount = detailIndexes.size
    val projectedRows = detailIndexes.map { rowIndex ->
        val source =
            entry.result.rows.getOrNull(rowIndex)
                ?: error("Worksheet row $rowIndex is outside the stored result")
        columnIndexes.map { columnIndex -> source.getOrElse(columnIndex) { ResultCell.Null } }
    }
    val projected =
        QueryResult(
            columns = columnIndexes.map { entry.result.columns[it] },
            rows = projectedRows,
            rowCount = projectedRows.size,
            truncated = entry.result.truncated,
            warnings = worksheet.warnings,
        )
    val slice =
        sliceRows(projected, offset = offset, limit = limit, maxBytes = MCP_PAYLOAD_MAX_BYTES)
    val page =
        FilteredResultRowsPage(
            resultId = resultId,
            offset = offset,
            matchedRowCount = matchedRowCount,
            sampleRowCount = entry.result.rowCount,
            sampleCapped = entry.result.truncated,
            pageTruncated = slice.moreRowsRemain,
            nextOffset = slice.nextOffset,
            warnings = entry.result.warnings + worksheet.warnings,
            rows = slice.rows,
        )
    return objectToolResult(page)
}

internal fun pivotResultReceipt(
    resultId: String,
    entry: StoredResultEntry,
    preview: ExplorePreviewResult,
): CallToolResult {
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
