package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceThemeTest {
    @Test
    fun presetsFollowRoygbivOrderAndRequestedColors() {
        assertEquals(
            listOf("red", "orange", "amber", "green", "blue", "indigo", "violet"),
            AppearanceTheme.presets.map { it.id }
        )
        assertEquals(
            listOf(
                "Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet"
            ),
            AppearanceTheme.presets.map { it.name }
        )
        assertEquals(
            listOf(
                0xffff5b64.toInt(),
                0xffff963f.toInt(),
                0xfff4d64f.toInt(),
                0xff43c98b.toInt(),
                0xff159fe0.toInt(),
                0xff8c91ff.toInt(),
                0xffc17cff.toInt()
            ),
            AppearanceTheme.presets.map { it.accent }
        )
        assertTrue(AppearanceTheme.presets.all { it.onAccent == 0xff07151d.toInt() })
    }

    @Test
    fun blueIsDefaultAndExistingPresetIdsRemainAvailable() {
        assertEquals("blue", AppearanceTheme.DEFAULT_ID)
        assertTrue(
            AppearanceTheme.presets.map { it.id }
                .containsAll(listOf("red", "blue", "green", "amber", "violet"))
        )
        assertEquals("Yellow", AppearanceTheme.presets.first { it.id == "amber" }.name)
    }
}
