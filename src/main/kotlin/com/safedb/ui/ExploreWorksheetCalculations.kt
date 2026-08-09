package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.SortDir
import com.safedb.explore.WorksheetAggregateFn
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetGrain
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.WorksheetWindowFn
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.evaluatePivotFormula
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import java.text.DecimalFormat
import java.util.Currency
import java.util.UUID

@Composable
internal fun WorksheetCalculationEditor(
    sample: QueryResult,
    config: WorksheetConfig,
    existing: WorksheetCalculation?,
    onSave: (WorksheetCalculation, WorksheetSort?) -> Unit,
    onCancel: () -> Unit,
) {
    var kind by remember { mutableStateOf(calculationKind(existing)) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var formula by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.RowFormula)?.formula
                ?: (existing as? WorksheetCalculation.GroupFormula)?.formula.orEmpty()
        )
    }
    var aggregateFn by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Aggregate)?.fn ?: WorksheetAggregateFn.Sum
        )
    }
    var windowFn by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Window)?.fn ?: WorksheetWindowFn.RunningTotal
        )
    }
    var source by remember {
        mutableStateOf(
            sourceColumn(existing)
                ?: sample.columns.firstOrNull { isNumericType(it.dataType) }?.name.orEmpty()
        )
    }
    var groupColumn by remember {
        mutableStateOf(groupColumn(existing) ?: config.groups.lastOrNull()?.column.orEmpty())
    }
    var grain by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Window)?.grain ?: WorksheetGrain.DetailRows
        )
    }
    var offset by remember {
        mutableStateOf(((existing as? WorksheetCalculation.Window)?.offset ?: 1).toString())
    }
    var formatKind by remember {
        mutableStateOf(existing?.numberFormat?.kind ?: NumberFormatKind.Auto)
    }
    val id =
        remember(existing?.id) { existing?.id ?: "calc_${UUID.randomUUID().toString().take(8)}" }
    val numericColumns = sample.columns.filter { isNumericType(it.dataType) }
    val sourceOptions =
        when (kind) {
            CalculationKind.Aggregate ->
                if (aggregateFn == WorksheetAggregateFn.Count) {
                    listOf("") + numericColumns.map { it.name }
                } else {
                    numericColumns.map { it.name }
                }
            CalculationKind.Window ->
                (numericColumns.map { it.name } +
                        config.calculations.filterNot { it.id == id }.map { it.id })
                    .distinct()
            CalculationKind.RowFormula,
            CalculationKind.GroupFormula -> emptyList()
        }
    val groupColumns = config.groups.map { it.column }
    val reconciledSelection =
        reconcileWorksheetCalculationEditorSelection(
            WorksheetCalculationEditorSelection(source, groupColumn, grain),
            sourceOptions =
                sourceOptions.takeUnless {
                    kind in setOf(CalculationKind.RowFormula, CalculationKind.GroupFormula)
                },
            groupColumns = groupColumns,
        )
    LaunchedEffect(sourceOptions, groupColumns) {
        source = reconciledSelection.source
        groupColumn = reconciledSelection.groupColumn
        grain = reconciledSelection.grain
    }
    val groupCalculations =
        config.calculations.filter {
            it is WorksheetCalculation.Aggregate || it is WorksheetCalculation.GroupFormula
        }
    val formulaTokens =
        if (kind == CalculationKind.GroupFormula) {
            groupCalculations.filterNot { it.id == id }.map { it.id to it.label }
        } else {
            sample.columns.map { it.name to displayColumnLabel(it.name) } +
                config.calculations
                    .filterIsInstance<WorksheetCalculation.RowFormula>()
                    .filterNot { it.id == id }
                    .map { it.id to it.label }
        }
    val formulaValidation =
        if (
            formula.isBlank() ||
                kind !in setOf(CalculationKind.RowFormula, CalculationKind.GroupFormula)
        ) {
            null
        } else {
            evaluatePivotFormula(
                    formula,
                    formulaTokens.associate { it.first to java.math.BigDecimal.ONE },
                )
                .error
        }
    val orderedWindow =
        windowFn in
            setOf(
                WorksheetWindowFn.RunningTotal,
                WorksheetWindowFn.RunningAverage,
                WorksheetWindowFn.PreviousValue,
                WorksheetWindowFn.DifferenceFromPrevious,
            )
    val enabled =
        label.isNotBlank() &&
            when (kind) {
                CalculationKind.RowFormula -> formula.isNotBlank() && formulaValidation == null
                CalculationKind.GroupFormula ->
                    formula.isNotBlank() &&
                        formulaValidation == null &&
                        groupColumn == reconciledSelection.groupColumn
                CalculationKind.Aggregate ->
                    source == reconciledSelection.source &&
                        source in sourceOptions &&
                        groupColumn == reconciledSelection.groupColumn
                CalculationKind.Window ->
                    source == reconciledSelection.source &&
                        source in sourceOptions &&
                        groupColumn == reconciledSelection.groupColumn &&
                        grain == reconciledSelection.grain
            }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            calculationTabOrder.forEach { choice ->
                SelectPill(label = choice.tabLabel, selected = kind == choice) { kind = choice }
            }
        }
        Text(
            if (existing == null) "New calculation" else "Editing ${existing.label}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        CompactWorksheetInput(
            value = label,
            onValueChange = { label = it },
            placeholder = "Name",
            modifier = Modifier.fillMaxWidth(),
        )
        when (kind) {
            CalculationKind.RowFormula,
            CalculationKind.GroupFormula -> {
                CompactWorksheetInput(
                    value = formula,
                    onValueChange = { formula = it },
                    placeholder = "Formula · [amount] - [discount]",
                    supportingText =
                        formulaValidation
                            ?: "Use field tokens, numbers, parentheses, and +, -, *, /.",
                    isError = formulaValidation != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Insert field or calculation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    formulaTokens.forEach { (token, display) ->
                        SelectPill(display, false) {
                            formula += if (formula.isBlank()) "[$token]" else " [$token]"
                        }
                    }
                }
            }
            CalculationKind.Aggregate -> {
                SelectRow(
                    "Summary",
                    WorksheetAggregateFn.entries,
                    aggregateFn,
                    { it.name.toDisplayWords() },
                ) {
                    aggregateFn = it
                }
                SelectRow(
                    "Value",
                    sourceOptions,
                    source,
                    { if (it.isBlank()) "Rows" else displayColumnLabel(it) },
                ) {
                    source = it
                }
            }
            CalculationKind.Window -> {
                SelectRow(
                    "Quick calculation",
                    WorksheetWindowFn.entries,
                    windowFn,
                    { it.name.toDisplayWords() },
                ) {
                    windowFn = it
                }
                SelectRow(
                    "Value",
                    sourceOptions,
                    source,
                    { token ->
                        config.calculations.firstOrNull { it.id == token }?.label
                            ?: displayColumnLabel(token)
                    },
                ) {
                    source = it
                }
                if (config.groups.isNotEmpty()) {
                    SelectRow(
                        "Calculate over",
                        WorksheetGrain.entries,
                        grain,
                        { if (it == WorksheetGrain.DetailRows) "Detail rows" else "Group rows" },
                    ) {
                        grain = it
                    }
                }
                if (
                    windowFn in
                        setOf(
                            WorksheetWindowFn.PreviousValue,
                            WorksheetWindowFn.DifferenceFromPrevious,
                        )
                )
                    CompactWorksheetInput(
                        value = offset,
                        onValueChange = { offset = it },
                        placeholder = "Offset",
                        modifier = Modifier.width(120.dp),
                    )
                if (orderedWindow && config.sorts.isEmpty())
                    Text(
                        "The worksheet will be sorted by the selected value in ascending order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
            }
        }
        if (kind != CalculationKind.RowFormula && config.groups.isNotEmpty()) {
            SelectRow(
                "Group level",
                listOf("") + config.groups.map { it.column },
                groupColumn,
                { if (it.isBlank()) "All / grand total" else displayColumnLabel(it) },
            ) {
                groupColumn = it
            }
        }
        SelectRow("Format", NumberFormatKind.entries, formatKind, { it.name.toDisplayWords() }) {
            formatKind = it
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        ) {
            SecondaryButton(onClick = onCancel) { Text("Cancel") }
            PrimaryButton(
                onClick = {
                    val calculation =
                        when (kind) {
                            CalculationKind.RowFormula ->
                                WorksheetCalculation.RowFormula(
                                    id,
                                    label.trim(),
                                    formula.trim(),
                                    numberFormat = PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.GroupFormula ->
                                WorksheetCalculation.GroupFormula(
                                    id,
                                    label.trim(),
                                    formula.trim(),
                                    groupColumn.ifBlank { null },
                                    PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.Aggregate ->
                                WorksheetCalculation.Aggregate(
                                    id,
                                    label.trim(),
                                    aggregateFn,
                                    source.takeUnless {
                                        aggregateFn == WorksheetAggregateFn.Count && it.isBlank()
                                    },
                                    groupColumn.ifBlank { null },
                                    PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.Window ->
                                WorksheetCalculation.Window(
                                    id = id,
                                    label = label.trim(),
                                    fn = windowFn,
                                    source =
                                        config.calculations
                                            .firstOrNull { it.id == source }
                                            ?.let { WorksheetValueRef.Calculation(it.id) }
                                            ?: WorksheetValueRef.Column(source),
                                    grain = grain,
                                    groupColumn = groupColumn.ifBlank { null },
                                    restartColumns =
                                        config.groups
                                            .takeWhile { it.column != groupColumn }
                                            .map { it.column },
                                    offset = offset.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                    numberFormat = PivotNumberFormat(kind = formatKind),
                                )
                        }
                    val requiredSort =
                        if (
                            kind == CalculationKind.Window &&
                                orderedWindow &&
                                config.sorts.isEmpty()
                        ) {
                            WorksheetSort(
                                (calculation as WorksheetCalculation.Window).source,
                                SortDir.Asc,
                            )
                        } else null
                    onSave(calculation, requiredSort)
                },
                enabled = enabled,
            ) {
                Text(if (existing == null) "Add" else "Apply")
            }
        }
    }
}

internal data class WorksheetCalculationEditorSelection(
    val source: String,
    val groupColumn: String,
    val grain: WorksheetGrain,
)

internal fun reconcileWorksheetCalculationEditorSelection(
    selection: WorksheetCalculationEditorSelection,
    sourceOptions: List<String>?,
    groupColumns: List<String>,
): WorksheetCalculationEditorSelection =
    selection.copy(
        source =
            sourceOptions?.let { selection.source.takeIf { source -> source in it }.orEmpty() }
                ?: selection.source,
        groupColumn = selection.groupColumn.takeIf { it in groupColumns }.orEmpty(),
        grain = if (groupColumns.isEmpty()) WorksheetGrain.DetailRows else selection.grain,
    )

internal enum class CalculationKind(val tabLabel: String) {
    RowFormula("Row"),
    Aggregate("Summary"),
    GroupFormula("Group"),
    Window("Quick"),
}

private val calculationTabOrder =
    listOf(
        CalculationKind.Window,
        CalculationKind.RowFormula,
        CalculationKind.Aggregate,
        CalculationKind.GroupFormula,
    )

internal fun calculationKind(calculation: WorksheetCalculation?): CalculationKind =
    when (calculation) {
        is WorksheetCalculation.RowFormula -> CalculationKind.RowFormula
        is WorksheetCalculation.Aggregate -> CalculationKind.Aggregate
        is WorksheetCalculation.GroupFormula -> CalculationKind.GroupFormula
        is WorksheetCalculation.Window -> CalculationKind.Window
        null -> CalculationKind.Window
    }

internal fun sourceColumn(calculation: WorksheetCalculation?): String? =
    when (calculation) {
        is WorksheetCalculation.Aggregate -> calculation.sourceColumn
        is WorksheetCalculation.Window ->
            when (val source = calculation.source) {
                is WorksheetValueRef.Column -> source.column
                is WorksheetValueRef.Calculation -> source.id
            }
        else -> null
    }

internal fun groupColumn(calculation: WorksheetCalculation?): String? =
    when (calculation) {
        is WorksheetCalculation.Aggregate -> calculation.groupColumn
        is WorksheetCalculation.GroupFormula -> calculation.groupColumn
        is WorksheetCalculation.Window -> calculation.groupColumn
        else -> null
    }

internal fun cycleSort(sorts: List<WorksheetSort>, target: WorksheetValueRef): List<WorksheetSort> {
    val existing = sorts.firstOrNull { it.target == target }
    return when (existing?.dir) {
        null -> sorts + WorksheetSort(target, SortDir.Asc)
        SortDir.Asc -> sorts.map { if (it.target == target) it.copy(dir = SortDir.Desc) else it }
        SortDir.Desc -> sorts.filterNot { it.target == target }
    }
}

internal fun calculationSummary(calculation: WorksheetCalculation): String =
    when (calculation) {
        is WorksheetCalculation.RowFormula -> calculation.formula
        is WorksheetCalculation.Aggregate ->
            "${calculation.fn.name.toDisplayWords()} · ${calculation.sourceColumn?.let(::displayColumnLabel) ?: "rows"}"
        is WorksheetCalculation.GroupFormula -> calculation.formula
        is WorksheetCalculation.Window ->
            "${calculation.fn.name.toDisplayWords()} · ${calculation.grain.name.toDisplayWords()}"
    }

internal fun filterOps(type: String): List<WorksheetFilterOp> =
    when {
        isNumericType(type) || isTemporalType(type) ->
            listOf(
                WorksheetFilterOp.Members,
                WorksheetFilterOp.Equals,
                WorksheetFilterOp.NotEquals,
                WorksheetFilterOp.GreaterThan,
                WorksheetFilterOp.GreaterThanOrEqual,
                WorksheetFilterOp.LessThan,
                WorksheetFilterOp.LessThanOrEqual,
                WorksheetFilterOp.Between,
                WorksheetFilterOp.IsNull,
                WorksheetFilterOp.IsNotNull,
            )
        else ->
            listOf(
                WorksheetFilterOp.Members,
                WorksheetFilterOp.Contains,
                WorksheetFilterOp.Equals,
                WorksheetFilterOp.NotEquals,
                WorksheetFilterOp.StartsWith,
                WorksheetFilterOp.EndsWith,
                WorksheetFilterOp.IsNull,
                WorksheetFilterOp.IsNotNull,
            )
    }

internal fun defaultFilterOp(type: String): WorksheetFilterOp =
    if (isNumericType(type) || isTemporalType(type)) WorksheetFilterOp.Equals
    else WorksheetFilterOp.Contains

internal fun isNumericType(type: String): Boolean = classifyColumn(type).isNumeric()

internal fun isTemporalType(type: String): Boolean = classifyColumn(type).isTemporal()

internal fun groupingLabel(grouping: PivotGrouping): String =
    when (grouping) {
        PivotGrouping.Exact -> "Exact values"
        is PivotGrouping.Date -> grouping.unit.name.toDisplayWords()
        is PivotGrouping.NumberBin -> "Number bins"
    }

internal fun sameGroupingKind(left: PivotGrouping, right: PivotGrouping): Boolean =
    when {
        left is PivotGrouping.Date && right is PivotGrouping.Date -> left.unit == right.unit
        left is PivotGrouping.NumberBin && right is PivotGrouping.NumberBin -> true
        else -> left == right
    }

internal fun String.toDisplayWords(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercase)

internal fun formatWorksheetValue(value: ResultCell, format: PivotNumberFormat?): String {
    val number =
        when (value) {
            is ResultCell.IntegerCell -> value.value.toDouble()
            is ResultCell.FloatCell -> value.value
            else -> return formatCell(value)
        }
    val applied = format ?: return formatCell(value)
    if (applied.kind == NumberFormatKind.Auto) return formatCell(value)
    if (applied.kind == NumberFormatKind.Scientific)
        return "%1$.${applied.decimals}e".format(number)
    val pattern = buildString {
        append(if (applied.thousandsSeparator) "#,##0" else "0")
        if (applied.decimals > 0) append('.').append("0".repeat(applied.decimals))
    }
    val formatter = DecimalFormat(pattern)
    return when (applied.kind) {
        NumberFormatKind.Percent -> "${formatter.format(number * 100)}%"
        NumberFormatKind.Currency ->
            runCatching { Currency.getInstance(applied.currencyCode).symbol }
                .getOrDefault(applied.currencyCode) + formatter.format(number)
        NumberFormatKind.Number -> formatter.format(number)
        NumberFormatKind.Auto,
        NumberFormatKind.Scientific -> formatCell(value)
    }
}
