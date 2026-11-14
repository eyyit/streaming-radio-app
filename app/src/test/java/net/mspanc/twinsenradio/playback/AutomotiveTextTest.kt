package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class AutomotiveTextTest {
    @Test fun loadingArtworkOwnsOnlyTheDescriptionLine() {
        val lines = AutomotiveText.playback("Station", "Song", "Artist", null,
            StreamQuality("AAC", 128, 44100, 2), "Favorites", true)
        assertEquals("Loading artwork…", lines.description)
        assertEquals("Artist · Song", lines.subtitle)
        assertEquals("Song", lines.title)
    }

    @Test fun completedArtworkRestoresCollectionAndQualityOnce() {
        val lines = AutomotiveText.playback("Station", "Song", "Artist", null,
            StreamQuality("AAC", 128, 44100, 2), "Favorites", false)
        assertEquals("AAC · 128 kbps · Favorites", lines.description)
    }

    @Test fun nativeDescriptionLeadsWithTheDetectedAacProfile() {
        for (profile in listOf("AAC-LC", "HE-AAC", "HE-AAC v2", "AAC-LC (HE-AAC inferred)")) {
            val quality = StreamQuality("AAC", 48, 44100, 2, technicalCodec = profile)
            val lines = AutomotiveText.playback("Station", "Song", "Artist", null, quality, "Long playlist")
            assertEquals("Song", lines.title)
            assertEquals("$profile · 48 kbps · Long playlist", lines.description)
        }
    }

    @Test fun collectionStillAppearsBeforeQualityIsKnown() {
        val lines = AutomotiveText.playback("Station", null, null, null, null,
            "All stations", false)
        assertEquals("All stations", lines.description)
    }

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
