package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.FilterOp
import com.safedb.query.CANVAS_HEADER_HEIGHT
import com.safedb.query.CANVAS_ROW_HEIGHT
import com.safedb.query.CANVAS_RESIZE_FOOTER_HEIGHT
import com.safedb.query.opLabel
import com.safedb.query.opsForColumn
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.MenuSectionLabel
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel

@Composable
fun TableCard(
    canvasTable: CanvasTable,
    queryViewModel: QueryViewModel,
    onStartDrag: () -> Unit,
    onDragTable: (Offset) -> Unit,
    onStartJoin: (String) -> Unit,
    onDragJoin: (Offset) -> Unit,
    onJoinClick: (String) -> Unit,
    onJoinTargetClick: (String, String) -> Unit,
    onStartResize: () -> Unit,
    onResizeTable: (Offset) -> Unit,
    onEndGesture: () -> Unit,
    fieldScrollState: ScrollState = rememberScrollState(),
    joinDragActive: Boolean = false,
    highlightJoinTargets: Pair<String, String>? = null,
    modifier: Modifier = Modifier,
) {
    val table = canvasTable.tableInfo
    val alias = canvasTable.alias
    var menuColumn by remember { mutableStateOf<String?>(null) }
    val displayHeight = canvasTable.height
    val bodyHeight = (displayHeight - CANVAS_HEADER_HEIGHT - CANVAS_RESIZE_FOOTER_HEIGHT).coerceAtLeast(64f)
    val resizeHandleColor = SafeDbTheme.colors.actionPrimary.copy(alpha = 0.84f)
    val joinColor = SafeDbTheme.colors.actionPrimary
    val joinTargetColor = SafeDbTheme.colors.accentContainer

    Surface(
        modifier = modifier
            .width(canvasTable.width.dp)
            .height(displayHeight.dp)
            .pointerInput(alias) {
                detectDragGestures(
                    onDragStart = { onStartDrag() },
                    onDragEnd = { onEndGesture() },
                    onDragCancel = { onEndGesture() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragTable(dragAmount)
                    },
                )
            },
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CANVAS_HEADER_HEIGHT.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        table.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { queryViewModel.removeTable(alias) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove table",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bodyHeight.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                        .verticalScroll(fieldScrollState),
                ) {
                    for (column in table.columns) {
                        val selected = queryViewModel.isColumnSelected(alias, column.name)
                        val joinTarget = highlightJoinTargets != null &&
                            column.isIndexed &&
                            !(highlightJoinTargets.first == alias && highlightJoinTargets.second == column.name)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CANVAS_ROW_HEIGHT.dp)
                                .padding(vertical = 1.dp)
                                .then(
                                    if (joinTarget && joinDragActive) {
                                        Modifier.clickable { onJoinTargetClick(alias, column.name) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .background(
                                    when {
                                        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        joinTarget -> joinTargetColor.copy(alpha = 0.72f)
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (joinTarget && joinDragActive) {
                                            onJoinTargetClick(alias, column.name)
                                        } else {
                                            queryViewModel.toggleColumn(alias, column.name)
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .border(
                                            1.dp,
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                            RoundedCornerShape(3.dp),
                                        )
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(3.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(9.dp),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        column.name,
                                        style = DataMono.copy(fontWeight = FontWeight.Medium),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        column.dataType,
                                        style = DataMono.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .weight(0.45f, fill = false),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Box {
                                IconButton(
                                    onClick = { menuColumn = if (menuColumn == column.name) null else column.name },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Filter options",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                SafeDropdownMenu(
                                    expanded = menuColumn == column.name,
                                    onDismissRequest = { menuColumn = null },
                                ) {
                                    MenuSectionLabel("Filter where")
                                    for (op in opsForColumn(column.dataType)) {
                                        MenuActionRow(
                                            text = "${column.name} ${opLabel(op)}",
                                            onClick = {
                                                queryViewModel.addFilter(
                                                    QueryViewModel.defaultFilterForColumn(alias, column.name, column.dataType)
                                                        .copy(op = op),
                                                )
                                                menuColumn = null
                                            },
                                        )
                                    }
                                }
                            }

                            if (column.isIndexed) {
                                IconButton(
                                    onClick = {
                                        if (joinDragActive) {
                                            onJoinTargetClick(alias, column.name)
                                        } else {
                                            onJoinClick(column.name)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(18.dp)
                                        .pointerInput(column.name) {
                                            detectDragGestures(
                                                onDragStart = { onStartJoin(column.name) },
                                                onDragEnd = { onEndGesture() },
                                                onDragCancel = { onEndGesture() },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    onDragJoin(dragAmount)
                                                },
                                            )
                                        },
                                ) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Drag to join",
                                        modifier = Modifier.size(13.dp),
                                        tint = joinColor,
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (fieldScrollState.value < fieldScrollState.maxValue) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                    ),
                                ),
                            ),
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "More fields below",
                        tint = SafeDbTheme.colors.actionPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .size(18.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CANVAS_RESIZE_FOOTER_HEIGHT.dp)
                    .padding(3.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { contentDescription = "Resize table" }
                        .pointerInput(alias) {
                            detectDragGestures(
                                onDragStart = { onStartResize() },
                                onDragEnd = { onEndGesture() },
                                onDragCancel = { onEndGesture() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onResizeTable(dragAmount)
                                },
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val stroke = 1.8.dp.toPx()
                        drawLine(
                            color = resizeHandleColor,
                            start = Offset(size.width * 0.48f, size.height * 0.82f),
                            end = Offset(size.width * 0.82f, size.height * 0.48f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = resizeHandleColor,
                            start = Offset(size.width * 0.68f, size.height * 0.86f),
                            end = Offset(size.width * 0.86f, size.height * 0.68f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
