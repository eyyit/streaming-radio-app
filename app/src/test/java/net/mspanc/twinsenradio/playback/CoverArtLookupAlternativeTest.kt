package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CoverArtLookupAlternativeTest {
    private fun info(artist: String? = "Fleetwood Mac", title: String? = "Everywhere",
                     art: String? = "https://art.example/other.jpg") = CoverArtLookup.TrackInfo(
        artworkUrl = art, album = "Tango in the Night", year = 1987,
        isSingle = false, trackName = title, artistName = artist
    )

    @Test fun matchingNewImageSurvivesRefresh() {
        val candidate = info()
        assertSame(candidate, CoverArtLookup.matchingAlternative(
            "Fleetwood Mac", "Everywhere", candidate, setOf("https://art.example/current.jpg")))
    }

    @Test fun looseSearchMustNotReplaceArtworkWithAnotherTrack() {
        assertNull(CoverArtLookup.matchingAlternative(
            "Fast Boy", "Music Sounds", info("Lee Brice", "Boy"), emptySet()))
        assertNull(CoverArtLookup.matchingAlternative(
            "Fleetwood Mac", "Everywhere", info(title = null), emptySet()))
    }

    @Test fun sameImageAndMissingImagesAreNotAlternatives() {
        val candidate = info()
        assertNull(CoverArtLookup.matchingAlternative(
            "Fleetwood Mac", "Everywhere", candidate, setOf(candidate.artworkUrl!!)))
        assertNull(CoverArtLookup.matchingAlternative(
            "Fleetwood Mac", "Everywhere", info(art = null), emptySet()))
        assertNull(CoverArtLookup.matchingAlternative(
            "Fleetwood Mac", "Everywhere", info(art = ""), emptySet()))
    }

    @Test fun swappedMetadataUsesTheExistingMatchingRule() {
        val candidate = info("Lady Gaga", "Poker Face (Radio Mix)")
        assertSame(candidate, CoverArtLookup.matchingAlternative(
            "POKER FACE", "LADY GAGA", candidate, emptySet()))
    }
}
