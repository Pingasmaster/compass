package com.compass.app.ui.compass

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeBearingDegreesTest {

    @Test
    fun `keeps values already in range`() {
        assertEquals(0f, normalizeBearingDegrees(0f), 0.001f)
        assertEquals(90f, normalizeBearingDegrees(90f), 0.001f)
        assertEquals(359f, normalizeBearingDegrees(359f), 0.001f)
    }

    @Test
    fun `wraps negative and oversized angles`() {
        assertEquals(350f, normalizeBearingDegrees(-10f), 0.001f)
        assertEquals(10f, normalizeBearingDegrees(370f), 0.001f)
        assertEquals(0f, normalizeBearingDegrees(720f), 0.001f)
    }
}
