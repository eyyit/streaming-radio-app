package net.mspanc.twinsenradio.playback

import net.mspanc.twinsenradio.net.SafeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcyArtworkParserTest {
    @Test fun parsesTitleArtworkAndCaseInsensitiveKeys() {
        val update = IcyArtworkParser.parse(
            "sTrEaMtItLe='Björk — Jóga';STREAMARTWORK='https://art.example/a.jpg'"
        )
        assertTrue(update.hasTitle)
        assertTrue(update.hasArtwork)
        assertEquals("Björk — Jóga", update.title)
        assertEquals("https://art.example/a.jpg", update.artworkUrl)
    }

    @Test fun quotedUrlMayContainQuerySemicolons() {
        val update = IcyArtworkParser.parse(
            "StreamArtwork=\"https://art.example/a.jpg?x=1;y=2\";StreamTitle='Song'"
        )
        assertEquals("https://art.example/a.jpg?x=1;y=2", update.artworkUrl)
        assertEquals("Song", update.title)
    }

    @Test fun explicitEmptyAndAbsentRemainDistinct() {
        val empty = IcyArtworkParser.parse("StreamTitle='';StreamArtwork='' ")
        assertTrue(empty.hasTitle)
        assertTrue(empty.hasArtwork)
        assertEquals("", empty.title)
        assertEquals("", empty.artworkUrl)
        val absent = IcyArtworkParser.parse("StreamUrl='https://radio.example/live'")
        assertFalse(absent.hasTitle)
        assertFalse(absent.hasArtwork)
        assertNull(absent.title)
        assertNull(absent.artworkUrl)
    }

    @Test fun rejectsInvalidSchemesAndOverlongArtworkUrlButKeepsTagPresence() {
        for (url in listOf("javascript:alert(1)", "data:image/png;base64,AAAA", "//art.example/a.jpg")) {
            val update = IcyArtworkParser.parse("StreamArtwork='$url'")
            assertTrue(update.hasArtwork)
            assertNull(update.artworkUrl)
        }
        val longUrl = "https://art.example/" + "x".repeat(2040)
        val update = IcyArtworkParser.parse("StreamArtwork='$longUrl'")
        assertTrue(update.hasArtwork)
        assertNull(update.artworkUrl)
    }

    @Test fun oversizedOrMalformedMetadataDoesNotInventValues() {
        val oversized = IcyArtworkParser.parse("StreamTitle='ok';" + "x".repeat(4080))
        assertFalse(oversized.hasTitle)
        assertFalse(oversized.hasArtwork)
        val malformed = IcyArtworkParser.parse("StreamArtwork='https://art.example/broken")
        assertTrue(malformed.hasArtwork)
        assertNull(malformed.artworkUrl)
        assertNull(malformed.title)
    }

    @Test fun unquotedUnknownAndMalformedFieldsDoNotHideLaterTags() {
        val update = IcyArtworkParser.parse(
            "stationName='Guns N' Roses; live';durationMilliseconds=30000;" +
                "StreamArtwork='https://art.example/cover.jpg';StreamTitle='Song'"
        )
        assertTrue(update.hasArtwork)
        assertEquals("https://art.example/cover.jpg", update.artworkUrl)
        assertTrue(update.hasTitle)
        assertEquals("Song", update.title)

        val afterMalformedKnown = IcyArtworkParser.parse(
            "StreamArtwork=bad;StreamTitle='Song';StreamArtwork='https://art.example/ok.jpg'"
        )
        assertTrue(afterMalformedKnown.hasArtwork)
        assertEquals("https://art.example/ok.jpg", afterMalformedKnown.artworkUrl)
        assertEquals("Song", afterMalformedKnown.title)
    }

    @Test fun capturedNovaSourceHostArtworkPassesDownloadPolicy() {
        val capturedFormatUrl = "https://www.lesinrocks.com/wp-content/uploads/contract-cover.jpg"
        val update = IcyArtworkParser.parse("StreamArtwork='$capturedFormatUrl'")
        assertEquals(capturedFormatUrl, update.artworkUrl)
        val checkedUrl = runCatching {
            SafeHttp.checkedUrl(
                update.artworkUrl!!, false, StreamArtworkPolicy.allowedHosts
            ).toString()
        }.getOrNull()
        assertEquals(capturedFormatUrl, checkedUrl)
    }

    @Test fun unrelatedTagsIncludingStreamUrlDoNotBecomeArtwork() {
        val update = IcyArtworkParser.parse(
            "StreamUrl='https://radio.example/cover.jpg';Name='station';Genre='jazz'"
        )
        assertFalse(update.hasArtwork)
        assertFalse(update.hasTitle)
        assertNull(update.artworkUrl)
        assertNull(update.title)
    }
}
