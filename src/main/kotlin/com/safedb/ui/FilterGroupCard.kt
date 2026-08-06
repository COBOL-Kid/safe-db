package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.GroupConnector
import com.safedb.query.MAX_FILTER_DEPTH
import com.safedb.ui.components.AndOrConnector
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
    val singleLeafPath = if (group.children.singleOrNull() is FilterNode.Leaf) path + 0 else null
    val depthTint =
        if (depth > 0) {
            if (depth % 2 == 1) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        } else {
            null
        }
    val backgroundModifier =
        if (depth > 0) {
            Modifier.background(depthTint!!, RoundedCornerShape(3.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                .padding(6.dp)
        } else {
            Modifier
        }

    Column(modifier = modifier.then(backgroundModifier)) {
        Column(modifier = Modifier.padding(start = if (depth > 0) 12.dp else 0.dp)) {
            group.children.forEachIndexed { index, child ->
                val childPath = path + index
                if (index > 0) {
                    val connector = queryViewModel.getConnectorForChild(childPath)
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        AndOrConnector(
                            connector = connector,
                            onClick = { queryViewModel.toggleChildConnector(childPath) },
                        )
                    }
                }
                when (child) {
                    is FilterNode.Leaf ->
                        FilterRow(
                            queryViewModel = queryViewModel,
                            filter = child.spec,
                            path = childPath,
                            showRemoveAction = singleLeafPath == null,
                        )
                    is FilterNode.Group ->
                        FilterGroupCard(
                            queryViewModel = queryViewModel,
                            group = child.group,
                            path = childPath,
                            depth = depth + 1,
                        )
                }
            }
        }

        if (group.children.isEmpty() && depth > 0) {
            Text(
                "No conditions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier.padding(start = if (depth > 0) 12.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactFilterBuilderAction(onClick = { addFilter(queryViewModel, path) }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                Text("Filter", style = MaterialTheme.typography.labelSmall)
            }
            CompactFilterBuilderAction(
                onClick = { queryViewModel.addGroupToGroup(path, GroupConnector.And) },
                enabled = !atMaxDepth,
            ) {
                Icon(
                    Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
                Text("Group", style = MaterialTheme.typography.labelSmall)
            }
            if (singleLeafPath != null) {
                CompactFilterBuilderAction(
                    onClick = { queryViewModel.removeFilterNode(singleLeafPath) }
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Text("Remove", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (depth > 0) {
                Box(
                    modifier = Modifier.padding(start = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactIconAction(onClick = { queryViewModel.removeFilterNode(path) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove group",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactFilterBuilderAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(2.dp),
        color = Color.Transparent,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun CompactIconAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(28.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun addFilter(queryViewModel: QueryViewModel, path: List<Int>) {
    val table = queryViewModel.canvasTables.firstOrNull() ?: return
    val column = table.tableInfo.columns.firstOrNull() ?: return
    queryViewModel.addFilterToGroup(
        path,
        QueryViewModel.defaultFilterForColumn(table.alias, column.name, column.dataType),
    )
}
