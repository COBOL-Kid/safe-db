package com.safedb.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.PivotGrouping
import com.safedb.explore.WorksheetFilter
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetGroup
import com.safedb.explore.pivotCellKey
import com.safedb.model.QueryResult
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SectionLabel
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.SafeDbTheme
import java.util.UUID

@Composable
internal fun WorksheetFilterDialog(
    column: String,
    dataType: String,
    existing: WorksheetFilter?,
    sample: QueryResult,
    onSave: (WorksheetFilter) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var op by remember { mutableStateOf(existing?.op ?: defaultFilterOp(dataType)) }
    var value by remember { mutableStateOf(existing?.value.orEmpty()) }
    var second by remember { mutableStateOf(existing?.secondValue.orEmpty()) }
    var members by remember { mutableStateOf(existing?.includedKeys.orEmpty()) }
    val index = sample.columns.indexOfFirst { it.name == column }
    val memberOptions =
        if (index < 0) emptyList()
        else sample.rows.mapNotNull { it.getOrNull(index) }.distinctBy(::pivotCellKey).take(100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter ${displayColumnName(sample.columns.first { it.name == column })}") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectRow("Condition", filterOps(dataType), op, { it.name.toDisplayWords() }) {
                    op = it
                }
                if (op == WorksheetFilterOp.Members) {
                    Column(modifier = Modifier.height(220.dp)) {
                        memberOptions.forEach { cell ->
                            val key = pivotCellKey(cell)
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        members =
                                            if (key in members) members - key else members + key
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = key in members, onCheckedChange = null)
                                Text(
                                    formatCell(cell),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else if (op !in setOf(WorksheetFilterOp.IsNull, WorksheetFilterOp.IsNotNull)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (op == WorksheetFilterOp.Between)
                        OutlinedTextField(
                            value = second,
                            onValueChange = { second = it },
                            label = { Text("Upper value") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        WorksheetFilter(
                            existing?.id ?: UUID.randomUUID().toString(),
                            column,
                            op = op,
                            value = value,
                            secondValue = second.ifBlank { null },
                            includedKeys = members,
                        )
                    )
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove") } }
                SecondaryButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun WorksheetGroupDialog(
    column: String,
    displayLabel: String,
    dataType: String,
    existing: WorksheetGroup?,
    onSave: (WorksheetGroup) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var grouping by remember { mutableStateOf(existing?.grouping ?: PivotGrouping.Exact) }
    var binSize by remember { mutableStateOf((grouping as? PivotGrouping.NumberBin)?.size ?: "10") }
    val choices =
        when {
            isTemporalType(dataType) ->
                listOf<PivotGrouping>(PivotGrouping.Exact) +
                    DateGroupUnit.entries.map(PivotGrouping::Date)
            isNumericType(dataType) -> listOf(PivotGrouping.Exact, PivotGrouping.NumberBin(binSize))
            else -> listOf(PivotGrouping.Exact)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group $displayLabel") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Group values by", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    choices.forEach { choice ->
                        SelectPill(groupingLabel(choice), sameGroupingKind(grouping, choice)) {
                            grouping = choice
                        }
                    }
                }
                if (grouping is PivotGrouping.NumberBin) {
                    OutlinedTextField(
                        value = binSize,
                        onValueChange = {
                            binSize = it
                            grouping = PivotGrouping.NumberBin(it)
                        },
                        label = { Text("Bin size") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        WorksheetGroup(
                            existing?.id ?: UUID.randomUUID().toString(),
                            column,
                            label = displayLabel,
                            grouping = grouping,
                        )
                    )
                },
                enabled =
                    grouping !is PivotGrouping.NumberBin ||
                        binSize.toBigDecimalOrNull()?.signum() == 1,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove") } }
                SecondaryButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun CompactWorksheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val borderColor =
            if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outlineVariant
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
            cursorBrush = SolidColor(SafeDbTheme.colors.actionPrimary),
            modifier =
                modifier
                    .height(38.dp)
                    .border(1.dp, borderColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun <T> SelectRow(
    label: String,
    choices: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { choice ->
                SelectPill(display(choice), choice == selected) { onSelect(choice) }
            }
        }
    }
}

@Composable
internal fun SelectPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SelectablePill(label, selected, onClick, modifier)
}
