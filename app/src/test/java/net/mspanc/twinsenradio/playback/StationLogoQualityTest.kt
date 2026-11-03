package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class StationLogoQualityTest {
    @Test fun acceptsTheExactMinimumAndLargerImages() {
        assertTrue(StationLogoQuality.accepts(512, 512))
        assertTrue(StationLogoQuality.accepts(512, 1024))
        assertTrue(StationLogoQuality.accepts(1024, 512))
    }

    @Test fun rejectsEitherUndersizedAxisRatherThanUsingAreaOrLongestEdge() {
        assertFalse(StationLogoQuality.accepts(511, 512))
        assertFalse(StationLogoQuality.accepts(512, 511))
        assertFalse(StationLogoQuality.accepts(128, 1024))
        assertFalse(StationLogoQuality.accepts(1024, 128))
    }

    @Test fun rejectsDecodeFailureAndInvalidDimensions() {
        assertFalse(StationLogoQuality.accepts(-1, -1))
        assertFalse(StationLogoQuality.accepts(0, 512))
        assertFalse(StationLogoQuality.accepts(512, 0))
    }
}
