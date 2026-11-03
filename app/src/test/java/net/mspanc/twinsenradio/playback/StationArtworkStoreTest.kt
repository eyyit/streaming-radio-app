package net.mspanc.twinsenradio.playback

import net.mspanc.twinsenradio.data.RadioBrowser
import net.mspanc.twinsenradio.data.Station
import org.junit.Assert.*
import org.junit.Test

class StationArtworkStoreTest {
    private val station = Station("one", "Radio Nova", "", "https://relay.example/nova")
    private fun found(name: String, stream: String, logo: String) =
        RadioBrowser.Found("id", name, stream, logo, null, null, null, 0)
    private fun logo(name: String, stream: String, url: String, votes: Int = 0) =
        RadioBrowser.ArtworkLogo(name, url, stream, votes)
    @Test fun usesFirstRankedExactNameLogo() {
        assertEquals("https://a.example/logo.jpg", StationArtworkStore.pickLogo(station, listOf(
            found("Radio Nova", "https://a.example/live", "https://a.example/logo.jpg"),
            found("Radio Nova", "https://b.example/live", "https://b.example/logo.jpg"))))
        assertEquals("https://a.example/logo.jpg", StationArtworkStore.pickLogo(station,
            listOf(found("Radio Nova", "https://a.example/live", "https://a.example/logo.jpg"))))
    }
    @Test fun exactStreamMatchWinsOverAnAmbiguousName() {
        assertEquals("https://exact.example/logo.jpg", StationArtworkStore.pickLogo(station, listOf(
            found("Radio Nova", "https://b.example/live", "https://b.example/logo.jpg"),
            found("Nova", station.stream, "https://exact.example/logo.jpg"))))
    }

    @Test fun candidateUrlsAreDistinctHttpLogosAndCappedAtTwenty() {
        val entries = (1..24).map { index ->
            logo("Station $index", "https://stream$index.example/live", "https://logo$index.example/image.jpg")
        }
        val result = StationArtworkStore.candidateUrls(station, entries)
        assertEquals(20, result.size)
        assertEquals(20, result.distinct().size)
        assertTrue(result.all { it.startsWith("https://") })
    }

    @Test fun candidateUrlsRejectNonHttpAndDuplicateLogos() {
        val result = StationArtworkStore.candidateUrls(station, listOf(
            logo("Radio Nova", "https://other.example/live", "file:///local/logo.png"),
            logo("Radio Nova", "https://one.example/live", "https://logos.example/nova.png"),
            logo("Nova mirror", "https://two.example/live", "https://logos.example/nova.png")
        ))
        assertEquals(listOf("https://logos.example/nova.png"), result)
    }

    @Test fun refreshDoesNotPretendTheSoleChosenImageIsAnotherResult() {
        assertTrue(StationArtworkStore.alternateCandidateIndices(listOf("chosen"), "chosen", 0).isEmpty())
        assertTrue(StationArtworkStore.alternateCandidateIndices(emptyList(), "chosen", 0).isEmpty())
    }

    @Test fun refreshPreservesWrappedOrderAndBoundsAttemptsAfterSkippingChosen() {
        val urls = (0..8).map { "url$it" }
        assertEquals(listOf(8, 0, 1, 2, 3), StationArtworkStore.alternateCandidateIndices(urls, "url7", 7))
        assertEquals(listOf(1, 2, 0), StationArtworkStore.alternateCandidateIndices(urls.take(3), null, 1))
    }
}

