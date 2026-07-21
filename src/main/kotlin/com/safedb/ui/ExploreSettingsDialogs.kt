package com.safedb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.explore.LabelFilterOp
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.evaluatePivotFormula
import com.safedb.model.ColumnCategory
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
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
    var binSize by remember(dimension.id) { mutableStateOf((dimension.grouping as? PivotGrouping.NumberBin)?.size.orEmpty()) }
    var binStart by remember(dimension.id) { mutableStateOf((dimension.grouping as? PivotGrouping.NumberBin)?.start.orEmpty()) }
    var subtotals by remember(dimension.id) { mutableStateOf(dimension.showSubtotals) }
    var sortMode by remember(dimension.id) { mutableStateOf(dimension.sortMode) }
    var sortMeasureAlias by remember(dimension.id) { mutableStateOf(dimension.sortMeasureAlias ?: measures.firstOrNull()?.alias) }
    val numeric = field?.category in setOf(ColumnCategory.Integer, ColumnCategory.Decimal)
    val temporal = field?.category in setOf(ColumnCategory.Date, ColumnCategory.DateTime)
    val validBin = grouping !is PivotGrouping.NumberBin || binSize.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoicePill("Exact", grouping == PivotGrouping.Exact) { grouping = PivotGrouping.Exact }
                    if (temporal) {
                        DateGroupUnit.entries.forEach { unit ->
                            ChoicePill(dateUnitLabel(unit), grouping == PivotGrouping.Date(unit)) {
                                grouping = PivotGrouping.Date(unit)
                            }
                        }
                    }
                    if (numeric) {
                        ChoicePill("Number bins", grouping is PivotGrouping.NumberBin) {
                            grouping = PivotGrouping.NumberBin(binSize.ifBlank { "100" }, binStart.ifBlank { null })
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
                    Text("Show subtotals for this field", style = MaterialTheme.typography.bodySmall)
                }
                SettingsLabel("Sort members")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DimensionSortMode.entries.forEach { mode ->
                        ChoicePill(dimensionSortLabel(mode), sortMode == mode) { sortMode = mode }
                    }
                }
                if (sortMode in setOf(DimensionSortMode.ValueAscending, DimensionSortMode.ValueDescending)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        measures.forEach { measure ->
                            ChoicePill(measure.label, sortMeasureAlias == measure.alias) { sortMeasureAlias = measure.alias }
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
                            grouping = if (grouping is PivotGrouping.NumberBin) {
                                PivotGrouping.NumberBin(binSize.trim(), binStart.trim().ifEmpty { null })
                            } else {
                                grouping
                            },
                            showSubtotals = subtotals,
                            sortMode = sortMode,
                            sortMeasureAlias = sortMeasureAlias,
                        ),
                    )
                },
                enabled = label.isNotBlank() && validBin,
            ) { Text("Apply") }
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
    var baseDimensionId by remember(measure.alias) { mutableStateOf(measure.showAs.baseDimensionId) }
    var formatKind by remember(measure.alias) { mutableStateOf(measure.numberFormat.kind) }
    var decimals by remember(measure.alias) { mutableStateOf(measure.numberFormat.decimals.toString()) }
    var thousands by remember(measure.alias) { mutableStateOf(measure.numberFormat.thousandsSeparator) }
    var currencyCode by remember(measure.alias) { mutableStateOf(measure.numberFormat.currencyCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
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
                        supportingText = { Text("References use stable tokens such as [${measure.alias}]") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SettingsLabel("Summarize by")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableFunctions.forEach { option ->
                            ChoicePill(measureFunctionName(option), function == option) { function = option }
                        }
                    }
                }
                SettingsLabel("Show values as")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    showAsChoices.forEach { mode ->
                        ChoicePill(showAsLabel(mode), showAs == mode) { showAs = mode }
                    }
                }
                if (showAs in modesNeedingBaseDimension && dimensions.isNotEmpty()) {
                    SettingsLabel("Base field")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dimensions.forEach { dimension ->
                            ChoicePill(dimension.label, baseDimensionId == dimension.id) { baseDimensionId = dimension.id }
                        }
                    }
                }
                SettingsLabel("Number format")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberFormatKind.entries.forEach { kind ->
                        ChoicePill(numberFormatKindLabel(kind), formatKind == kind) { formatKind = kind }
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
                            onValueChange = { currencyCode = it.uppercase().filter(Char::isLetter).take(3) },
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
                            numberFormat = measure.numberFormat.copy(
                                kind = formatKind,
                                decimals = decimals.toIntOrNull()?.coerceIn(0, 8) ?: 2,
                                thousandsSeparator = thousands,
                                currencyCode = currencyCode.ifBlank { measure.numberFormat.currencyCode },
                            ),
                        ),
                    )
                },
                enabled = label.isNotBlank() &&
                    (measure.formula == null || formula.isNotBlank()) &&
                    (formatKind != NumberFormatKind.Currency || currencyCode.length == 3),
            ) { Text("Apply") }
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
        shape = RoundedCornerShape(12.dp),
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
                    supportingText = { Text(validation ?: "Use +, −, ×, ÷, parentheses, and the measure tokens below.") },
                    isError = validation != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsLabel("Insert measure")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    existing.forEach { measure ->
                        ChoicePill(measure.label, false) {
                            formula += if (formula.isBlank()) "[${measure.alias}]" else " [${measure.alias}]"
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
                        ),
                    )
                },
                enabled = label.isNotBlank() && formula.isNotBlank() && validation == null,
            ) { Text("Add") }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
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

private fun dateUnitLabel(unit: DateGroupUnit): String = when (unit) {
    DateGroupUnit.Year -> "Year"
    DateGroupUnit.Quarter -> "Quarter"
    DateGroupUnit.Month -> "Month"
    DateGroupUnit.IsoWeek -> "ISO week"
    DateGroupUnit.Day -> "Day"
}

private fun dimensionSortLabel(mode: DimensionSortMode): String = when (mode) {
    DimensionSortMode.SourceOrder -> "Source order"
    DimensionSortMode.LabelAscending -> "A → Z"
    DimensionSortMode.LabelDescending -> "Z → A"
    DimensionSortMode.ValueAscending -> "Value ↑"
    DimensionSortMode.ValueDescending -> "Value ↓"
}

private val showAsChoices = listOf(
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

private val modesNeedingBaseDimension = setOf(
    ShowAsMode.PercentParent,
    ShowAsMode.DifferenceFrom,
    ShowAsMode.PercentDifferenceFrom,
    ShowAsMode.RunningTotal,
    ShowAsMode.PercentRunningTotal,
    ShowAsMode.RankAscending,
    ShowAsMode.RankDescending,
)

private fun showAsLabel(mode: ShowAsMode): String = when (mode) {
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

private fun measureFunctionName(function: MeasureFn): String = when (function) {
    MeasureFn.Count -> "Count"
    MeasureFn.CountNumbers -> "Count numbers"
    MeasureFn.CountDistinct -> "Count distinct"
    MeasureFn.Sum -> "Sum"
    MeasureFn.Avg -> "Average"
    MeasureFn.Min -> "Minimum"
    MeasureFn.Max -> "Maximum"
    MeasureFn.Product -> "Product"
    MeasureFn.StdDev -> "StdDev"
    MeasureFn.StdDevPopulation -> "StdDevP"
    MeasureFn.Variance -> "Variance"
    MeasureFn.VariancePopulation -> "VarianceP"
}

private fun numberFormatKindLabel(kind: NumberFormatKind): String = when (kind) {
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
    onSave: (PivotFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember(filter.id) {
        mutableStateOf(
            when (filter) {
                is PivotFilter.Members -> FilterEditorKind.Members
                is PivotFilter.Label -> FilterEditorKind.Label
                is PivotFilter.Value -> if (filter.op in setOf(ValueFilterOp.Top, ValueFilterOp.Bottom)) FilterEditorKind.TopN else FilterEditorKind.Value
            },
        )
    }
    var pinned by remember(filter.id) { mutableStateOf(filter.pinned) }
    var textValue by remember(filter.id) {
        mutableStateOf(
            when (filter) {
                is PivotFilter.Label -> filter.value
                is PivotFilter.Value -> filter.value
                else -> ""
            },
        )
    }
    var secondValue by remember(filter.id) { mutableStateOf((filter as? PivotFilter.Value)?.secondValue.orEmpty()) }
    var labelOp by remember(filter.id) { mutableStateOf((filter as? PivotFilter.Label)?.op ?: LabelFilterOp.Contains) }
    var valueOp by remember(filter.id) { mutableStateOf((filter as? PivotFilter.Value)?.op ?: ValueFilterOp.GreaterThan) }
    var measureAlias by remember(filter.id) { mutableStateOf((filter as? PivotFilter.Value)?.measureAlias ?: measures.firstOrNull()?.alias.orEmpty()) }
    var count by remember(filter.id) { mutableStateOf(((filter as? PivotFilter.Value)?.count ?: 10).toString()) }
    var includedKeys by remember(filter.id) { mutableStateOf((filter as? PivotFilter.Members)?.includedKeys.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text("${filter.label} filter", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.widthIn(min = 500.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsLabel("Filter type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterEditorKind.entries.forEach { option ->
                        ChoicePill(filterKindLabel(option), kind == option) { kind = option }
                    }
                }
                when (kind) {
                    FilterEditorKind.Members -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pinned = !pinned },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = pinned, onCheckedChange = null)
                            Text("Pin this multi-select filter above the pivot")
                        }
                        Text(
                            if (includedKeys.isEmpty()) "All sample values selected" else "${includedKeys.size} selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            memberOptions.forEach { option ->
                                val checked = includedKeys.isEmpty() || option.key in includedKeys
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val baseline = if (includedKeys.isEmpty()) memberOptions.map { it.key }.toSet() else includedKeys
                                            val next = if (option.key in baseline) baseline - option.key else baseline + option.key
                                            if (next.isNotEmpty()) {
                                                includedKeys = if (next.size == memberOptions.size) emptySet() else next
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Text(option.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text("${option.count}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    FilterEditorKind.Label -> {
                        SettingsLabel("Match")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LabelFilterOp.entries.forEach { op -> ChoicePill(labelFilterLabel(op), labelOp == op) { labelOp = op } }
                        }
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            label = { Text("Text") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FilterEditorKind.Value,
                    FilterEditorKind.TopN,
                    -> {
                        SettingsLabel("Measure")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            measures.forEach { measure ->
                                ChoicePill(measure.label, measureAlias == measure.alias) { measureAlias = measure.alias }
                            }
                        }
                        if (kind == FilterEditorKind.TopN) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChoicePill("Top", valueOp == ValueFilterOp.Top) { valueOp = ValueFilterOp.Top }
                                ChoicePill("Bottom", valueOp == ValueFilterOp.Bottom) { valueOp = ValueFilterOp.Bottom }
                            }
                            OutlinedTextField(
                                value = count,
                                onValueChange = { count = it.filter(Char::isDigit).take(4) },
                                label = { Text("Items") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ValueFilterOp.entries.filterNot { it in setOf(ValueFilterOp.Top, ValueFilterOp.Bottom) }.forEach { op ->
                                    ChoicePill(valueFilterLabel(op), valueOp == op) { valueOp = op }
                                }
                            }
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                label = { Text("Value") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (valueOp == ValueFilterOp.Between) {
                                OutlinedTextField(
                                    value = secondValue,
                                    onValueChange = { secondValue = it },
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
            val enabled = when (kind) {
                FilterEditorKind.Members -> true
                FilterEditorKind.Label -> textValue.isNotBlank()
                FilterEditorKind.Value -> measureAlias.isNotBlank() && textValue.toBigDecimalOrNull() != null &&
                    (valueOp != ValueFilterOp.Between || secondValue.toBigDecimalOrNull() != null)
                FilterEditorKind.TopN -> measureAlias.isNotBlank() && (count.toIntOrNull() ?: 0) > 0
            }
            PrimaryButton(
                onClick = {
                    val updated = when (kind) {
                        FilterEditorKind.Members -> PivotFilter.Members(
                            filter.id,
                            filter.column,
                            filter.label,
                            includedKeys = includedKeys,
                            pinned = pinned,
                        )
                        FilterEditorKind.Label -> PivotFilter.Label(filter.id, filter.column, filter.label, labelOp, textValue.trim(), pinned)
                        FilterEditorKind.Value -> PivotFilter.Value(
                            filter.id,
                            filter.column,
                            filter.label,
                            measureAlias,
                            valueOp.takeUnless { it in setOf(ValueFilterOp.Top, ValueFilterOp.Bottom) } ?: ValueFilterOp.GreaterThan,
                            textValue.trim(),
                            secondValue.trim().ifEmpty { null },
                            pinned = false,
                        )
                        FilterEditorKind.TopN -> PivotFilter.Value(
                            filter.id,
                            filter.column,
                            filter.label,
                            measureAlias,
                            if (valueOp == ValueFilterOp.Bottom) ValueFilterOp.Bottom else ValueFilterOp.Top,
                            count = count.toIntOrNull()?.coerceAtLeast(1) ?: 10,
                            pinned = false,
                        )
                    }
                    onSave(updated)
                },
                enabled = enabled,
            ) { Text("Apply") }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class FilterEditorKind { Members, Label, Value, TopN }

private fun filterKindLabel(kind: FilterEditorKind): String = when (kind) {
    FilterEditorKind.Members -> "Members / slicer"
    FilterEditorKind.Label -> "Label"
    FilterEditorKind.Value -> "Value"
    FilterEditorKind.TopN -> "Top / Bottom N"
}

private fun labelFilterLabel(op: LabelFilterOp): String = when (op) {
    LabelFilterOp.Equals -> "Equals"
    LabelFilterOp.Contains -> "Contains"
    LabelFilterOp.StartsWith -> "Starts with"
    LabelFilterOp.EndsWith -> "Ends with"
}

private fun valueFilterLabel(op: ValueFilterOp): String = when (op) {
    ValueFilterOp.GreaterThan -> ">"
    ValueFilterOp.GreaterThanOrEqual -> "≥"
    ValueFilterOp.LessThan -> "<"
    ValueFilterOp.LessThanOrEqual -> "≤"
    ValueFilterOp.Between -> "Between"
    ValueFilterOp.Top -> "Top"
    ValueFilterOp.Bottom -> "Bottom"
}
