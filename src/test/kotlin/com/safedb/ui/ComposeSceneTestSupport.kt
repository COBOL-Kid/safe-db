package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs

private const val SETTLE_FRAME_NANOS = 50_000_000L

internal fun ImageComposeScene.allSemanticsNodes(): List<SemanticsNode> {
    val nodes = mutableListOf<SemanticsNode>()
    for (owner in semanticsOwners) {
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk(owner.rootSemanticsNode)
    }
    return nodes
}

internal fun SemanticsNode.label(): String {
    val texts =
        if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString { it.text }
        } else {
            ""
        }
    val editable =
        if (config.contains(SemanticsProperties.EditableText)) {
            config[SemanticsProperties.EditableText].text
        } else {
            ""
        }
    val descriptions =
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].joinToString()
        } else {
            ""
        }
    return listOf(texts, editable, descriptions).filter { it.isNotEmpty() }.joinToString(" | ")
}

internal fun ImageComposeScene.findContaining(text: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label().contains(text) }

internal fun ImageComposeScene.requireContaining(text: String): SemanticsNode =
    findContaining(text)
        ?: error(
            "No node containing '$text' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

internal fun ImageComposeScene.findExact(label: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { it.label() == label }

internal fun ImageComposeScene.requireExact(label: String): SemanticsNode =
    findExact(label)
        ?: error(
            "No node labeled '$label' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

internal fun ImageComposeScene.nodesLabeled(label: String): List<SemanticsNode> =
    allSemanticsNodes().filter { it.label() == label }

internal fun ImageComposeScene.nodesContaining(text: String): List<SemanticsNode> =
    allSemanticsNodes().filter { it.label().contains(text) }

internal fun ImageComposeScene.findByTag(tag: String): SemanticsNode? =
    allSemanticsNodes().firstOrNull { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag] == tag
    }

internal fun ImageComposeScene.requireByTag(tag: String): SemanticsNode =
    findByTag(tag)
        ?: error(
            "No node tagged '$tag' in ${
                allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
            }"
        )

@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.click(position: Offset) {
    sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
    sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
}

internal fun ImageComposeScene.settle() {
    render(SETTLE_FRAME_NANOS)
}

internal fun ImageComposeScene.clickActionNear(anchorText: String, actionLabel: String) {
    val anchor = requireContaining(anchorText)
    val action =
        nodesLabeled(actionLabel).minBy {
            abs(it.boundsInRoot.center.y - anchor.boundsInRoot.center.y)
        }
    click(action.boundsInRoot.center)
}

// Scope the lookup to one card so a layout change cannot silently click a neighbouring row.
internal fun ImageComposeScene.clickActionInTag(tag: String, actionLabel: String) {
    val card = requireByTag(tag)
    val action =
        card.descendants().firstOrNull { it.label() == actionLabel }
            ?: error("No node labeled '$actionLabel' inside '$tag'")
    click(action.boundsInRoot.center)
}

private fun SemanticsNode.descendants(): List<SemanticsNode> = buildList {
    add(this@descendants)
    for (child in children) addAll(child.descendants())
}
