package com.safedb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

internal const val APP_WINDOW_ICON_RESOURCE = "icons/icon-32.png"

private object AppWindowIconLoader

internal fun loadAppWindowIconBytes(): ByteArray {
    val stream =
        AppWindowIconLoader::class.java.getResourceAsStream("/$APP_WINDOW_ICON_RESOURCE")
            ?: error("missing /$APP_WINDOW_ICON_RESOURCE")
    return stream.use { it.readBytes() }
}

@Composable
internal fun rememberAppWindowIcon(): Painter = remember {
    val image =
        requireNotNull(loadAppWindowIconBytes().inputStream().use(ImageIO::read)) {
            "could not decode /$APP_WINDOW_ICON_RESOURCE"
        }
    BitmapPainter(image.toComposeImageBitmap())
}
