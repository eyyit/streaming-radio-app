package net.mspanc.twinsenradio.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {

    @Test
    fun `the chip says codec, bitrate and channels`() {
        assertEquals("MP3 · 128 kbit · stereo", StreamQuality("MP3", 128, 44_100, 2).chipLabel())
    }

    /** RNS's catalogue says what was chosen; that beats what the decoder guesses. */
    @Test
    fun `a declared bitrate replaces the reported one`() {
        assertEquals("AAC+ · 64 kbit · stereo", StreamQuality("AAC+", 48, 44_100, 2).chipLabel(64))
    }

    @Test
    fun `an unknown bitrate is left out, not shown as zero`() {
        assertEquals("AAC · stereo", StreamQuality("AAC", 0, 44_100, 2).chipLabel())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `technical label identifies explicit AAC profiles`() {
        assertEquals("AAC-LC · 128 kbps · 44.1 kHz · stereo", quality("mp4a.40.2").technicalLabel())
        assertEquals("HE-AAC · 128 kbps · 44.1 kHz · stereo", quality("mp4a.40.5").technicalLabel())
        assertEquals("HE-AAC v2 · 128 kbps · 44.1 kHz · stereo", quality("mp4a.40.29").technicalLabel())
        assertEquals("HE-AAC · 128 kbps · 44.1 kHz · stereo", quality("MP4A.40.5").technicalLabel())
        assertEquals("AAC · 128 kbps · 44.1 kHz · stereo", quality("mp4a.40.99").technicalLabel())
        assertEquals("AAC · 128 kbps · 44.1 kHz · stereo", quality("prefix.mp4a.40.5").technicalLabel())
        assertEquals("AAC · 128 kbps · 44.1 kHz · stereo", quality(null).technicalLabel())
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `technical label marks low-rate SBR as inferred and leaves chip codec intact`() {
        val quality = Format.Builder()
            .setSampleMimeType("audio/mp4a-latm")
            .setCodecs("mp4a.40.2")
            .setAverageBitrate(48_000)
            .setSampleRate(22_050)
            .setChannelCount(2)
            .build()
            .let { StreamQuality.of(it, 0) }

        assertEquals("AAC-LC (HE-AAC inferred) · 48 kbps · 44.1 kHz · stereo", quality.technicalLabel())
        assertEquals("AAC+ · 48 kbit · stereo", quality.chipLabel())
    }

    @OptIn(UnstableApi::class)
    private fun quality(codecs: String?) = StreamQuality.of(
        Format.Builder()
            .setSampleMimeType("audio/mp4a-latm")
            .setCodecs(codecs)
            .setAverageBitrate(128_000)
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build(),
        0
    )
}
