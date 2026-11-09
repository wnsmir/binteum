package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class AmbientSkyTest {
    @Test fun `flat palettes are neutral solid colors`() {
        listOf(BackgroundMode.BLACK to 0xFF000000.toInt(), BackgroundMode.WHITE to 0xFFFFFFFF.toInt()).forEach { (mode, color) ->
            assertTrue(mode.isFlat)
            assertEquals(SkyColors(color, color, color), AmbientSky.colors(mode, 720))
        }
        assertFalse(BackgroundMode.AUTO.isFlat)
    }

    @Test fun `auto follows the local day and returns to night across midnight`() {
        assertEquals(AmbientSky.colors(BackgroundMode.DAWN, 0), AmbientSky.colors(BackgroundMode.AUTO, 420))
        assertEquals(AmbientSky.colors(BackgroundMode.DAY, 0), AmbientSky.colors(BackgroundMode.AUTO, 720))
        assertEquals(AmbientSky.colors(BackgroundMode.SUNSET, 0), AmbientSky.colors(BackgroundMode.AUTO, 1080))
        assertEquals(AmbientSky.colors(BackgroundMode.AUTO, 0), AmbientSky.colors(BackgroundMode.AUTO, 1439))
    }

    @Test fun `fixed backgrounds ignore time of day`() {
        BackgroundMode.values().filter { it != BackgroundMode.AUTO }.forEach { mode ->
            val expected = AmbientSky.colors(mode, 0)
            (0..1439).forEach { assertEquals(expected, AmbientSky.colors(mode, it)) }
        }
    }

    @Test fun `automatic colors stay continuous through every minute and schedule boundary`() {
        (0..1439).forEach { minute ->
            val a = AmbientSky.colors(BackgroundMode.AUTO, minute)
            val b = AmbientSky.colors(BackgroundMode.AUTO, (minute + 1) % 1440)
            listOf(a.top to b.top, a.middle to b.middle, a.bottom to b.bottom).forEach { (x, y) ->
                listOf(0, 8, 16).forEach { shift ->
                    assertTrue(kotlin.math.abs(((x ushr shift) and 255) - ((y ushr shift) and 255)) <= 3)
                }
            }
        }
    }
}
