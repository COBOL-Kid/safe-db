package com.safedb.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.ScrollableMenuColumn
import com.safedb.viewmodel.QueryViewModel

@Composable
fun FilterRow(
    queryViewModel: QueryViewModel,
    filter: FilterSpec,
    path: List<Int>,
    showRemoveAction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val valueFocusRequester = remember(filter.id) { FocusRequester() }
    val focusRequested = queryViewModel.requestedFilterFocusId == filter.id
    val table = queryViewModel.canvasTables.find { it.alias == filter.tableAlias }
    val columns = table?.tableInfo?.columns.orEmpty()
    val columnInfo = columns.find { it.name == filter.column }
    val availableOps = columnInfo?.let { opsForColumn(it.dataType) }.orEmpty()
    val valueKind = filter.op.valueKind()
    val canFocusValue =
        when (valueKind) {
            ValueKind.None -> false
            ValueKind.Single ->
                columnInfo == null || literalKindForColumn(columnInfo.dataType) != LiteralKind.Bool
            ValueKind.Pair -> true
            ValueKind.List ->
                (filter.value as? FilterValue.ListValue)?.literals?.isNotEmpty() == true
        }
    val shouldFocusValue = focusRequested && canFocusValue
    if (focusRequested) {
        LaunchedEffect(filter.id, canFocusValue) {
            if (canFocusValue) valueFocusRequester.requestFocus()
            queryViewModel.consumeRequestedFilterFocus(filter.id)
        }
    }

    fun update(newFilter: FilterSpec) {
        queryViewModel.updateFilter(path, newFilter)
    }

    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterDropdown(
                label = "Table",
                value = table?.tableInfo?.name ?: filter.tableAlias,
                options = queryViewModel.canvasTables.map { it.alias to it.tableInfo.name },
                onSelected = { alias ->
                    val selected =
                        queryViewModel.canvasTables.find { it.alias == alias }
                            ?: return@FilterDropdown
                    val firstCol = selected.tableInfo.columns.firstOrNull() ?: return@FilterDropdown
                    val ops = opsForColumn(firstCol.dataType)
                    val op = if (filter.op in ops) filter.op else ops.first()
                    update(
                        filter.copy(
                            tableAlias = alias,
                            column = firstCol.name,
                            op = op,
                            value = rebuildValue(op, firstCol.dataType, filter.value),
                        )
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
                        )
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
                    update(filter.copy(op = op, value = rebuildValue(op, dataType, filter.value)))
                },
                modifier = Modifier.width(100.dp),
            )

            when (valueKind) {
                ValueKind.None -> Unit
                ValueKind.Single -> {
                    val single = (filter.value as? FilterValue.Single)?.literal
                    if (
                        columnInfo != null &&
                            literalKindForColumn(columnInfo.dataType) == LiteralKind.Bool
                    ) {
                        FilterDropdown(
                            label = "Value",
                            value = single?.text ?: "false",
                            options = listOf("true" to "true", "false" to "false"),
                            onSelected = { text ->
                                val kind = single?.kind ?: LiteralKind.Bool
                                update(
                                    filter.copy(
                                        value = FilterValue.Single(FilterLiteral(kind, text))
                                    )
                                )
                            },
                            modifier = Modifier.width(80.dp),
                        )
                    } else {
                        CompactTextInput(
                            value = single?.text.orEmpty(),
                            onValueChange = { text ->
                                val kind = single?.kind ?: LiteralKind.Text
                                update(
                                    filter.copy(
                                        value = FilterValue.Single(FilterLiteral(kind, text))
                                    )
                                )
                            },
                            modifier = Modifier.width(100.dp),
                            placeholder = "value",
                            focusRequester = if (shouldFocusValue) valueFocusRequester else null,
                        )
                    }
                }
                ValueKind.Pair -> {
                    val pair = filter.value as? FilterValue.Pair
                    CompactTextInput(
                        value = pair?.first?.text.orEmpty(),
                        onValueChange = { text ->
                            val first = pair?.first ?: FilterLiteral(LiteralKind.Text, "")
                            val second = pair?.second ?: FilterLiteral(LiteralKind.Text, "")
                            update(
                                filter.copy(
                                    value = FilterValue.Pair(first.copy(text = text), second)
                                )
                            )
                        },
                        modifier = Modifier.width(80.dp),
                        placeholder = "from",
                        focusRequester = if (shouldFocusValue) valueFocusRequester else null,
                    )
                    Text("and", style = MaterialTheme.typography.labelSmall)
                    CompactTextInput(
                        value = pair?.second?.text.orEmpty(),
                        onValueChange = { text ->
                            val first = pair?.first ?: FilterLiteral(LiteralKind.Text, "")
                            val second = pair?.second ?: FilterLiteral(LiteralKind.Text, "")
                            update(
                                filter.copy(
                                    value = FilterValue.Pair(first, second.copy(text = text))
                                )
                            )
                        },
                        modifier = Modifier.width(80.dp),
                        placeholder = "to",
                    )
                }
                ValueKind.List -> {
                    val listValue = filter.value as? FilterValue.ListValue
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listValue?.literals?.forEachIndexed { index, literal ->
                            CompactTextInput(
                                value = literal.text,
                                onValueChange = { text ->
                                    val items =
                                        listValue.literals.mapIndexed { i, lit ->
                                            if (i == index) lit.copy(text = text) else lit
                                        }
                                    update(filter.copy(value = FilterValue.ListValue(items)))
                                },
                                modifier = Modifier.width(72.dp),
                                placeholder = "value",
                                focusRequester =
                                    if (shouldFocusValue && index == 0) valueFocusRequester
                                    else null,
                            )
                            if ((listValue.literals.size) > 1) {
                                CompactIconButton(
                                    contentDescription = "Remove value",
                                    onClick = {
                                        val items =
                                            listValue.literals.filterIndexed { i, _ -> i != index }
                                        update(filter.copy(value = FilterValue.ListValue(items)))
                                    },
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove value")
                                }
                            }
                        }
                        CompactIconButton(
                            contentDescription = "Add value",
                            onClick = {
                                val current = listValue?.literals.orEmpty()
                                if (current.size < MAX_IN_LIST_SIZE) {
                                    val kind = current.firstOrNull()?.kind ?: LiteralKind.Text
                                    update(
                                        filter.copy(
                                            value =
                                                FilterValue.ListValue(
                                                    current + FilterLiteral(kind, "")
                                                )
                                        )
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add value")
                        }
                    }
                }
            }
        }
        if (showRemoveAction) {
            Row(modifier = Modifier.padding(top = 2.dp)) {
                CompactFilterBuilderAction(onClick = { queryViewModel.removeFilterNode(path) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Text("Remove", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun <T> FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        CompactSelectSurface(label = label, value = value, onClick = { expanded = true })
        SafeDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScrollableMenuColumn {
                for ((option, display) in options) {
                    MenuActionRow(
                        text = display,
                        selected = display == value,
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSelectSurface(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(30.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surface,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "$label:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CompactTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val textStyle =
        MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            modifier
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .height(30.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun CompactIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                .semantics { this.contentDescription = contentDescription }
                .clickable(onClick = onClick)
                .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun rebuildValue(op: FilterOp, dataType: String, oldValue: FilterValue?): FilterValue? {
    val kind = literalKindForColumn(dataType)
    return when (op.valueKind()) {
        ValueKind.None -> null
        ValueKind.Single -> FilterValue.Single(FilterLiteral(kind, extractSingleText(oldValue)))
        ValueKind.List -> {
            val items = extractList(oldValue)
            FilterValue.ListValue(items.ifEmpty { listOf(FilterLiteral(kind, "")) })
        }
        ValueKind.Pair -> {
            val (from, to) = extractPair(oldValue)
            FilterValue.Pair(FilterLiteral(kind, from), FilterLiteral(kind, to))
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
