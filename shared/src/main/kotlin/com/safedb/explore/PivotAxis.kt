package com.safedb.explore

import com.safedb.model.ResultCell
import java.util.BitSet

internal data class PivotRecord(
    val index: Int,
    val row: List<ResultCell>,
    val rowBuckets: List<ExploreBucket>,
    val columnBuckets: List<ExploreBucket>,
)

internal class AxisNode(
    val bucket: ExploreBucket?,
    val depth: Int,
    val pathKey: String,
    val parent: AxisNode?,
    val rows: BitSet = BitSet(),
) {
    val children = linkedMapOf<String, AxisNode>()

    fun labels(): List<String> =
        generateSequence(this) { it.parent }.mapNotNull { it.bucket?.label }.toList().asReversed()

    fun ancestorAtDepth(targetDepth: Int): AxisNode? {
        var node: AxisNode? = this
        while (node != null && node.depth > targetDepth) node = node.parent
        return node?.takeIf { it.depth == targetDepth }
    }
}

internal data class RowSlice(
    val node: AxisNode,
    val label: String,
    val kind: PivotRowKind,
    val hasChildren: Boolean,
    val expanded: Boolean,
) {
    val pathKey: String
        get() = if (kind == PivotRowKind.GrandTotal) "<grand>" else node.pathKey

    fun toEntry() =
        PivotRowEntry(
            pathKey = pathKey,
            label = label,
            depth = (node.depth - 1).coerceAtLeast(0),
            kind = kind,
            hasChildren = hasChildren,
            expanded = expanded,
        )
}

internal data class ColumnSlice(
    val node: AxisNode,
    val labels: List<String>,
    val isSubtotal: Boolean,
    val isGrandTotal: Boolean,
) {
    val pathKey: String
        get() = if (isGrandTotal) "<grand>" else node.pathKey

    fun toLeaf() = PivotColumnLeaf(pathKey, labels, isSubtotal, isGrandTotal)
}
