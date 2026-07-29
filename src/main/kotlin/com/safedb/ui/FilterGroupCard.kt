package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
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
    val depthTint = if (depth > 0) {
        if (depth % 2 == 1) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        }
    } else {
        null
    }
    val backgroundModifier = if (depth > 0) {
        Modifier
            .background(depthTint!!, RoundedCornerShape(3.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
            .padding(6.dp)
    } else {
        Modifier
    }

    Column(
        modifier = modifier.then(backgroundModifier),
    ) {
        Column(
            modifier = Modifier.padding(start = if (depth > 0) 12.dp else 0.dp),
        ) {
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

        if (group.children.isEmpty() && depth > 0) {
            Text(
                "No conditions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(vertical = 8.dp),
            )
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

@Composable
private fun CompactIconAction(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
