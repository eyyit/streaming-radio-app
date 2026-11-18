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
        assertEquals("audio/mpeg", PhoneCastPolicy.contentType("https://sohoradiomusic.doughunt.co.uk:8010/320mp3"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live-aac"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/_aac"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/live-aacp"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("https://radio.test/aacp"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("http://stream.radioparadise.com/aac-320"))
        assertEquals("audio/aac", PhoneCastPolicy.contentType("http://live-radio01.mediahubaustralia.com/DJDW/aac/"))
        assertEquals(null, PhoneCastPolicy.contentType("https://radio.test/live-mpeg"))
    }

    @Test
    fun recognizesNativeAndOggFlacWithoutOverridingHls() {
        assertEquals("audio/flac", PhoneCastPolicy.contentType("https://radio.test/live.FLAC?key=1"))
        assertEquals("audio/flac", PhoneCastPolicy.contentType("https://radio.test/live", "FLAC lossless"))
        assertEquals("audio/flac", PhoneCastPolicy.contentType("https://radio.test/live", sampleMimeType = "audio/flac"))
        assertEquals("audio/ogg; codecs=flac", PhoneCastPolicy.contentType("http://stream.radioparadise.com/flacm"))
        assertEquals("audio/ogg; codecs=flac", PhoneCastPolicy.contentType("http://stream.radioparadise.com/mellow-flacm"))
        assertEquals("audio/ogg; codecs=flac", PhoneCastPolicy.contentType(
            "http://stream.radioparadise.com/flac", sampleMimeType = "audio/flac", containerMimeType = "audio/ogg"
        ))
        assertEquals("audio/ogg; codecs=flac", PhoneCastPolicy.contentType(
            "https://radio.test/live.ogg", sampleMimeType = "audio/flac", containerMimeType = "audio/ogg"
        ))
        assertEquals(null, PhoneCastPolicy.contentType(
            "https://radio.test/live", sampleMimeType = "audio/flac", containerMimeType = "video/mp4"
        ))
        assertEquals("application/vnd.apple.mpegurl", PhoneCastPolicy.contentType(
            "https://radio.test/live.m3u8", sampleMimeType = "audio/flac"
        ))
    }

    @Test
    fun packagedAudioUsesItsContainerAndRejectsMismatchedCodecs() {
        val url = "https://radio.test/live"
        assertEquals("audio/mp4", PhoneCastPolicy.contentType("$url.m4a", sampleMimeType = "audio/mp4a-latm"))
        assertEquals("audio/mp4", PhoneCastPolicy.contentType(url,
            sampleMimeType = "audio/mp4a-latm", containerMimeType = "video/mp4"))
        assertEquals("audio/ogg", PhoneCastPolicy.contentType("$url.opus", sampleMimeType = "audio/opus"))
        assertEquals("audio/ogg", PhoneCastPolicy.contentType(url, sampleMimeType = "audio/opus"))
        assertEquals("audio/ogg; codecs=vorbis", PhoneCastPolicy.contentType(url,
            sampleMimeType = "audio/vorbis", containerMimeType = "audio/ogg"))
        assertEquals("audio/ogg; codecs=vorbis", PhoneCastPolicy.contentType(url, sampleMimeType = "audio/vorbis"))
        assertEquals("audio/webm", PhoneCastPolicy.contentType("$url.webm", sampleMimeType = "audio/opus"))
        assertEquals("audio/wav", PhoneCastPolicy.contentType("$url.wav", sampleMimeType = "audio/raw"))
        assertEquals(null, PhoneCastPolicy.contentType("$url.wav", sampleMimeType = "audio/aac"))
        assertEquals(null, PhoneCastPolicy.contentType("$url?format=opus"))
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
