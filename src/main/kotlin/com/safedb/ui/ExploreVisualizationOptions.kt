package com.safedb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotGrouping
import com.safedb.explore.SortDir
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationSortTarget
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal
import com.safedb.ui.components.SelectablePill

@Composable
internal fun VisualizationOptions(
    config: VisualizationConfig,
    effectiveType: ChartType?,
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
                            config.copy(x = config.x?.copy(grouping = PivotGrouping.Date(unit))),
                        )
                    }
                }
            }
        }
        if (xField?.category?.isNumeric() == true && effectiveType == ChartType.Histogram) {
            val current = config.x?.grouping as? PivotGrouping.NumberBin
            val rawBinSize = current?.size.orEmpty()
            val binSizeError =
                rawBinSize.isNotBlank() &&
                    rawBinSize.trim().toBigDecimalOrNull()?.let {
                        it > java.math.BigDecimal.ZERO
                    } != true
            OutlinedTextField(
                value = rawBinSize,
                onValueChange = { value ->
                    val nextGrouping =
                        if (value.isBlank() && current?.start.isNullOrBlank()) {
                            PivotGrouping.Exact
                        } else {
                            current?.copy(size = value) ?: PivotGrouping.NumberBin(value)
                        }
                    onChange(config.copy(x = config.x?.copy(grouping = nextGrouping)))
                },
                label = { Text("Bin size (blank = automatic)") },
                isError = binSizeError,
                supportingText =
                    if (binSizeError) {
                        { Text("Enter a positive number") }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (effectiveType == ChartType.Bar) {
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
                                                numberFormat = it.numberFormat.copy(kind = kind),
                                            )
                                        } else {
                                            it
                                        }
                                    },
                            ),
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
                                                        it.numberFormat.copy(decimals = decimals),
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                ),
                            )
                        }
                    },
                    label = { Text("Decimal places") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (effectiveType == ChartType.Bar || effectiveType == ChartType.Line) {
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
        }
        if (effectiveType == ChartType.Bar) {
            Text("Top categories", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(10, 20, 50, 100).forEach { count ->
                    SelectablePill(count.toString(), config.topN == count) {
                        onChange(config.copy(topN = count))
                    }
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
        if (effectiveType == ChartType.Bar || effectiveType == ChartType.Histogram) {
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
}
