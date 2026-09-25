/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotScaleMessageTest {
    @Test
    fun `a HiDPI image reports the factor to divide by`() {
        assertEquals(
            "Image scale: 1.5. The image is 1800x1200 pixels for a 1200x800 window. " +
                "Divide image coordinates by 1.5 before passing them to steroid_input.",
            screenshotScaleMessage(componentSize = Size(1200, 800), imageSize = Size(1800, 1200)),
        )
    }

    @Test
    fun `a whole-number scale prints without a fraction`() {
        assertEquals(
            "Image scale: 2. The image is 2400x1600 pixels for a 1200x800 window. " +
                "Divide image coordinates by 2 before passing them to steroid_input.",
            screenshotScaleMessage(componentSize = Size(1200, 800), imageSize = Size(2400, 1600)),
        )
    }

    @Test
    fun `an unscaled image needs no message`() {
        assertNull(screenshotScaleMessage(componentSize = Size(1200, 800), imageSize = Size(1200, 800)))
    }

    @Test
    fun `an empty component needs no message`() {
        assertNull(screenshotScaleMessage(componentSize = Size(0, 0), imageSize = Size(1024, 768)))
    }
}
