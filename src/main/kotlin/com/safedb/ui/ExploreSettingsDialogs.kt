package com.safedb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.DimensionSortMode
import com.safedb.explore.LabelFilterOp
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.evaluatePivotFormula
import com.safedb.model.ColumnCategory
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.InputShape
import com.safedb.viewmodel.MemberOption
import java.math.BigDecimal
import java.util.UUID

@Composable
internal fun DimensionSettingsDialog(
    dimension: PivotDimension,
    field: ExploreFieldOption?,
    measures: List<PivotMeasure>,
    onSave: (PivotDimension) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(dimension.id) { mutableStateOf(dimension.label) }
    var grouping by remember(dimension.id) { mutableStateOf(dimension.grouping) }
    var binSize by
        remember(dimension.id) {
            mutableStateOf((dimension.grouping as? PivotGrouping.NumberBin)?.size.orEmpty())
        }
    var binStart by
        remember(dimension.id) {
            mutableStateOf((dimension.grouping as? PivotGrouping.NumberBin)?.start.orEmpty())
        }
    var subtotals by remember(dimension.id) { mutableStateOf(dimension.showSubtotals) }
    var sortMode by remember(dimension.id) { mutableStateOf(dimension.sortMode) }
    var sortMeasureAlias by
        remember(dimension.id) {
            mutableStateOf(dimension.sortMeasureAlias ?: measures.firstOrNull()?.alias)
        }
    val numeric = field?.category in setOf(ColumnCategory.Integer, ColumnCategory.Decimal)
    val temporal = field?.category in setOf(ColumnCategory.Date, ColumnCategory.DateTime)
    val validBin =
        grouping !is PivotGrouping.NumberBin ||
            binSize.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Field settings", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    shape = InputShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsLabel("Group values")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SelectablePill("Exact", grouping == PivotGrouping.Exact) {
                        grouping = PivotGrouping.Exact
                    }
                    if (temporal) {
                        DateGroupUnit.entries.forEach { unit ->
                            SelectablePill(
                                dateUnitLabel(unit),
                                grouping == PivotGrouping.Date(unit),
                            ) {
                                grouping = PivotGrouping.Date(unit)
                            }
                        }
                    }
                    if (numeric) {
                        SelectablePill("Number bins", grouping is PivotGrouping.NumberBin) {
                            grouping =
                                PivotGrouping.NumberBin(
                                    binSize.ifBlank { "100" },
                                    binStart.ifBlank { null },
                                )
                        }
                    }
                }
                if (grouping is PivotGrouping.NumberBin) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = binSize,
                            onValueChange = { binSize = it },
                            label = { Text("Bin size") },
                            singleLine = true,
                            isError = !validBin,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = binStart,
                            onValueChange = { binStart = it },
                            label = { Text("Start (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { subtotals = !subtotals },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = subtotals, onCheckedChange = null)
                    Text(
                        "Show subtotals for this field",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SettingsLabel("Sort members")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DimensionSortMode.entries.forEach { mode ->
                        SelectablePill(dimensionSortLabel(mode), sortMode == mode) {
                            sortMode = mode
                        }
                    }
                }
                if (
                    sortMode in
                        setOf(DimensionSortMode.ValueAscending, DimensionSortMode.ValueDescending)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        measures.forEach { measure ->
                            SelectablePill(measure.label, sortMeasureAlias == measure.alias) {
                                sortMeasureAlias = measure.alias
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        dimension.copy(
                            label = label.trim().ifEmpty { dimension.label },
                            grouping =
                                if (grouping is PivotGrouping.NumberBin) {
                                    PivotGrouping.NumberBin(
                                        binSize.trim(),
                                        binStart.trim().ifEmpty { null },
                                    )
                                } else {
                                    grouping
                                },
                            showSubtotals = subtotals,
                            sortMode = sortMode,
                            sortMeasureAlias = sortMeasureAlias,
                        )
                    )
                },
                enabled = label.isNotBlank() && validBin,
            ) {
                Text("Apply")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun MeasureSettingsDialog(
    measure: PivotMeasure,
    dimensions: List<PivotDimension>,
    availableFunctions: List<MeasureFn>,
    onSave: (PivotMeasure) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(measure.alias) { mutableStateOf(measure.label) }
    var formula by remember(measure.alias) { mutableStateOf(measure.formula.orEmpty()) }
    var function by remember(measure.alias) { mutableStateOf(measure.fn) }
    var showAs by remember(measure.alias) { mutableStateOf(measure.showAs.mode) }
    var baseDimensionId by
        remember(measure.alias) { mutableStateOf(measure.showAs.baseDimensionId) }
    var formatKind by remember(measure.alias) { mutableStateOf(measure.numberFormat.kind) }
    var decimals by
        remember(measure.alias) { mutableStateOf(measure.numberFormat.decimals.toString()) }
    var thousands by
        remember(measure.alias) { mutableStateOf(measure.numberFormat.thousandsSeparator) }
    var currencyCode by
        remember(measure.alias) { mutableStateOf(measure.numberFormat.currencyCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Value settings", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (measure.formula != null) {
                    OutlinedTextField(
                        value = formula,
                        onValueChange = { formula = it },
                        label = { Text("Formula") },
                        supportingText = {
                            Text("References use stable tokens such as [${measure.alias}]")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SettingsLabel("Summarize by")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableFunctions.forEach { option ->
                            SelectablePill(option.label, function == option) { function = option }
                        }
                    }
                }
                SettingsLabel("Show values as")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    showAsChoices.forEach { mode ->
                        SelectablePill(showAsLabel(mode), showAs == mode) { showAs = mode }
                    }
                }
                if (showAs in modesNeedingBaseDimension && dimensions.isNotEmpty()) {
                    SettingsLabel("Base field")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dimensions.forEach { dimension ->
                            SelectablePill(dimension.label, baseDimensionId == dimension.id) {
                                baseDimensionId = dimension.id
                            }
                        }
                    }
                }
                SettingsLabel("Number format")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NumberFormatKind.entries.forEach { kind ->
                        SelectablePill(numberFormatKindLabel(kind), formatKind == kind) {
                            formatKind = kind
                        }
                    }
                }
                if (formatKind != NumberFormatKind.Auto) {
                    OutlinedTextField(
                        value = decimals,
                        onValueChange = { decimals = it.filter(Char::isDigit).take(1) },
                        label = { Text("Decimal places (0–8)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (formatKind in setOf(NumberFormatKind.Number, NumberFormatKind.Currency)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { thousands = !thousands },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = thousands, onCheckedChange = null)
                            Text("Use thousands separator")
                        }
                    }
                    if (formatKind == NumberFormatKind.Currency) {
                        OutlinedTextField(
                            value = currencyCode,
                            onValueChange = {
                                currencyCode = it.uppercase().filter(Char::isLetter).take(3)
                            },
                            label = { Text("Currency code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        measure.copy(
                            label = label.trim().ifEmpty { measure.label },
                            fn = function,
                            formula = measure.formula?.let { formula.trim() },
                            showAs = PivotShowAs(showAs, baseDimensionId),
                            numberFormat =
                                measure.numberFormat.copy(
                                    kind = formatKind,
                                    decimals = decimals.toIntOrNull()?.coerceIn(0, 8) ?: 2,
                                    thousandsSeparator = thousands,
                                    currencyCode =
                                        currencyCode.ifBlank { measure.numberFormat.currencyCode },
                                ),
                        )
                    )
                },
                enabled =
                    label.isNotBlank() &&
                        (measure.formula == null || formula.isNotBlank()) &&
                        (formatKind != NumberFormatKind.Currency || currencyCode.length == 3),
            ) {
                Text("Apply")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun CalculatedMeasureDialog(
    existing: List<PivotMeasure>,
    onSave: (PivotMeasure) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    val aliases = existing.associate { it.alias to BigDecimal.ONE }
    val validation = if (formula.isBlank()) null else evaluatePivotFormula(formula, aliases).error

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Calculated measure", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formula,
                    onValueChange = { formula = it },
                    label = { Text("Formula") },
                    placeholder = { Text("[revenue] / [orders]") },
                    supportingText = {
                        Text(
                            validation
                                ?: "Use +, −, ×, ÷, parentheses, and the measure tokens below."
                        )
                    },
                    isError = validation != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsLabel("Insert measure")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    existing.forEach { measure ->
                        SelectablePill(measure.label, false) {
                            formula +=
                                if (formula.isBlank()) "[${measure.alias}]"
                                else " [${measure.alias}]"
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        PivotMeasure(
                            alias = "calc_${UUID.randomUUID().toString().take(8)}",
                            fn = MeasureFn.Sum,
                            label = label.trim(),
                            formula = formula.trim(),
                            numberFormat = PivotNumberFormat(),
                        )
                    )
                },
                enabled = label.isNotBlank() && formula.isNotBlank() && validation == null,
            ) {
                Text("Add")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun dimensionSortLabel(mode: DimensionSortMode): String =
    when (mode) {
        DimensionSortMode.SourceOrder -> "Source order"
        DimensionSortMode.LabelAscending -> "A → Z"
        DimensionSortMode.LabelDescending -> "Z → A"
        DimensionSortMode.ValueAscending -> "Value ↑"
        DimensionSortMode.ValueDescending -> "Value ↓"
    }

private val showAsChoices =
    listOf(
        ShowAsMode.Value,
        ShowAsMode.PercentGrandTotal,
        ShowAsMode.PercentRowTotal,
        ShowAsMode.PercentColumnTotal,
        ShowAsMode.PercentParent,
        ShowAsMode.DifferenceFrom,
        ShowAsMode.PercentDifferenceFrom,
        ShowAsMode.RunningTotal,
        ShowAsMode.PercentRunningTotal,
        ShowAsMode.RankAscending,
        ShowAsMode.RankDescending,
    )

private val modesNeedingBaseDimension =
    setOf(
        ShowAsMode.PercentParent,
        ShowAsMode.DifferenceFrom,
        ShowAsMode.PercentDifferenceFrom,
        ShowAsMode.RunningTotal,
        ShowAsMode.PercentRunningTotal,
        ShowAsMode.RankAscending,
        ShowAsMode.RankDescending,
    )

private fun showAsLabel(mode: ShowAsMode): String =
    when (mode) {
        ShowAsMode.Value -> "Value"
        ShowAsMode.PercentGrandTotal -> "% grand total"
        ShowAsMode.PercentRowTotal -> "% row total"
        ShowAsMode.PercentColumnTotal -> "% column total"
        ShowAsMode.PercentParent -> "% parent"
        ShowAsMode.DifferenceFrom -> "Difference"
        ShowAsMode.PercentDifferenceFrom -> "% difference"
        ShowAsMode.RunningTotal -> "Running total"
        ShowAsMode.PercentRunningTotal -> "% running total"
        ShowAsMode.RankAscending -> "Rank ascending"
        ShowAsMode.RankDescending -> "Rank descending"
    }

private fun numberFormatKindLabel(kind: NumberFormatKind): String =
    when (kind) {
        NumberFormatKind.Auto -> "Auto"
        NumberFormatKind.Number -> "Number"
        NumberFormatKind.Percent -> "Percent"
        NumberFormatKind.Currency -> "Currency"
        NumberFormatKind.Scientific -> "Scientific"
    }

@Composable
internal fun FilterSettingsDialog(
    filter: PivotFilter,
    measures: List<PivotMeasure>,
    memberOptions: List<MemberOption>,
    showPinnedOption: Boolean = true,
    onSave: (PivotFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by
        remember(filter.id) {
            mutableStateOf(FilterDraft.from(filter, measures.firstOrNull()?.alias.orEmpty()))
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        title = { Text("${filter.label} filter", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 500.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsLabel("Filter type")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterEditorKind.entries.forEach { option ->
                        SelectablePill(filterKindLabel(option), draft.kind == option) {
                            draft = draft.copy(kind = option)
                        }
                    }
                }
                when (draft.kind) {
                    FilterEditorKind.Members -> {
                        if (showPinnedOption) {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        draft = draft.copy(pinned = !draft.pinned)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = draft.pinned, onCheckedChange = null)
                                Text("Pin this multi-select filter above the pivot")
                            }
                        }
                        FilterMemberList(
                            memberOptions = memberOptions,
                            includedKeys = draft.includedKeys,
                            onIncludedKeysChange = { draft = draft.copy(includedKeys = it) },
                        )
                    }
                    FilterEditorKind.Label -> {
                        SettingsLabel("Match")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LabelFilterOp.entries.forEach { op ->
                                SelectablePill(labelFilterLabel(op), draft.labelOp == op) {
                                    draft = draft.copy(labelOp = op)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draft.text,
                            onValueChange = { draft = draft.copy(text = it) },
                            label = { Text("Text") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FilterEditorKind.Value,
                    FilterEditorKind.TopN -> {
                        SettingsLabel("Measure")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            measures.forEach { measure ->
                                SelectablePill(measure.label, draft.measureAlias == measure.alias) {
                                    draft = draft.copy(measureAlias = measure.alias)
                                }
                            }
                        }
                        if (draft.kind == FilterEditorKind.TopN) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SelectablePill("Top", draft.valueOp == ValueFilterOp.Top) {
                                    draft = draft.copy(valueOp = ValueFilterOp.Top)
                                }
                                SelectablePill("Bottom", draft.valueOp == ValueFilterOp.Bottom) {
                                    draft = draft.copy(valueOp = ValueFilterOp.Bottom)
                                }
                            }
                            OutlinedTextField(
                                value = draft.count,
                                onValueChange = {
                                    draft = draft.copy(count = it.filter(Char::isDigit).take(4))
                                },
                                label = { Text("Items") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ValueFilterOp.entries
                                    .filterNot { it in topBottomOps }
                                    .forEach { op ->
                                        SelectablePill(valueFilterLabel(op), draft.valueOp == op) {
                                            draft = draft.copy(valueOp = op)
                                        }
                                    }
                            }
                            OutlinedTextField(
                                value = draft.text,
                                onValueChange = { draft = draft.copy(text = it) },
                                label = { Text("Value") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (draft.valueOp == ValueFilterOp.Between) {
                                OutlinedTextField(
                                    value = draft.secondText,
                                    onValueChange = { draft = draft.copy(secondText = it) },
                                    label = { Text("Second value") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = { onSave(draft.toFilter()) }, enabled = draft.isValid) {
                Text("Apply")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterMemberList(
    memberOptions: List<MemberOption>,
    includedKeys: Set<String>,
    onIncludedKeysChange: (Set<String>) -> Unit,
) {
    Text(
        if (includedKeys.isEmpty()) "All sample values selected"
        else "${includedKeys.size} selected",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier =
            Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
    ) {
        memberOptions.forEach { option ->
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        val baseline = includedKeys.ifEmpty { memberOptions.map { it.key }.toSet() }
                        val next =
                            if (option.key in baseline) baseline - option.key
                            else baseline + option.key
                        if (next.isNotEmpty()) {
                            onIncludedKeysChange(
                                if (next.size == memberOptions.size) emptySet() else next
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includedKeys.isEmpty() || option.key in includedKeys,
                    onCheckedChange = null,
                )
                Text(
                    option.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${option.count}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal enum class FilterEditorKind {
    Members,
    Label,
    Value,
    TopN,
}

private val topBottomOps = setOf(ValueFilterOp.Top, ValueFilterOp.Bottom)

internal data class FilterDraft(
    val id: String,
    val column: String,
    val label: String,
    val kind: FilterEditorKind,
    val pinned: Boolean = false,
    val includedKeys: Set<String> = emptySet(),
    val labelOp: LabelFilterOp = LabelFilterOp.Contains,
    val valueOp: ValueFilterOp = ValueFilterOp.GreaterThan,
    val measureAlias: String = "",
    val text: String = "",
    val secondText: String = "",
    val count: String = "10",
) {
    val isValid: Boolean
        get() =
            when (kind) {
                FilterEditorKind.Members -> true
                FilterEditorKind.Label -> text.isNotBlank()
                FilterEditorKind.Value ->
                    measureAlias.isNotBlank() &&
                        text.toBigDecimalOrNull() != null &&
                        (valueOp != ValueFilterOp.Between ||
                            secondText.toBigDecimalOrNull() != null)
                FilterEditorKind.TopN -> measureAlias.isNotBlank() && (count.toIntOrNull() ?: 0) > 0
            }

    fun toFilter(): PivotFilter =
        when (kind) {
            FilterEditorKind.Members ->
                PivotFilter.Members(
                    id,
                    column,
                    label,
                    includedKeys = includedKeys,
                    pinned = pinned,
                )
            FilterEditorKind.Label -> PivotFilter.Label(id, column, label, labelOp, text.trim())
            FilterEditorKind.Value ->
                PivotFilter.Value(
                    id,
                    column,
                    label,
                    measureAlias,
                    valueOp.takeUnless { it in topBottomOps } ?: ValueFilterOp.GreaterThan,
                    text.trim(),
                    secondText.trim().ifEmpty { null },
                )
            FilterEditorKind.TopN ->
                PivotFilter.Value(
                    id,
                    column,
                    label,
                    measureAlias,
                    if (valueOp == ValueFilterOp.Bottom) ValueFilterOp.Bottom
                    else ValueFilterOp.Top,
                    count = count.toIntOrNull()?.coerceAtLeast(1) ?: 10,
                )
        }

    companion object {
        fun from(filter: PivotFilter, defaultMeasureAlias: String = ""): FilterDraft =
            FilterDraft(
                id = filter.id,
                column = filter.column,
                label = filter.label,
                kind =
                    when (filter) {
                        is PivotFilter.Members -> FilterEditorKind.Members
                        is PivotFilter.Label -> FilterEditorKind.Label
                        is PivotFilter.Value ->
                            if (filter.op in topBottomOps) FilterEditorKind.TopN
                            else FilterEditorKind.Value
                    },
                pinned = filter.pinned,
                includedKeys = (filter as? PivotFilter.Members)?.includedKeys.orEmpty(),
                labelOp = (filter as? PivotFilter.Label)?.op ?: LabelFilterOp.Contains,
                valueOp = (filter as? PivotFilter.Value)?.op ?: ValueFilterOp.GreaterThan,
                measureAlias = (filter as? PivotFilter.Value)?.measureAlias ?: defaultMeasureAlias,
                text =
                    when (filter) {
                        is PivotFilter.Label -> filter.value
                        is PivotFilter.Value -> filter.value
                        is PivotFilter.Members -> ""
                    },
                secondText = (filter as? PivotFilter.Value)?.secondValue.orEmpty(),
                count = ((filter as? PivotFilter.Value)?.count ?: 10).toString(),
            )
    }
}

private fun filterKindLabel(kind: FilterEditorKind): String =
    when (kind) {
        FilterEditorKind.Members -> "Members / slicer"
        FilterEditorKind.Label -> "Label"
        FilterEditorKind.Value -> "Value"
        FilterEditorKind.TopN -> "Top / Bottom N"
    }

private fun labelFilterLabel(op: LabelFilterOp): String =
    when (op) {
        LabelFilterOp.Equals -> "Equals"
        LabelFilterOp.Contains -> "Contains"
        LabelFilterOp.StartsWith -> "Starts with"
        LabelFilterOp.EndsWith -> "Ends with"
    }

private fun valueFilterLabel(op: ValueFilterOp): String =
    when (op) {
        ValueFilterOp.GreaterThan -> ">"
        ValueFilterOp.GreaterThanOrEqual -> "≥"
        ValueFilterOp.LessThan -> "<"
        ValueFilterOp.LessThanOrEqual -> "≤"
        ValueFilterOp.Between -> "Between"
        ValueFilterOp.Top -> "Top"
        ValueFilterOp.Bottom -> "Bottom"
    }
