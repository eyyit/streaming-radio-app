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
        assertEquals("Massive Attack · Teardrop", text.subtitle)
        assertEquals("AAC · 128 kbps", text.description)
    }

    @Test fun unknownQualityNeverClaimsZeroOrInventedBitrate() {
        val text = AutomotiveText.playback("Talk station", null, null, null, StreamQuality("", 0, 0, 0))
        assertEquals("Talk station", text.title)
        assertFalse(text.subtitle.contains("0 kbps"))
        assertEquals("", text.description)
    }
    @Test fun playbackDoesNotRepeatTechnicalDetailsOrStation() {
        val text = AutomotiveText.playback("Nova Hip-Hop", "Still D.R.E.", "Dr. Dre", null,
            StreamQuality("AAC", 256, 48000, 2))
        assertEquals("Dr. Dre · Still D.R.E.", text.subtitle)
        assertEquals("AAC · 256 kbps", text.description)
    }

}
