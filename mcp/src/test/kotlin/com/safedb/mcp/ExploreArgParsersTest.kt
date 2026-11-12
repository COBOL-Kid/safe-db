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
import com.safedb.explore.SortDir
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.WorksheetFilter
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExploreArgParsersTest {
    private val columns = setOf("region", "amount", "created")

    @Test
    fun parseDimensionsRejectsMalformedShapes() {
        val notAString =
            assertIs<Arg.Err>(
                parseDimensions(JsonArray(listOf(JsonPrimitive(5))), columns, "row_dimensions")
            )
        assertEquals("row_dimensions[0] must be a string or object", notAString.message())

        val missingColumn =
            assertIs<Arg.Err>(
                parseDimensions(objects("""{"grouping":"exact"}"""), columns, "row_dimensions")
            )
        assertEquals("row_dimensions[0].column is required", missingColumn.message())

        val unknownColumn =
            assertIs<Arg.Err>(
                parseDimensions(objects("""{"column":"nope"}"""), columns, "row_dimensions")
            )
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )

        val badSortMode =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"created","sort_mode":"random"}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals("Unknown sort_mode 'random'", badSortMode.message())

        val missingBinSize =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"amount","grouping":{"bin_start":"0"}}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals("grouping objects require bin_size", missingBinSize.message())

        val badGrouping =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"amount","grouping":5}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals("grouping must be a string or object", badGrouping.message())
    }

    @Test
    fun parseDimensionsSupportsStringAndObjectAliases() {
        val dimensions =
            assertIs<Arg.Ok<List<PivotDimension>>>(
                    parseDimensions(
                        JsonArray(
                            listOf(
                                JsonPrimitive("region"),
                                Json.parseToJsonElement(
                                    """{"column":"created","label":"Created","grouping":"month","sort_mode":"label_asc"}"""
                                ),
                            )
                        ),
                        columns,
                        "row_dimensions",
                    )
                )
                .value

        assertEquals(listOf("region", "created"), dimensions.map { it.column })
        assertEquals("region", dimensions[0].label)
        assertEquals("Created", dimensions[1].label)
        assertEquals(PivotGrouping.Date(DateGroupUnit.Month), dimensions[1].grouping)
        assertEquals(DimensionSortMode.LabelAscending, dimensions[1].sortMode)
    }

    @Test
    fun parseDimensionsSupportsNumericBins() {
        val dimension =
            assertIs<Arg.Ok<List<PivotDimension>>>(
                    parseDimensions(
                        objects(
                            """{"column":"amount","grouping":{"bin_size":"25","bin_start":"0"}}"""
                        ),
                        columns,
                        "row_dimensions",
                    )
                )
                .value
                .single()

        assertEquals(PivotGrouping.NumberBin(size = "25", start = "0"), dimension.grouping)
    }

    @Test
    fun parseMeasuresRejectsMalformedShapes() {
        val notAnObject =
            assertIs<Arg.Err>(parseMeasures(JsonArray(listOf(JsonPrimitive("x"))), columns))
        assertEquals("measures[0] must be an object", notAnObject.message())

        val missingAlias = assertIs<Arg.Err>(parseMeasures(objects("""{"fn":"count"}"""), columns))
        assertEquals("measures[0].alias is required", missingAlias.message())

        val missingFn = assertIs<Arg.Err>(parseMeasures(objects("""{"alias":"x"}"""), columns))
        assertEquals("measures[0].fn is required", missingFn.message())

        val unknownFn =
            assertIs<Arg.Err>(parseMeasures(objects("""{"alias":"x","fn":"median"}"""), columns))
        assertEquals("Unknown measure fn 'median'", unknownFn.message())

        val unknownSourceColumn =
            assertIs<Arg.Err>(
                parseMeasures(
                    objects("""{"alias":"x","fn":"sum","source_column":"nope"}"""),
                    columns,
                )
            )
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownSourceColumn.message(),
        )

        val badShowAs =
            assertIs<Arg.Err>(
                parseMeasures(objects("""{"alias":"x","fn":"count","show_as":5}"""), columns)
            )
        assertEquals("show_as must be a string", badShowAs.message())

        val unknownShowAs =
            assertIs<Arg.Err>(
                parseMeasures(
                    objects("""{"alias":"x","fn":"count","show_as":"bogus"}"""),
                    columns,
                )
            )
        assertEquals("Unknown show_as 'bogus'", unknownShowAs.message())
    }

    @Test
    fun parseMeasuresSupportsAliasesAndSourceColumns() {
        val measures =
            assertIs<Arg.Ok<List<PivotMeasure>>>(
                    parseMeasures(
                        objects(
                            """{"alias":"count","fn":"count"}""",
                            """{"alias":"total","fn":"sum","source_column":"amount"}""",
                        ),
                        columns,
                    )
                )
                .value

        assertEquals(listOf("count", "total"), measures.map { it.alias })
        assertEquals(MeasureFn.Count, measures[0].fn)
        assertEquals(MeasureFn.Sum, measures[1].fn)
        assertEquals("amount", measures[1].sourceColumn)
    }

    @Test
    fun parsePivotFiltersRejectsMalformedShapes() {
        val notAnObject =
            assertIs<Arg.Err>(parsePivotFilters(JsonArray(listOf(JsonPrimitive("x"))), columns))
        assertEquals("filters[0] must be an object", notAnObject.message())

        val missingColumn =
            assertIs<Arg.Err>(parsePivotFilters(objects("""{"op":"equals"}"""), columns))
        assertEquals("filters[0].column is required", missingColumn.message())

        val unknownColumn =
            assertIs<Arg.Err>(
                parsePivotFilters(objects("""{"column":"nope","op":"equals"}"""), columns)
            )
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )

        val missingOp =
            assertIs<Arg.Err>(parsePivotFilters(objects("""{"column":"region"}"""), columns))
        assertEquals("filters[0].op is required", missingOp.message())

        val missingMeasureAlias =
            assertIs<Arg.Err>(
                parsePivotFilters(objects("""{"column":"region","op":"gt"}"""), columns)
            )
        assertEquals("filters[0].measure_alias is required", missingMeasureAlias.message())

        val unknownOp =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects("""{"column":"region","op":"regex","measure_alias":"count"}"""),
                    columns,
                )
            )
        assertEquals("Unknown filter op 'regex'", unknownOp.message())
    }

    @Test
    fun parsePivotFiltersSupportsMembersLabelAndValueFilters() {
        val filters =
            assertIs<Arg.Ok<List<PivotFilter>>>(
                    parsePivotFilters(
                        objects(
                            """{"column":"region","op":"members","included_keys":["TextCell:east"]}""",
                            """{"column":"region","op":"contains","value":"ea"}""",
                            """{"column":"amount","op":"between","measure_alias":"count","value":"1","second_value":"10"}""",
                        ),
                        columns,
                    )
                )
                .value

        assertEquals(3, filters.size)
        val members = assertIs<PivotFilter.Members>(filters[0])
        assertEquals(setOf("TextCell:east"), members.includedKeys)
        val label = assertIs<PivotFilter.Label>(filters[1])
        assertEquals(LabelFilterOp.Contains, label.op)
        assertEquals("ea", label.value)
        val value = assertIs<PivotFilter.Value>(filters[2])
        assertEquals(ValueFilterOp.Between, value.op)
        assertEquals("count", value.measureAlias)
        assertEquals("10", value.secondValue)
    }

    @Test
    fun parseExploreSortRejectsMalformedShapes() {
        val missingTarget =
            assertIs<Arg.Err>(parseExploreSort(jsonObject("""{"direction":"asc"}"""), columns))
        assertEquals("sort requires column or measure_alias", missingTarget.message())

        val unknownColumn =
            assertIs<Arg.Err>(parseExploreSort(jsonObject("""{"column":"nope"}"""), columns))
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )

        val badDirection =
            assertIs<Arg.Err>(
                parseExploreSort(
                    jsonObject("""{"column":"region","direction":"sideways"}"""),
                    columns,
                )
            )
        assertEquals("Unknown sort direction 'sideways'", badDirection.message())
    }

    @Test
    fun parseExploreSortSupportsColumnAndMeasureTargets() {
        val byColumn =
            assertIs<Arg.Ok<ExploreSort>>(
                    parseExploreSort(
                        jsonObject("""{"column":"region","direction":"desc"}"""),
                        columns,
                    )
                )
                .value
        assertEquals(ExploreSortTarget.Dimension("region"), byColumn.target)
        assertEquals(SortDir.Desc, byColumn.dir)

        val byMeasure =
            assertIs<Arg.Ok<ExploreSort>>(
                    parseExploreSort(jsonObject("""{"measure_alias":"count"}"""), columns)
                )
                .value
        assertEquals(ExploreSortTarget.Measure("count"), byMeasure.target)
        assertEquals(SortDir.Asc, byMeasure.dir)
    }

    @Test
    fun parseWorksheetFiltersRejectsMalformedShapes() {
        val notAnObject =
            assertIs<Arg.Err>(parseWorksheetFilters(JsonArray(listOf(JsonPrimitive("x"))), columns))
        assertEquals("filters[0] must be an object", notAnObject.message())

        val missingColumn =
            assertIs<Arg.Err>(parseWorksheetFilters(objects("""{"op":"equals"}"""), columns))
        assertEquals("filters[0].column is required", missingColumn.message())

        val unknownColumn =
            assertIs<Arg.Err>(
                parseWorksheetFilters(objects("""{"column":"nope","op":"equals"}"""), columns)
            )
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )

        val missingOp =
            assertIs<Arg.Err>(parseWorksheetFilters(objects("""{"column":"region"}"""), columns))
        assertEquals("filters[0].op is required", missingOp.message())

        val unknownOp =
            assertIs<Arg.Err>(
                parseWorksheetFilters(objects("""{"column":"region","op":"regex"}"""), columns)
            )
        assertEquals("Unknown filter op 'regex'", unknownOp.message())
    }

    @Test
    fun parseWorksheetFiltersSupportsOpsAndMembers() {
        val filters =
            assertIs<Arg.Ok<List<WorksheetFilter>>>(
                    parseWorksheetFilters(
                        objects(
                            """{"column":"region","op":"members","included_keys":["TextCell:east"]}""",
                            """{"column":"region","op":"eq","value":"east"}""",
                            """{"column":"amount","op":"between","value":"5","second_value":"25"}""",
                            """{"column":"amount","op":"is_null"}""",
                        ),
                        columns,
                    )
                )
                .value

        assertEquals(4, filters.size)
        assertEquals(WorksheetFilterOp.Members, filters[0].op)
        assertEquals(setOf("TextCell:east"), filters[0].includedKeys)
        assertEquals(WorksheetFilterOp.Equals, filters[1].op)
        assertEquals("east", filters[1].value)
        assertEquals(WorksheetFilterOp.Between, filters[2].op)
        assertEquals("25", filters[2].secondValue)
        assertEquals(WorksheetFilterOp.IsNull, filters[3].op)
    }

    @Test
    fun parseWorksheetSortsRejectsMalformedShapes() {
        val notAnObject =
            assertIs<Arg.Err>(parseWorksheetSorts(JsonArray(listOf(JsonPrimitive("x"))), columns))
        assertEquals("sorts[0] must be an object", notAnObject.message())

        val missingColumn =
            assertIs<Arg.Err>(parseWorksheetSorts(objects("""{"direction":"asc"}"""), columns))
        assertEquals("sorts[0].column is required", missingColumn.message())

        val unknownColumn =
            assertIs<Arg.Err>(parseWorksheetSorts(objects("""{"column":"nope"}"""), columns))
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )

        val badDirection =
            assertIs<Arg.Err>(
                parseWorksheetSorts(
                    objects("""{"column":"region","direction":"sideways"}"""),
                    columns,
                )
            )
        assertEquals("Unknown sort direction 'sideways'", badDirection.message())
    }

    @Test
    fun parseWorksheetSortsSupportsColumnsAndDirections() {
        val sorts =
            assertIs<Arg.Ok<List<WorksheetSort>>>(
                    parseWorksheetSorts(
                        objects(
                            """{"column":"region","direction":"asc"}""",
                            """{"column":"amount","direction":"desc"}""",
                        ),
                        columns,
                    )
                )
                .value

        assertEquals(
            listOf(
                WorksheetValueRef.Column("region") to SortDir.Asc,
                WorksheetValueRef.Column("amount") to SortDir.Desc,
            ),
            sorts.map { it.target to it.dir },
        )
    }

    @Test
    fun parseColumnProjectionRejectsMalformedShapes() {
        val notAString =
            assertIs<Arg.Err>(parseColumnProjection(JsonArray(listOf(JsonPrimitive(5))), columns))
        assertEquals("columns[0] must be a string", notAString.message())

        val unknownColumn = assertIs<Arg.Err>(parseColumnProjection(strings("nope"), columns))
        assertEquals(
            "Unknown column 'nope'. Known columns: amount, created, region",
            unknownColumn.message(),
        )
    }

    @Test
    fun parseColumnProjectionSupportsColumnNames() {
        val projection =
            assertIs<Arg.Ok<List<String>>>(
                    parseColumnProjection(strings("amount", "region"), columns)
                )
                .value

        assertEquals(listOf("amount", "region"), projection)
    }

    @Test
    fun nestedObjectsRejectUnknownKeysAndWrongTypes() {
        val unknownDimensionKey =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"region","bogus":true}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals("unknown field 'row_dimensions[0].bogus'", unknownDimensionKey.message())

        val unknownGroupingKey =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"amount","grouping":{"bin_size":"25","unit":"n"}}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals(
            "unknown field 'row_dimensions[0].grouping.unit'",
            unknownGroupingKey.message(),
        )

        val showSubtotalsString =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"region","show_subtotals":"true"}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals(
            "row_dimensions[0].show_subtotals must be a boolean",
            showSubtotalsString.message(),
        )

        val labelNumber =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"region","label":42}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals("row_dimensions[0].label must be a string", labelNumber.message())

        val binStartNumber =
            assertIs<Arg.Err>(
                parseDimensions(
                    objects("""{"column":"amount","grouping":{"bin_size":"25","bin_start":0}}"""),
                    columns,
                    "row_dimensions",
                )
            )
        assertEquals(
            "row_dimensions[0].grouping.bin_start must be a string",
            binStartNumber.message(),
        )

        val unknownMeasureKey =
            assertIs<Arg.Err>(
                parseMeasures(objects("""{"alias":"x","fn":"count","bogus":1}"""), columns)
            )
        assertEquals("unknown field 'measures[0].bogus'", unknownMeasureKey.message())

        val measureLabelNumber =
            assertIs<Arg.Err>(
                parseMeasures(objects("""{"alias":"x","fn":"count","label":42}"""), columns)
            )
        assertEquals("measures[0].label must be a string", measureLabelNumber.message())

        val countString =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects(
                        """{"column":"region","op":"top","measure_alias":"count","count":"5"}"""
                    ),
                    columns,
                )
            )
        assertEquals("filters[0].count must be an integer", countString.message())

        val includedKeysNumber =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects("""{"column":"region","op":"members","included_keys":[1]}"""),
                    columns,
                )
            )
        assertEquals("filters[0].included_keys[0] must be a string", includedKeysNumber.message())

        val extraMembersValue =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects("""{"column":"region","op":"members","value":"x"}"""),
                    columns,
                )
            )
        assertEquals("unknown field 'filters[0].value'", extraMembersValue.message())

        val extraLabelCount =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects("""{"column":"region","op":"equals","count":5}"""),
                    columns,
                )
            )
        assertEquals("unknown field 'filters[0].count'", extraLabelCount.message())

        val extraValueIncludedKeys =
            assertIs<Arg.Err>(
                parsePivotFilters(
                    objects(
                        """{"column":"amount","op":"gt","measure_alias":"count","included_keys":[]}"""
                    ),
                    columns,
                )
            )
        assertEquals("unknown field 'filters[0].included_keys'", extraValueIncludedKeys.message())

        val bothSortTargets =
            assertIs<Arg.Err>(
                parseExploreSort(
                    jsonObject("""{"column":"region","measure_alias":"count"}"""),
                    columns,
                )
            )
        assertEquals(
            "sort cannot include both column and measure_alias",
            bothSortTargets.message(),
        )

        val unknownSortKey =
            assertIs<Arg.Err>(
                parseExploreSort(jsonObject("""{"column":"region","bogus":1}"""), columns)
            )
        assertEquals("unknown field 'sort.bogus'", unknownSortKey.message())

        val unknownWorksheetFilterKey =
            assertIs<Arg.Err>(
                parseWorksheetFilters(
                    objects("""{"column":"region","op":"eq","bogus":true}"""),
                    columns,
                )
            )
        assertEquals("unknown field 'filters[0].bogus'", unknownWorksheetFilterKey.message())

        val unknownWorksheetSortKey =
            assertIs<Arg.Err>(
                parseWorksheetSorts(objects("""{"column":"region","bogus":1}"""), columns)
            )
        assertEquals("unknown field 'sorts[0].bogus'", unknownWorksheetSortKey.message())
    }

    @Test
    fun optionalArrayRejectsNonArrayCollections() {
        val request =
            CallToolRequest(
                CallToolRequestParams(
                    name = "filter_result_rows",
                    arguments =
                        buildJsonObject {
                            put("filters", JsonPrimitive("x"))
                            put("sorts", JsonPrimitive("x"))
                            put("columns", JsonPrimitive("x"))
                        },
                )
            )

        for (name in listOf("filters", "sorts", "columns")) {
            val error = assertIs<Arg.Err>(optionalArray(request, name))
            assertEquals("$name must be an array", error.message())
        }
    }
}

private fun objects(vararg values: String): JsonArray =
    JsonArray(values.map { Json.parseToJsonElement(it) })

private fun strings(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

private fun jsonObject(value: String) = Json.parseToJsonElement(value).jsonObject

private fun Arg.Err.message(): String {
    val text = (result.content.single() as TextContent).text
    return Json.parseToJsonElement(text).jsonObject.getValue("message").jsonPrimitive.content
}
