package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.VisualizationSortTarget
import com.safedb.explore.VisualizationTemplate
import com.safedb.explore.VisualizationTemplateBuildResult
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.suggestedVisualizationTemplates
import com.safedb.explore.visualizationTemplates
import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import com.safedb.model.TableRef
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.MenuSectionLabel
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.ScrollableMenuColumn
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SectionLabel
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.MemberOption
import java.util.UUID

@Composable
internal fun VisualizationConfigPanel(
    config: VisualizationConfig,
    sample: QueryResult,
    tables: List<TableRef>,
    fields: List<ExploreFieldOption>,
    memberOptionsFor: (String) -> List<MemberOption>,
    onConfigChange: (VisualizationConfig) -> Unit,
    onApplyTemplate: (VisualizationConfig) -> Unit,
    onReset: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var templatesOpen by remember { mutableStateOf(false) }
    var optionsExpanded by remember { mutableStateOf(false) }
    var editingFilter by remember { mutableStateOf<PivotFilter?>(null) }
    var transitionMessage by remember { mutableStateOf<String?>(null) }
    val suggested = remember(sample, tables) { suggestedVisualizationTemplates(sample, tables, 5) }

    editingFilter?.let { filter ->
        FilterSettingsDialog(
            filter = filter,
            measures = config.values.map(::asPivotMeasure),
            memberOptions = memberOptionsFor(filter.column),
            showPinnedOption = false,
            onSave = { updated ->
                onConfigChange(
                    config.copy(
                        filters = config.filters.map { if (it.id == updated.id) updated else it }
                    )
                )
                editingFilter = null
            },
            onDismiss = { editingFilter = null },
        )
    }
    if (templatesOpen) {
        VisualizationTemplateDialog(
            templates = remember(sample, tables) { visualizationTemplates(sample, tables) },
            onApply = {
                templatesOpen = false
                onApplyTemplate(it)
            },
            onDismiss = { templatesOpen = false },
        )
    }

    Surface(modifier = modifier, color = SafeDbTheme.colors.workspacePanel) {
        VisualizationConfigScroller {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Build chart", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (config.isConfigured()) visualizationConfigSummary(config)
                        else "Start with a suggestion or choose fields",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { templatesOpen = true }) { Text("Templates") }
                TextButton(onClick = onReset, enabled = config.isConfigured()) { Text("Reset") }
                IconButton(onClick = onHide) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Hide Visualization sidebar",
                    )
                }
            }

            if (!config.isConfigured() && suggested.isNotEmpty()) {
                ConfigSection("Suggested for this sample") {
                    suggested.take(3).forEach { template ->
                        val ready = template.result as VisualizationTemplateBuildResult.Ready
                        TemplateCard(template, false) { onApplyTemplate(ready.config) }
                    }
                }
            }

            ConfigSection("Chart") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChartType.entries.forEach { type ->
                        SelectablePill(chartTypeLabel(type), config.chartType == type) {
                            val next = config.forChartType(type, fields)
                            val removed = buildList {
                                if (config.x != null && next.x == null) add("X")
                                if (config.values.isNotEmpty() && next.values.isEmpty())
                                    add("values")
                                if (config.series != null && next.series == null) add("series")
                                if (config.size != null && next.size == null) add("size")
                            }
                            transitionMessage =
                                removed
                                    .takeIf { it.isNotEmpty() }
                                    ?.let {
                                        "Removed incompatible ${it.joinToString()} assignment${if (it.size == 1) "" else "s"}."
                                    }
                            onConfigChange(next)
                        }
                    }
                }
                transitionMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (config.chartType != ChartType.Kpi) {
                ConfigSection(if (config.chartType == ChartType.Bar) "Category / X" else "X") {
                    config.x?.let { field ->
                        VisualizationFieldChip(
                            label = field.label,
                            supporting =
                                fields.firstOrNull { it.column == field.column }?.supportingText(),
                            onRemove = { onConfigChange(config.copy(x = null)) },
                        )
                    }
                    VisualizationFieldPicker(
                        label = if (config.x == null) "Add X field" else "Replace X field",
                        fields = fields,
                        compatible = { field -> xCompatible(config.chartType, field) },
                        incompatibleReason = {
                            "Not compatible with ${chartTypeLabel(config.chartType).lowercase()}"
                        },
                        onSelect = { field ->
                            val grouping =
                                if (field.category.isTemporal()) {
                                    PivotGrouping.Date(DateGroupUnit.Month)
                                } else {
                                    PivotGrouping.Exact
                                }
                            onConfigChange(
                                config.copy(
                                    x = VisualizationField(field.column, field.label, grouping)
                                )
                            )
                        },
                    )
                }
            }

            if (config.chartType != ChartType.Histogram) {
                ConfigSection(if (config.chartType == ChartType.Scatter) "Y value" else "Values") {
                    config.values.forEach { value ->
                        VisualizationValueChip(
                            value = value,
                            field = fields.firstOrNull { it.column == value.sourceColumn },
                            allowAggregation = config.chartType != ChartType.Scatter,
                            onChange = { updated ->
                                onConfigChange(
                                    config.copy(
                                        values =
                                            config.values.map {
                                                if (it.alias == value.alias) updated else it
                                            }
                                    )
                                )
                            },
                            onRemove = {
                                onConfigChange(config.copy(values = config.values - value))
                            },
                        )
                    }
                    VisualizationValuePicker(
                        chartType = config.chartType,
                        fields = fields,
                        countAllowed = config.chartType != ChartType.Scatter,
                        onSelect = { value ->
                            val values =
                                if (config.chartType in setOf(ChartType.Scatter, ChartType.Kpi)) {
                                    listOf(value)
                                } else {
                                    config.values + value
                                }
                            onConfigChange(
                                config.copy(
                                    values = values,
                                    series = config.series.takeIf { values.size <= 1 },
                                )
                            )
                        },
                    )
                }
            }

            if (
                config.chartType in
                    setOf(ChartType.Auto, ChartType.Bar, ChartType.Line, ChartType.Scatter)
            ) {
                ConfigSection("Series") {
                    config.series?.let { field ->
                        VisualizationFieldChip(
                            field.label,
                            fields.firstOrNull { it.column == field.column }?.supportingText(),
                            onRemove = { onConfigChange(config.copy(series = null)) },
                        )
                    }
                    VisualizationFieldPicker(
                        label = if (config.series == null) "Add series" else "Replace series",
                        fields = fields,
                        compatible = {
                            !it.category.isNumeric() &&
                                it.category !in setOf(ColumnCategory.Binary, ColumnCategory.Json)
                        },
                        incompatibleReason = { "Series fields must be categorical" },
                        onSelect = {
                            onConfigChange(
                                config.copy(
                                    series = VisualizationField(it.column, it.label),
                                    values = config.values.take(1),
                                )
                            )
                        },
                    )
                }
            }

            if (config.chartType == ChartType.Scatter) {
                ConfigSection("Size") {
                    config.size?.let { field ->
                        VisualizationFieldChip(
                            field.label,
                            fields.firstOrNull { it.column == field.column }?.supportingText(),
                            onRemove = { onConfigChange(config.copy(size = null)) },
                        )
                    }
                    VisualizationFieldPicker(
                        label = if (config.size == null) "Add size" else "Replace size",
                        fields = fields,
                        compatible = { it.category.isNumeric() },
                        incompatibleReason = { "Size needs a numeric field" },
                        onSelect = {
                            onConfigChange(
                                config.copy(size = VisualizationField(it.column, it.label))
                            )
                        },
                    )
                }
            }

            ConfigSection("Filters") {
                config.filters.forEach { filter ->
                    VisualizationFieldChip(
                        filter.label,
                        filterSupportingText(
                            filter,
                            (filter as? PivotFilter.Members)?.let {
                                memberOptionsFor(it.column).size
                            },
                        ),
                        onRemove = {
                            onConfigChange(config.copy(filters = config.filters - filter))
                        },
                        onClick = { editingFilter = filter },
                    )
                }
                VisualizationFieldPicker(
                    label = "Add filter",
                    fields =
                        fields.filterNot { field ->
                            config.filters.any { it.column == field.column }
                        },
                    compatible = { true },
                    incompatibleReason = { "" },
                    onSelect = { field ->
                        onConfigChange(
                            config.copy(
                                filters =
                                    config.filters +
                                        PivotFilter.Members(
                                            id = UUID.randomUUID().toString(),
                                            column = field.column,
                                            label = field.label,
                                        )
                            )
                        )
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { optionsExpanded = !optionsExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Options", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${config.sort.target.name.lowercase()} sort · Top ${config.topN}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (optionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (optionsExpanded) {
                VisualizationOptions(config, fields, onConfigChange)
            }
        }
    }
}

@Composable
private fun VisualizationConfigScroller(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun VisualizationOptions(
    config: VisualizationConfig,
    fields: List<ExploreFieldOption>,
    onChange: (VisualizationConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val xField =
            config.x?.let { selected -> fields.firstOrNull { it.column == selected.column } }
        if (xField?.category?.isTemporal() == true) {
            Text("Date grouping", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                DateGroupUnit.entries.forEach { unit ->
                    SelectablePill(
                        unit.name.replace("Iso", "ISO "),
                        config.x?.grouping == PivotGrouping.Date(unit),
                    ) {
                        onChange(
                            config.copy(x = config.x?.copy(grouping = PivotGrouping.Date(unit)))
                        )
                    }
                }
            }
        }
        if (xField?.category?.isNumeric() == true && config.chartType == ChartType.Histogram) {
            val current = config.x?.grouping as? PivotGrouping.NumberBin
            OutlinedTextField(
                value = current?.size.orEmpty(),
                onValueChange = { value ->
                    onChange(
                        config.copy(x = config.x?.copy(grouping = PivotGrouping.NumberBin(value)))
                    )
                },
                label = { Text("Bin size (blank = automatic)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (config.chartType == ChartType.Bar) {
            Text("Bars", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                BarArrangement.entries.forEach { arrangement ->
                    SelectablePill(arrangement.name, config.barArrangement == arrangement) {
                        onChange(config.copy(barArrangement = arrangement))
                    }
                }
                BarOrientation.entries.forEach { orientation ->
                    SelectablePill(orientation.name, config.barOrientation == orientation) {
                        onChange(config.copy(barOrientation = orientation))
                    }
                }
            }
        }
        config.values.forEach { value ->
            Text("${value.label} format", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                NumberFormatKind.entries.forEach { kind ->
                    SelectablePill(kind.name, value.numberFormat.kind == kind) {
                        onChange(
                            config.copy(
                                values =
                                    config.values.map {
                                        if (it.alias == value.alias) {
                                            it.copy(
                                                numberFormat = it.numberFormat.copy(kind = kind)
                                            )
                                        } else {
                                            it
                                        }
                                    }
                            )
                        )
                    }
                }
            }
            if (value.numberFormat.kind != NumberFormatKind.Auto) {
                OutlinedTextField(
                    value = value.numberFormat.decimals.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.coerceIn(0, 8)?.let { decimals ->
                            onChange(
                                config.copy(
                                    values =
                                        config.values.map {
                                            if (it.alias == value.alias) {
                                                it.copy(
                                                    numberFormat =
                                                        it.numberFormat.copy(decimals = decimals)
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                )
                            )
                        }
                    },
                    label = { Text("Decimal places") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text("Sort", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            VisualizationSortTarget.entries.forEach { target ->
                SelectablePill(target.name, config.sort.target == target) {
                    onChange(config.copy(sort = config.sort.copy(target = target)))
                }
            }
            SortDir.entries.forEach { dir ->
                SelectablePill(dir.name, config.sort.dir == dir) {
                    onChange(config.copy(sort = config.sort.copy(dir = dir)))
                }
            }
        }
        Text("Top categories", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(10, 20, 50, 100).forEach { count ->
                SelectablePill(count.toString(), config.topN == count) {
                    onChange(config.copy(topN = count))
                }
            }
        }
        OutlinedTextField(
            value = config.title,
            onValueChange = { onChange(config.copy(title = it)) },
            label = { Text("Chart title (automatic when blank)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    onChange(config.copy(showLabels = !config.showLabels))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(config.showLabels, onCheckedChange = null)
            Text("Show value labels")
        }
    }
}

@Composable
private fun VisualizationValueChip(
    value: VisualizationMeasure,
    field: ExploreFieldOption?,
    allowAggregation: Boolean,
    onChange: (VisualizationMeasure) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    value.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    field?.supportingText() ?: "All rows",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (allowAggregation && field != null) {
                AggregationPicker(value, field) { fn ->
                    onChange(value.copy(fn = fn, label = "${fn.shortLabel} ${field.label}"))
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${value.label}",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AggregationPicker(
    value: VisualizationMeasure,
    field: ExploreFieldOption,
    onSelect: (MeasureFn) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(value.fn.label) }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 190.dp,
        ) {
            availablePlottableMeasureFunctions(field).forEach { fn ->
                MenuActionRow(
                    text = fn.label,
                    selected = value.fn == fn,
                    onClick = {
                        expanded = false
                        onSelect(fn)
                    },
                )
            }
        }
    }
}

@Composable
private fun VisualizationValuePicker(
    chartType: ChartType,
    fields: List<ExploreFieldOption>,
    countAllowed: Boolean,
    onSelect: (VisualizationMeasure) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val numericOnly = chartType == ChartType.Scatter
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Add value", modifier = Modifier.padding(start = 4.dp))
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            minWidth = 286.dp,
        ) {
            Column(modifier = Modifier.widthIn(min = 286.dp)) {
                if (countAllowed) {
                    MenuActionRow(
                        text = "Count rows",
                        supportingText = "Number of sample rows",
                        onClick = {
                            expanded = false
                            onSelect(
                                VisualizationMeasure.countRows(
                                    "count_${UUID.randomUUID().toString().take(8)}"
                                )
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ExploreSearchField(query, onQueryChange = { query = it })
                ScrollableMenuColumn(maxHeight = 280.dp) {
                    groupExploreFields(fields.filter { it.matchesSearch(query) }).forEach { group ->
                        MenuSectionLabel(group.label)
                        group.fields.forEach { field ->
                            val compatible = field.category.isNumeric() || !numericOnly
                            MenuActionRow(
                                text = field.label,
                                supportingText =
                                    if (compatible) field.supportingText()
                                    else "${field.supportingText()} · Numeric fields only",
                                modifier = Modifier.alpha(if (compatible) 1f else 0.45f),
                                onClick = {
                                    if (compatible) {
                                        expanded = false
                                        query = ""
                                        val raw = chartType == ChartType.Scatter
                                        val function =
                                            if (field.category.isNumeric()) MeasureFn.Sum
                                            else MeasureFn.CountDistinct
                                        onSelect(
                                            VisualizationMeasure(
                                                alias =
                                                    "${if (raw) "raw" else function.name.lowercase()}_${field.column}_${UUID.randomUUID().toString().take(6)}",
                                                fn = function,
                                                sourceColumn = field.column,
                                                label =
                                                    if (raw) field.label
                                                    else "${function.shortLabel} ${field.label}",
                                                aggregate = !raw,
                                            )
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualizationFieldPicker(
    label: String,
    fields: List<ExploreFieldOption>,
    compatible: (ExploreFieldOption) -> Boolean,
    incompatibleReason: (ExploreFieldOption) -> String,
    onSelect: (ExploreFieldOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        TextButton(onClick = { expanded = true }, enabled = fields.isNotEmpty()) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, modifier = Modifier.padding(start = 4.dp))
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            minWidth = 286.dp,
        ) {
            Column(modifier = Modifier.widthIn(min = 286.dp)) {
                ExploreSearchField(query, onQueryChange = { query = it })
                ScrollableMenuColumn {
                    groupExploreFields(fields.filter { it.matchesSearch(query) }).forEach { group ->
                        MenuSectionLabel(group.label)
                        group.fields.forEach { field ->
                            val enabled = compatible(field)
                            MenuActionRow(
                                text = field.label,
                                supportingText =
                                    listOfNotNull(
                                            field.supportingText(),
                                            incompatibleReason(field).takeIf { !enabled },
                                        )
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
                                onClick = {
                                    if (enabled) {
                                        expanded = false
                                        query = ""
                                        onSelect(field)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualizationFieldChip(
    label: String,
    supporting: String?,
    onRemove: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(3.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove $label",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfigSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun VisualizationTemplateDialog(
    templates: List<VisualizationTemplate>,
    onApply: (VisualizationConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<VisualizationTemplate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Visualization templates") },
        text = {
            Column(
                modifier =
                    Modifier.widthIn(min = 620.dp)
                        .heightIn(max = 590.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                templates.forEach { template ->
                    val ready = template.result is VisualizationTemplateBuildResult.Ready
                    TemplateCard(template, selected?.id == template.id, enabled = ready) {
                        if (ready) selected = template
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    val config =
                        (selected?.result as? VisualizationTemplateBuildResult.Ready)?.config
                            ?: return@PrimaryButton
                    onApply(config)
                },
                enabled = selected?.result is VisualizationTemplateBuildResult.Ready,
            ) {
                Text("Apply template")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TemplateCard(
    template: VisualizationTemplate,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val reason = (template.result as? VisualizationTemplateBuildResult.Unavailable)?.reason
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(3.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                1.dp,
                if (selected) SafeDbTheme.colors.actionPrimary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                template.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                template.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                reason ?: template.preview,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (reason != null) MaterialTheme.colorScheme.error
                    else SafeDbTheme.colors.actionPrimary,
            )
        }
    }
}

internal fun VisualizationConfig.forChartType(
    type: ChartType,
    fields: List<ExploreFieldOption>,
): VisualizationConfig {
    fun numeric(column: String?): Boolean =
        fields.firstOrNull { it.column == column }?.category?.isNumeric() == true
    return when (type) {
        ChartType.Histogram ->
            copy(
                chartType = type,
                x = x?.takeIf { numeric(it.column) },
                values = emptyList(),
                series = null,
                size = null,
            )
        ChartType.Kpi ->
            copy(
                chartType = type,
                x = null,
                values = values.take(1).map { it.copy(aggregate = true) },
                series = null,
                size = null,
            )
        ChartType.Scatter ->
            copy(
                chartType = type,
                x = x?.takeIf { numeric(it.column) },
                values =
                    values
                        .take(1)
                        .filter { numeric(it.sourceColumn) }
                        .map {
                            it.copy(
                                aggregate = false,
                                label = it.sourceColumn?.let(::displayColumnLabel) ?: it.label,
                            )
                        },
                series = series,
                size = size?.takeIf { numeric(it.column) },
            )
        ChartType.Bar,
        ChartType.Line ->
            copy(chartType = type, values = values.map { it.copy(aggregate = true) }, size = null)
        ChartType.Auto -> copy(chartType = type)
    }
}

private fun xCompatible(type: ChartType, field: ExploreFieldOption): Boolean =
    when (type) {
        ChartType.Scatter,
        ChartType.Histogram -> field.category.isNumeric()
        ChartType.Kpi -> false
        else -> field.category !in setOf(ColumnCategory.Binary, ColumnCategory.Json)
    }

private fun asPivotMeasure(value: VisualizationMeasure) =
    PivotMeasure(
        alias = value.alias,
        fn = value.fn,
        sourceColumn = value.sourceColumn,
        label = value.label,
        numberFormat = value.numberFormat,
    )

private fun chartTypeLabel(type: ChartType): String =
    when (type) {
        ChartType.Auto -> "Auto"
        ChartType.Bar -> "Bar"
        ChartType.Line -> "Line"
        ChartType.Scatter -> "Scatter"
        ChartType.Histogram -> "Histogram"
        ChartType.Kpi -> "KPI"
    }

private fun visualizationConfigSummary(config: VisualizationConfig): String {
    val values = config.values.joinToString(", ") { it.label }
    return listOfNotNull(
            chartTypeLabel(config.chartType),
            config.x?.label,
            values.takeIf { it.isNotBlank() },
            config.series?.let { "by ${it.label}" },
        )
        .joinToString(" · ")
}
