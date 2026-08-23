package com.safedb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppWindowIconTest {
    @Test
    fun windowIconResourceIsOnTheClasspath() {
        val image = loadAppWindowIconImage()
        assertNotNull(image)
        assertEquals(32, image.width)
        assertEquals(32, image.height)
    }
}
