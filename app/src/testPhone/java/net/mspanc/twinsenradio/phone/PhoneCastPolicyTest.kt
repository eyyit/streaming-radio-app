package net.mspanc.twinsenradio.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCastPolicyTest {
    @Test
    fun mimeTypeUsesStreamPathAndVariantLabel() {
        assertEquals("audio/mpeg", PhoneCastPolicy.contentType("https://radio.test/live.mp3?x=1"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live", "AAC 64 kbps"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live", measuredCodec = "AAC+"))
        assertEquals("audio/mpeg", PhoneCastPolicy.contentType("https://radio.test/live", measuredCodec = "MP3"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live.aacp"))
        assertEquals(
            "application/vnd.apple.mpegurl",
            PhoneCastPolicy.contentType("https://radio.test/live.m3u8")
        )
        assertEquals(null, PhoneCastPolicy.contentType("https://radio.test/live"))
        assertEquals(null, PhoneCastPolicy.contentType("https://radio.test/live?format=mp3"))
    }

    @Test
    fun mimeTypeRecognizesTerminalCodecPathTokens() {
        assertEquals(
            "audio/mpeg",
            PhoneCastPolicy.contentType("https://ice6.somafm.com/groovesalad-256-mp3")
        )
        assertEquals("audio/mpeg", PhoneCastPolicy.contentType("https://radio.test/_mp3"))
        assertEquals("audio/mpeg", PhoneCastPolicy.contentType("https://radio.test/mp3"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live-aac"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/_aac"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live-aacp"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/aacp"))
        assertEquals(null, PhoneCastPolicy.contentType("https://radio.test/live-mpeg"))
    }

    @Test
    fun phonePausesOnlyAfterSuccessfulLoadOfSameCurrentUri() {
        assertTrue(PhoneCastPolicy.shouldPauseLocalAfterLoad(true, "https://radio.test/a.mp3", "https://radio.test/a.mp3"))
        assertFalse(PhoneCastPolicy.shouldPauseLocalAfterLoad(false, "https://radio.test/a.mp3", "https://radio.test/a.mp3"))
        assertFalse(PhoneCastPolicy.shouldPauseLocalAfterLoad(true, "https://radio.test/b.mp3", "https://radio.test/a.mp3"))
    }

    @Test
    fun reconnectLoadsOnlyOwnedMediaOrExplicitReceiverSelection() {
        assertFalse(PhoneCastPolicy.shouldLoadCurrentStream(false, false, "https://other.test/x.mp3", "https://radio.test/a.mp3"))
        assertFalse(PhoneCastPolicy.shouldLoadCurrentStream(false, true, "https://radio.test/a.mp3", "https://radio.test/a.mp3", "station-a", "station-a"))
        assertTrue(PhoneCastPolicy.shouldLoadCurrentStream(false, true, "https://radio.test/a.mp3", "https://radio.test/a.mp3", "station-a", "station-b"))
        assertTrue(PhoneCastPolicy.shouldLoadCurrentStream(false, true, "https://radio.test/old.mp3", "https://radio.test/a.mp3"))
        assertTrue(PhoneCastPolicy.shouldLoadCurrentStream(true, false, null, "https://radio.test/a.mp3"))
        assertFalse(PhoneCastPolicy.ownsRemoteMedia("another-app"))
        assertTrue(PhoneCastPolicy.ownsRemoteMedia(PhoneCastPolicy.OWNER_MARKER))
    }
}
