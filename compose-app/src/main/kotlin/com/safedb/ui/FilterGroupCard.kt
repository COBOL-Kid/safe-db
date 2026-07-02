package com.safedb.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.ValueKind
import com.safedb.model.valueKind
import com.safedb.query.MAX_FILTER_DEPTH
import com.safedb.query.literalKindForColumn
import com.safedb.query.opsForColumn
import com.safedb.viewmodel.QueryViewModel

@Composable
fun FilterGroupCard(
    queryViewModel: QueryViewModel,
    group: FilterGroup,
    path: List<Int>,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    val atMaxDepth = depth >= MAX_FILTER_DEPTH - 1
    val backgroundModifier = if (depth > 0) {
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(6.dp)
    } else {
        Modifier
    }

    Column(
        modifier = modifier.then(backgroundModifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = if (depth > 0) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.children.forEachIndexed { index, child ->
                val childPath = path + index
                if (index > 0) {
                    val connector = queryViewModel.getConnectorForChild(childPath)
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Button(
                            onClick = { queryViewModel.toggleChildConnector(childPath) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (connector == GroupConnector.And) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                },
                            ),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(
                                connector.name.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                when (child) {
                    is FilterNode.Leaf -> FilterRow(
                        queryViewModel = queryViewModel,
                        filter = child.spec,
                        path = childPath,
                    )
                    is FilterNode.Group -> FilterGroupCard(
                        queryViewModel = queryViewModel,
                        group = child.group,
                        path = childPath,
                        depth = depth + 1,
                    )
                }
            }
        }

        if (group.children.isEmpty()) {
            Text(
                "No conditions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier.padding(start = if (depth > 0) 12.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { addFilter(queryViewModel, path) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Filter", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = { queryViewModel.addGroupToGroup(path, GroupConnector.And) },
                enabled = !atMaxDepth,
            ) {
                Icon(Icons.Default.GridView, contentDescription = null)
                Text("Group", style = MaterialTheme.typography.labelSmall)
            }
            if (depth > 0) {
                IconButton(
                    onClick = { queryViewModel.removeFilterNode(path) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove group")
                }
            }
        }
    }
}

private fun addFilter(queryViewModel: QueryViewModel, path: List<Int>) {
    val table = queryViewModel.canvasTables.firstOrNull() ?: return
    val column = table.tableInfo.columns.firstOrNull() ?: return
    val ops = opsForColumn(column.dataType)
    val op = ops.first()
    val kind = literalKindForColumn(column.dataType)
    val value = when (op.valueKind()) {
        ValueKind.None -> null
        ValueKind.Single -> FilterValue.Single(FilterLiteral(kind, ""))
        ValueKind.List -> FilterValue.ListValue(listOf(FilterLiteral(kind, "")))
        ValueKind.Pair -> FilterValue.Pair(FilterLiteral(kind, ""), FilterLiteral(kind, ""))
    }
    val spec = FilterSpec(
        tableAlias = table.alias,
        column = column.name,
        op = op,
        value = value,
    )
    queryViewModel.addFilterToGroup(path, spec)
}
