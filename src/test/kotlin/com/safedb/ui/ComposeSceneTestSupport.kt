package com.safedb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.model.FilterGroup
import com.safedb.model.QuerySpec
import com.safedb.model.TableRef
import kotlin.math.abs
import kotlin.test.assertTrue

private const val SETTLE_FRAME_NANOS = 50_000_000L

@OptIn(ExperimentalComposeUiApi::class)
internal fun withComposeScene(
    width: Int,
    height: Int,
    content: @Composable () -> Unit,
    body: (ImageComposeScene) -> Unit,
) {
    ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        .use { scene ->
            scene.render(0L)
            body(scene)
        }
}

internal fun sampleQuerySpec() =
    QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )

internal fun ImageComposeScene.allSemanticsNodes(): List<SemanticsNode> = semanticsOwners.flatMap {
    it.rootSemanticsNode.descendants()
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

internal fun ImageComposeScene.assertNoHorizontalOverflow(sceneWidth: Int) {
    val overflows =
        allSemanticsNodes().filter {
            it.boundsInRoot.left < -0.5f || it.boundsInRoot.right > sceneWidth + 0.5f
        }
    assertTrue(
        overflows.isEmpty(),
        "nodes overflowed ${sceneWidth}px: ${overflows.map { "${it.label()} ${it.boundsInRoot}" }}",
    )
}

internal const val TIMESTAMP_FIELD_MIN_WIDTH_PX = 168f

// "BETWEEN" at 12sp needs about 52px; a 100dp Op dropdown left only ~40px and showed "BETW...".
internal const val BETWEEN_OP_LABEL_MIN_WIDTH_PX = 52f

internal fun rectsOverlap(a: Rect, b: Rect): Boolean =
    a.left < b.right - 0.5f &&
        a.right > b.left + 0.5f &&
        a.top < b.bottom - 0.5f &&
        a.bottom > b.top + 0.5f

internal fun SemanticsNode.isInsideWindow(width: Int, height: Int, minSize: Float = 8f): Boolean {
    val bounds = boundsInRoot
    return bounds.width >= minSize &&
        bounds.height >= minSize &&
        bounds.left >= -0.5f &&
        bounds.top >= -0.5f &&
        bounds.right <= width + 0.5f &&
        bounds.bottom <= height + 0.5f
}

internal fun SemanticsNode.visibleBoundsIn(clip: SemanticsNode): Rect =
    boundsInRoot.intersect(clip.boundsInRoot)

internal fun SemanticsNode.isVisibleInside(clip: SemanticsNode, minSize: Float = 8f): Boolean {
    val visible = visibleBoundsIn(clip)
    return visible.width >= minSize && visible.height >= minSize
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.scrollTagged(tag: String, deltaY: Float, times: Int = 8) {
    val node = requireByTag(tag)
    repeat(times) {
        sendPointerEvent(
            PointerEventType.Scroll,
            node.boundsInRoot.center,
            scrollDelta = Offset(0f, deltaY),
        )
        render(100_000_000L)
    }
}

internal fun assertBetweenRelationship(
    from: SemanticsNode,
    and: SemanticsNode,
    to: SemanticsNode,
    detail: String = "",
) {
    val sameRow =
        abs(from.boundsInRoot.center.y - to.boundsInRoot.center.y) <= 1f &&
            abs(and.boundsInRoot.center.y - from.boundsInRoot.center.y) <= 1f &&
            from.boundsInRoot.right <= and.boundsInRoot.left + 0.5f &&
            and.boundsInRoot.right <= to.boundsInRoot.left + 0.5f
    val stacked =
        from.boundsInRoot.bottom <= to.boundsInRoot.top + 1f &&
            and.boundsInRoot.center.y >= from.boundsInRoot.top - 0.5f &&
            and.boundsInRoot.center.y <= to.boundsInRoot.bottom + 0.5f
    val suffix = if (detail.isEmpty()) "" else " $detail"
    assertTrue(
        sameRow || stacked,
        "and lost the from/to relationship$suffix: from=${from.boundsInRoot} " +
            "and=${and.boundsInRoot} to=${to.boundsInRoot}",
    )
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
