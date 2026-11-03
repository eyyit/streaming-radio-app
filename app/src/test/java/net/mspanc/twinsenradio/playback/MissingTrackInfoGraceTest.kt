package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingTrackInfoGraceTest {
    private val grace = MissingTrackInfoGrace()

    private fun state(at: Long, playing: Boolean = true, title: Boolean = false,
                      artist: Boolean = false, station: String = "a") =
        grace.update(station, playing, title, artist, at)

    @Test fun defaultWaitsForThirtySecondsOfActualPlayback() {
        state(0)
        assertNull(grace.text(29_999))
        assertEquals("No track info received", grace.text(30_000))
    }

    @Test fun pausedTimeDoesNotCountAndNoticeHidesWhilePaused() {
        state(0)
        state(10_000, playing = false)
        assertNull(grace.text(100_000))
        state(100_000)
        assertNull(grace.text(119_999))
        assertEquals("No track info received", grace.text(120_000))
        state(120_000, playing = false)
        assertNull(grace.text(120_001))
    }

    @Test fun bufferingBeforePlaybackAndDuringPlaybackDoesNotCount() {
        state(0, playing = false)
        state(100_000)
        state(110_000, playing = false)
        state(200_000)
        assertNull(grace.text(219_999))
        assertEquals("No track info received", grace.text(220_000))
    }

    @Test fun eitherMetadataFieldClearsAndResetsMissingTime() {
        state(0)
        state(30_000, title = true)
        assertNull(grace.text(40_000))
        state(40_000)
        assertNull(grace.text(69_999))
        state(70_000, artist = true)
        assertNull(grace.text(80_000))
        state(80_000)
        assertNull(grace.text(109_999))
        assertEquals("No track info received", grace.text(110_000))
    }

    @Test fun stationChangeAndExplicitSessionResetStartFresh() {
        state(0)
        state(30_000, station = "b")
        assertNull(grace.text(59_999))
        assertEquals("No track info received", grace.text(60_000))
        grace.reset()
        state(60_000, station = "b")
        assertNull(grace.text(89_999))
    }

    @Test fun twoIcyIntervalsUseBytesAndBitsPerSecondWithThirtySecondFloor() {
        assertEquals(30_000L, MissingTrackInfoGrace.graceMs(16_000, 128_000))
        assertEquals(60_000L, MissingTrackInfoGrace.graceMs(480_000, 128_000))
        assertEquals(30_001L, MissingTrackInfoGrace.graceMs(240_001, 128_000))
        state(0)
        grace.setIcyHeaders(480_000, 128_000)
        assertNull(grace.text(59_999))
        assertEquals("No track info received", grace.text(60_000))
    }

    @Test fun invalidOrOverflowSizedHeadersFallBackAndLongIntervalsAreBounded() {
        for ((interval, bitrate) in listOf(-1L to 128_000L, 1L to -1L,
            0L to 128_000L, 1L to 0L, Long.MAX_VALUE to 1L,
            1L to Long.MAX_VALUE)) {
            assertEquals(30_000L, MissingTrackInfoGrace.graceMs(interval, bitrate))
        }
        assertEquals(300_000L, MissingTrackInfoGrace.graceMs(Int.MAX_VALUE.toLong(), 1L))
    }

    @Test fun newConnectionWithoutHeadersDiscardsPreviousAdvertisedTiming() {
        state(0)
        grace.setIcyHeaders(480_000, 128_000)
        assertNull(grace.text(30_000))
        grace.setIcyHeaders(-1, -1)
        assertEquals("No track info received", grace.text(30_000))
    }
}
