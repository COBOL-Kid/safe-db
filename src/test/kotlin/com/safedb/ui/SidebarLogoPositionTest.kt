package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.AppRoute
import com.safedb.ui.theme.SafeDbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

@OptIn(ExperimentalComposeUiApi::class)
class SidebarLogoPositionTest {
    @Test
    fun `logo stays on the icon axis while the sidebar opens and closes`() {
        var collapsed by mutableStateOf(true)
        ImageComposeScene(width = 320, height = 420, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    Sidebar(
                        route = AppRoute.Home,
                        isDark = false,
                        collapsed = collapsed,
                        onCollapsedChange = { collapsed = it },
                        onNavigate = {},
                        onOpenSettings = {},
                        onOpenPalette = {},
                        onToggleTheme = {},
                    )
                }
            }
            .use { scene ->
                var frameTime = 0L

                fun sample(): SidebarFrame {
                    frameTime += 16_000_000L
                    return scene.render(frameTime).use { image -> measureSidebar(image) }
                }

                val samples = mutableListOf<SidebarFrame>()
                repeat(3) {
                    samples += sample()
                    Thread.sleep(30)
                }
                val collapsedWidth = samples.last().railWidth
                assertTrue(
                    collapsedWidth in 72..74,
                    "collapsed rail width was $collapsedWidth",
                )

                collapsed = false
                pumpUntil(samples, ::sample) { it.railWidth >= 230 }
                repeat(8) {
                    samples += sample()
                    Thread.sleep(40)
                }
                assertTrue(
                    samples.last().railWidth >= 230,
                    "sidebar did not finish opening; last rail width ${samples.last().railWidth}",
                )

                collapsed = true
                pumpUntil(samples, ::sample) { it.railWidth <= collapsedWidth }
                repeat(10) {
                    samples += sample()
                    Thread.sleep(40)
                }

                assertEquals(
                    collapsedWidth,
                    samples.last().railWidth,
                    "sidebar did not finish closing",
                )
                val logoPositions = samples.map { it.logoLeft to it.logoTop }.distinct()
                assertEquals(
                    listOf(16 to 22),
                    logoPositions,
                    "logo moved across ${samples.size} frames",
                )
            }
    }

    private fun pumpUntil(
        samples: MutableList<SidebarFrame>,
        sample: () -> SidebarFrame,
        timeoutMs: Long = 3_000,
        done: (SidebarFrame) -> Boolean,
    ): SidebarFrame {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var latest = samples.last()
        while (System.nanoTime() < deadline) {
            Thread.sleep(25)
            latest = sample()
            samples += latest
            if (done(latest)) return latest
        }
        return latest
    }
}

private data class SidebarFrame(val logoLeft: Int, val logoTop: Int, val railWidth: Int)

private fun measureSidebar(image: Image): SidebarFrame {
    val pixels = image.peekPixels() ?: error("rendered sidebar frame has no readable pixels")
    val width = pixels.info.width
    var logoTop = Int.MAX_VALUE
    var logoBottom = -1
    for (y in 0 until HeaderHeightPx) {
        for (x in 0 until width) {
            if (!pixels.getColor(x, y).isLogoBlue()) continue
            if (y < logoTop) logoTop = y
            if (y > logoBottom) logoBottom = y
        }
    }
    check(logoBottom >= logoTop) { headerColorSample(pixels) }
    val midY = (logoTop + logoBottom) / 2
    var midLeft = Int.MAX_VALUE
    for (x in 0 until width) {
        if (pixels.getColor(x, midY).isLogoBlue()) {
            midLeft = x
            break
        }
    }
    check(midLeft != Int.MAX_VALUE) { headerColorSample(pixels) }
    var railRight = -1
    for (x in 0 until width) {
        if (pixels.getColor(x, 4).alpha() > 0.5f) railRight = x
    }
    return SidebarFrame(logoLeft = midLeft, logoTop = logoTop, railWidth = railRight + 1)
}

private fun headerColorSample(pixels: org.jetbrains.skia.Pixmap): String = buildString {
    append("logo pixels not found; header samples:")
    for (y in 0 until HeaderHeightPx step 8) {
        for (x in 0 until 80 step 4) {
            append(" ($x,$y)=")
            append(pixels.getColor(x, y).toUInt().toString(16))
        }
    }
}

private fun Int.isLogoBlue(): Boolean {
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    return kotlin.math.abs(red - 0x0B) <= 12 &&
        kotlin.math.abs(green - 0x5E) <= 12 &&
        kotlin.math.abs(blue - 0xD7) <= 12 &&
        alpha() > 0.9f
}

private fun Int.alpha(): Float = ((this ushr 24) and 0xFF) / 255f

private const val HeaderHeightPx = 78
