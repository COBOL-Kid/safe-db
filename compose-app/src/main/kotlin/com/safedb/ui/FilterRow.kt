package com.safedb.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.LiteralKind
import com.safedb.model.ValueKind
import com.safedb.model.valueKind
import com.safedb.query.MAX_IN_LIST_SIZE
import com.safedb.query.literalKindForColumn
import com.safedb.query.opLabel
import com.safedb.query.opsForColumn
import com.safedb.viewmodel.QueryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRow(
    queryViewModel: QueryViewModel,
    filter: FilterSpec,
    path: List<Int>,
    modifier: Modifier = Modifier,
) {
    val table = queryViewModel.canvasTables.find { it.alias == filter.tableAlias }
    val columns = table?.tableInfo?.columns.orEmpty()
    val columnInfo = columns.find { it.name == filter.column }
    val availableOps = columnInfo?.let { opsForColumn(it.dataType) }.orEmpty()
    val valueKind = filter.op.valueKind()

    fun update(newFilter: FilterSpec) {
        queryViewModel.updateFilter(path, newFilter)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterDropdown(
            label = "Table",
            value = table?.tableInfo?.name ?: filter.tableAlias,
            options = queryViewModel.canvasTables.map { it.alias to it.tableInfo.name },
            onSelected = { alias ->
                val selected = queryViewModel.canvasTables.find { it.alias == alias } ?: return@FilterDropdown
                val firstCol = selected.tableInfo.columns.firstOrNull() ?: return@FilterDropdown
                val ops = opsForColumn(firstCol.dataType)
                val op = if (filter.op in ops) filter.op else ops.first()
                update(
                    filter.copy(
                        tableAlias = alias,
                        column = firstCol.name,
                        op = op,
                        value = rebuildValue(op, firstCol.dataType, filter.value),
                    ),
                )
            },
            modifier = Modifier.width(110.dp),
        )

        FilterDropdown(
            label = "Column",
            value = filter.column,
            options = columns.map { it.name to it.name },
            onSelected = { colName ->
                val col = columns.find { it.name == colName } ?: return@FilterDropdown
                val ops = opsForColumn(col.dataType)
                val op = if (filter.op in ops) filter.op else ops.first()
                update(
                    filter.copy(
                        column = colName,
                        op = op,
                        value = rebuildValue(op, col.dataType, filter.value),
                    ),
                )
            },
            modifier = Modifier.width(110.dp),
        )

        FilterDropdown(
            label = "Op",
            value = opLabel(filter.op),
            options = availableOps.map { it to opLabel(it) },
            onSelected = { op ->
                val dataType = columnInfo?.dataType ?: return@FilterDropdown
                update(
                    filter.copy(
                        op = op,
                        value = rebuildValue(op, dataType, filter.value),
                    ),
                )
            },
            modifier = Modifier.width(100.dp),
        )

        when (valueKind) {
            ValueKind.None -> Unit
            ValueKind.Single -> {
                val single = (filter.value as? FilterValue.Single)?.literal
                if (columnInfo != null && literalKindForColumn(columnInfo.dataType) == LiteralKind.Bool) {
                    FilterDropdown(
                        label = "Value",
                        value = single?.text ?: "false",
                        options = listOf("true" to "true", "false" to "false"),
                        onSelected = { text ->
                            val kind = single?.kind ?: LiteralKind.Bool
                            update(filter.copy(value = FilterValue.Single(FilterLiteral(kind, text))))
                        },
                        modifier = Modifier.width(80.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = single?.text.orEmpty(),
                        onValueChange = { text ->
                            val kind = single?.kind ?: LiteralKind.Text
                            update(filter.copy(value = FilterValue.Single(FilterLiteral(kind, text))))
                        },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        placeholder = { Text("value", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            ValueKind.Pair -> {
                val pair = filter.value as? FilterValue.Pair
                OutlinedTextField(
                    value = pair?.first?.text.orEmpty(),
                    onValueChange = { text ->
                        val first = pair?.first ?: FilterLiteral(LiteralKind.Text, "")
                        val second = pair?.second ?: FilterLiteral(LiteralKind.Text, "")
                        update(filter.copy(value = FilterValue.Pair(first.copy(text = text), second)))
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    placeholder = { Text("from", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                Text("and", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = pair?.second?.text.orEmpty(),
                    onValueChange = { text ->
                        val first = pair?.first ?: FilterLiteral(LiteralKind.Text, "")
                        val second = pair?.second ?: FilterLiteral(LiteralKind.Text, "")
                        update(filter.copy(value = FilterValue.Pair(first, second.copy(text = text))))
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    placeholder = { Text("to", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.labelSmall,
                )
            }
            ValueKind.List -> {
                val listValue = filter.value as? FilterValue.ListValue
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listValue?.literals?.forEachIndexed { index, literal ->
                        OutlinedTextField(
                            value = literal.text,
                            onValueChange = { text ->
                                val items = listValue.literals.mapIndexed { i, lit ->
                                    if (i == index) lit.copy(text = text) else lit
                                }
                                update(filter.copy(value = FilterValue.ListValue(items)))
                            },
                            modifier = Modifier.width(72.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                        if ((listValue.literals.size) > 1) {
                            IconButton(onClick = {
                                val items = listValue.literals.filterIndexed { i, _ -> i != index }
                                update(filter.copy(value = FilterValue.ListValue(items)))
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove value")
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            val current = listValue?.literals.orEmpty()
                            if (current.size >= MAX_IN_LIST_SIZE) return@IconButton
                            val kind = current.firstOrNull()?.kind ?: LiteralKind.Text
                            update(
                                filter.copy(
                                    value = FilterValue.ListValue(current + FilterLiteral(kind, "")),
                                ),
                            )
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add value")
                    }
                }
            }
        }

        IconButton(onClick = { queryViewModel.removeFilterNode(path) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove filter")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        TextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            textStyle = MaterialTheme.typography.labelSmall,
            singleLine = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((option, display) in options) {
                DropdownMenuItem(
                    text = { Text(display, style = MaterialTheme.typography.labelSmall) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun rebuildValue(op: FilterOp, dataType: String, oldValue: FilterValue?): FilterValue? {
    val kind = literalKindForColumn(dataType)
    return when (op.valueKind()) {
        ValueKind.None -> null
        ValueKind.Single -> FilterValue.Single(
            FilterLiteral(kind, extractSingleText(oldValue)),
        )
        ValueKind.List -> {
            val items = extractList(oldValue)
            FilterValue.ListValue(
                if (items.isNotEmpty()) items else listOf(FilterLiteral(kind, "")),
            )
        }
        ValueKind.Pair -> {
            val (from, to) = extractPair(oldValue)
            FilterValue.Pair(
                FilterLiteral(kind, from),
                FilterLiteral(kind, to),
            )
        }
    }
}

private fun extractSingleText(value: FilterValue?): String =
    (value as? FilterValue.Single)?.literal?.text.orEmpty()

private fun extractList(value: FilterValue?): List<FilterLiteral> =
    (value as? FilterValue.ListValue)?.literals.orEmpty()

private fun extractPair(value: FilterValue?): Pair<String, String> {
    val pair = value as? FilterValue.Pair
    return pair?.first?.text.orEmpty() to pair?.second?.text.orEmpty()
}
