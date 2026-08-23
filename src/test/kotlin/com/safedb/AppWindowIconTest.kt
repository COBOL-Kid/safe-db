package com.safedb

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppWindowIconTest {
    @Test
    fun windowIconResourceIsOnTheClasspath() {
        val image = loadAppWindowIconBytes().inputStream().use(ImageIO::read)
        assertNotNull(image)
        assertEquals(32, image.width)
        assertEquals(32, image.height)
    }
}
