package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserArtworkTest {
    @Test fun artworkParserKeepsLogoAlternativesBeforeStreamMerge() {
        val records = """
            [
              {"name":"Nova Soul","url_resolved":"https://stream.example/a","favicon":"","bitrate":256,"votes":20},
              {"name":"Nova Soul","url_resolved":"https://stream.example/b","favicon":"https://logos.example/nova.png","bitrate":256,"votes":18},
              {"name":"Nova Soul mirror","url_resolved":"https://stream.example/a","favicon":"https://logos.example/alternate.png","bitrate":128,"votes":10},
              {"name":"Nova Soul copy","url_resolved":"https://stream.example/c","favicon":"https://logos.example/nova.png","bitrate":64,"votes":8}
            ]
        """.trimIndent()

        val logos = RadioBrowser.parseArtwork(records)

        assertEquals(
            listOf("https://logos.example/nova.png", "https://logos.example/alternate.png"),
            logos.map { it.url }
        )
        assertEquals("https://stream.example/b", logos.first().stream)
    }

    @Test fun emptyAndMissingFaviconResponsesHaveNoLogoCandidates() {
        assertTrue(RadioBrowser.parseArtwork("[]").isEmpty())
        assertTrue(RadioBrowser.parseArtwork("""[{"name":"Nova Soul","url_resolved":"https://stream.example/a","favicon":""}]""").isEmpty())
    }

    @Test fun ordinaryDiscoveryStillMergesStreamsByBestBitrate() {
        val records = """
            [
              {"name":"Nova Soul","url_resolved":"https://stream.example/a","favicon":"","bitrate":256},
              {"name":"Nova Soul","url_resolved":"https://stream.example/b","favicon":"https://logos.example/nova.png","bitrate":128}
            ]
        """.trimIndent()

        val merged = RadioBrowser.parse(records)

        assertEquals(1, merged.size)
        assertEquals("https://stream.example/a", merged.single().stream)
        assertNull(merged.single().faviconUrl)
        assertEquals(1, merged.single().alternates.size)
    }
}
