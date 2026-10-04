package com.monster0506.divvy.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FitWithinTest {
    @Test
    fun small_image_is_unchanged() {
        assertEquals(800 to 600, fitWithin(800, 600, 2000))
    }

    @Test
    fun landscape_is_scaled_by_width() {
        assertEquals(2000 to 1500, fitWithin(4000, 3000, 2000))
    }

    @Test
    fun portrait_is_scaled_by_height() {
        assertEquals(1500 to 2000, fitWithin(3000, 4000, 2000))
    }

    @Test
    fun extreme_aspect_ratio_keeps_at_least_one_pixel() {
        assertEquals(2000 to 1, fitWithin(40000, 10, 2000))
    }

    @Test
    fun invalid_dimensions_are_rejected() {
        assertFailsWith<IllegalArgumentException> { fitWithin(0, 10, 2000) }
    }
}
