package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class AutomotiveTextTest {
    @Test fun unnamedTrackStillNamesItsStation() {
        val text = AutomotiveText.playback("Radio Nova", "", null, null, null)
        assertEquals("Radio Nova", text.title)
        assertEquals("", text.subtitle)
    }

    @Test fun trackStationAndQualityRemainAvailableToHostAndCluster() {
        val quality = StreamQuality("AAC", 128, 44100, 2)
        val text = AutomotiveText.playback("Radio Nova", "Teardrop", "Massive Attack", "Mezzanine", quality)
        assertEquals("Teardrop", text.title)
        assertEquals("Massive Attack · Teardrop · AAC · 128 kbps", text.subtitle)
        assertEquals("Radio Nova · Mezzanine · AAC · 128 kbps · 44.1 kHz · stereo", text.description)
    }

    @Test fun unknownQualityNeverClaimsZeroOrInventedBitrate() {
        val text = AutomotiveText.playback("Talk station", null, null, null, StreamQuality("", 0, 0, 0))
        assertEquals("Talk station", text.title)
        assertFalse(text.subtitle.contains("0 kbps"))
        assertEquals("Talk station", text.description)
    }
}
