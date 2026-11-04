package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun missingFirstReleaseArtworkDoesNotHideAnotherRelease() {
        val body = """{"recordings":[{"title":"Bohemian Rhapsody",
            "artist-credit":[{"name":"Queen"}],
            "releases":[
                {"id":"first","title":"No Art"},
                {"id":"second","title":"A Night at the Opera"}
            ]}]}"""
        val checked = mutableListOf<String>()
        val selected = ReleaseCatalog.select(body, "Queen",
            "Bohemian Rhapsody") { url ->
            checked += url
            url.endsWith("/second/front-500")
        }
        assertEquals("https://coverartarchive.org/release/second/front-500",
            (selected as ReleaseCatalog.Result.Found).candidate.artworkUrl)
        assertEquals(2, checked.size)
    }

    @Test fun firstRecordingCannotExhaustTheCandidateBudget() {
        val firstReleases = (1..4).joinToString(",") { """{"id":"missing-$it"}""" }
        val body = """{"recordings":[
            {"title":"Everywhere","artist-credit":[{"name":"Fleetwood Mac"}],
            "releases":[$firstReleases]},
            {"title":"Everywhere","artist-credit":[{"name":"Fleetwood Mac"}],
            "releases":[{"id":"later-recording"}]}]}"""
        val checked = mutableListOf<String>()
        val wanted = ReleaseCatalog.releaseUrl("later-recording")
        val result = ReleaseCatalog.select(body, "Fleetwood Mac", "Everywhere") {
            checked += it
            it == wanted
        }
        assertTrue("Later matching recording must get a bounded candidate slot", wanted in checked)
        assertEquals(wanted, (result as ReleaseCatalog.Result.Found).candidate.artworkUrl)
        assertEquals(listOf(ReleaseCatalog.releaseUrl("missing-1"), wanted), checked)
    }

    @Test fun releaseGroupFrontIsTriedAfterReleaseCoversAreMissing() {
        val body = """{"recordings":[{"title":"Bohemian Rhapsody",
            "artist-credit":[{"name":"Queen"}],
            "releases":[{"id":"release-one","title":"A Night at the Opera",
                "release-group":{"id":"group-one"}}]}]}"""
        val checked = mutableListOf<String>()
        val result = ReleaseCatalog.select(body, "Queen", "Bohemian Rhapsody") { url ->
            checked += url
            url == ReleaseCatalog.groupUrl("group-one")
        }
        assertEquals(ReleaseCatalog.groupUrl("group-one"),
            (result as ReleaseCatalog.Result.Found).candidate.artworkUrl)
        assertEquals(listOf(
            ReleaseCatalog.releaseUrl("release-one"),
            ReleaseCatalog.groupUrl("group-one")
        ), checked)
    }

    @Test fun transientCoverArchiveFailureIsNotACompletedMiss() {
        val body = """{"recordings":[{"title":"Bohemian Rhapsody",
            "artist-credit":[{"name":"Queen"}],
            "releases":[{"id":"release-one","title":"A Night at the Opera"}]}]}"""
        val result = ReleaseCatalog.select(body, "Queen", "Bohemian Rhapsody") {
            throw net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException(503, 45)
        }
        assertEquals(ReleaseCatalog.Result.TemporaryFailure(45), result)
        assertFalse(CoverArtLookup.shouldCache(
            CoverArtLookup.CatalogLookupResult.TemporaryFailure(45)))
        assertTrue(CoverArtLookup.shouldCache(CoverArtLookup.CatalogLookupResult.NotFound))
    }

    @Test fun releaseThrottleStopsBeforeAnotherReleaseOrGroup() {
        assertProviderCooldownStopsSearch(groupFailure = false)
    }

    @Test fun groupThrottleStopsBeforeAnotherGroup() {
        assertProviderCooldownStopsSearch(groupFailure = true)
    }

    private fun assertProviderCooldownStopsSearch(groupFailure: Boolean) {
        val body = """{"recordings":[{"title":"Everywhere",
            "artist-credit":[{"name":"Fleetwood Mac"}],"releases":[
            {"id":"a","release-group":{"id":"ga"}},
            {"id":"b","release-group":{"id":"gb"}}]}]}"""
        for ((status, retryAfter) in listOf(429 to null, 503 to null, 500 to 300L)) {
            val checked = mutableListOf<String>()
            val failingUrl = if (groupFailure) ReleaseCatalog.groupUrl("ga")
                else ReleaseCatalog.releaseUrl("a")
            val result = ReleaseCatalog.select(body, "Fleetwood Mac", "Everywhere") { url ->
                checked += url
                when {
                    url == failingUrl -> throw net.mspanc.twinsenradio.net.SafeHttp
                        .HttpStatusException(status, retryAfter)
                    groupFailure && url.contains("/release/") -> false
                    else -> true
                }
            }
            assertEquals("Provider cooldown must stop status $status",
                ReleaseCatalog.Result.TemporaryFailure(retryAfter), result)
            assertEquals(failingUrl, checked.last())
            assertEquals(if (groupFailure) 3 else 1, checked.size)
        }
    }

    @Test fun releaseSearchCancellationPropagates() {
        val body = """{"recordings":[{"title":"Bohemian Rhapsody",
            "artist-credit":[{"name":"Queen"}],
            "releases":[{"id":"release-one","title":"A Night at the Opera"}]}]}"""
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        var propagated = false
        try {
            ReleaseCatalog.select(body, "Queen", "Bohemian Rhapsody") {
                throw cancelled
            }
        } catch (error: java.util.concurrent.CancellationException) {
            assertSame(cancelled, error)
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test fun rejectedImageCanAdvancePastExcludedRelease() {
        val body = """{"recordings":[{"title":"Bohemian Rhapsody",
            "artist-credit":[{"name":"Queen"}],
            "releases":[
                {"id":"first","title":"First"},
                {"id":"second","title":"Second"}
            ]}]}"""
        val excluded = setOf(ReleaseCatalog.releaseUrl("first"))
        val result = ReleaseCatalog.select(body, "Queen", "Bohemian Rhapsody",
            excluded) { it.endsWith("/second/front-500") }
        assertEquals(ReleaseCatalog.releaseUrl("second"),
            (result as ReleaseCatalog.Result.Found).candidate.artworkUrl)
    }

    @Test fun wrongRecordingIsSkippedAndProbeBudgetIsBounded() {
        val releases = (1..12).joinToString(",") { index ->
            """{"id":"r$index","title":"Album $index","release-group":{"id":"g$index"}}"""
        }
        val body = """{"recordings":[
            {"title":"Boy","artist-credit":[{"name":"Lee Brice"}],"releases":[{"id":"wrong","title":"Wrong"}]},
            {"title":"Bohemian Rhapsody","artist-credit":[{"name":"Queen"}],"releases":[$releases]}
        ]}"""
        val checked = mutableListOf<String>()
        val result = ReleaseCatalog.select(body, "Queen", "Bohemian Rhapsody") {
            checked += it
            false
        }
        assertEquals(ReleaseCatalog.Result.NotFound, result)
        assertEquals(ReleaseCatalog.MAX_ARTWORK_PROBES, checked.size)
        assertTrue(checked.none { it.contains("wrong") })
    }

    @Test fun groupFallbackDeduplicatesAndHonorsExclusions() {
        val body = """{"recordings":[{"title":"Everywhere",
            "artist-credit":[{"name":"Fleetwood Mac"}],"releases":[
            {"id":"a","release-group":{"id":"same"}},
            {"id":"b","release-group":{"id":"same"}},
            {"id":"c","release-group":{"id":"other"}}]}]}"""
        val checked = mutableListOf<String>()
        val excluded = setOf(ReleaseCatalog.groupUrl("other"))
        val result = ReleaseCatalog.select(body, "Fleetwood Mac", "Everywhere", excluded) {
            checked += it
            false
        }
        assertEquals(ReleaseCatalog.Result.NotFound, result)
        assertEquals(1, checked.count { it == ReleaseCatalog.groupUrl("same") })
        assertFalse(ReleaseCatalog.groupUrl("other") in checked)
    }

    @Test fun temporaryFailureRetriesThenCachesRecoveredValue() = kotlinx.coroutines.runBlocking {
        val key = "recovery-test-${System.nanoTime()}"
        var calls = 0
        val recovered = CoverArtLookup.CatalogLookupResult.Found(info())
        suspend fun load() = CoverArtLookup.cachedLookup(key) {
            calls++
            if (calls == 1) CoverArtLookup.CatalogLookupResult.TemporaryFailure(5)
            else recovered
        }
        assertEquals(CoverArtLookup.CatalogLookupResult.TemporaryFailure(5), load())
        assertEquals(recovered, load())
        assertEquals(recovered, load())
        assertEquals(2, calls)
    }

    @Test fun completedMissIsCachedButCancellationIsNot() = kotlinx.coroutines.runBlocking {
        val key = "miss-test-${System.nanoTime()}"
        var calls = 0
        repeat(2) {
            assertEquals(CoverArtLookup.CatalogLookupResult.NotFound,
                CoverArtLookup.cachedLookup(key) {
                    calls++
                    CoverArtLookup.CatalogLookupResult.NotFound
                })
        }
        assertEquals(1, calls)
        val cancelledKey = "cancel-test-${System.nanoTime()}"
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        try {
            CoverArtLookup.cachedLookup(cancelledKey) { throw cancelled }
            throw AssertionError("Cancellation was swallowed")
        } catch (error: java.util.concurrent.CancellationException) {
            assertSame(cancelled, error)
        }
        assertEquals(CoverArtLookup.CatalogLookupResult.Found(info()),
            CoverArtLookup.cachedLookup(cancelledKey) {
                CoverArtLookup.CatalogLookupResult.Found(info())
            })
    }

    @Test fun gateSpacesRequestsAndReturnsImmediatelyDuringServerCooldown() = kotlinx.coroutines.runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val gate = CoverArtLookup.MusicBrainzRequestGate({ now }) { millis ->
            waits += millis
            now += millis * 1_000_000L
        }
        assertTrue(gate.beforeRequest())
        assertTrue(gate.beforeRequest())
        assertEquals(listOf(1_000L), waits)
        gate.failed(300)
        assertFalse(gate.beforeRequest())
        assertEquals(300L, gate.retryAfterSeconds())
        assertEquals(listOf(1_000L), waits)
        now += 299_000_000_000L
        assertFalse(gate.beforeRequest())
        now += 1_000_000_000L
        assertTrue(gate.beforeRequest())
        gate.succeeded()
        gate.failed()
        assertEquals(1L, gate.retryAfterSeconds())
    }

    @Test fun sameTitleByAnotherArtistIsSkipped() {
        val body = """{"recordings":[
            {"title":"Everywhere","artist-credit":[{"name":"Another Artist"}],
            "releases":[{"id":"wrong"}]},
            {"title":"Everywhere","artist-credit":[{"name":"Fleetwood Mac"}],
            "releases":[{"id":"right"}]}]}"""
        val checked = mutableListOf<String>()
        val result = ReleaseCatalog.select(body, "Fleetwood Mac", "Everywhere") {
            checked += it
            true
        }
        assertEquals(ReleaseCatalog.releaseUrl("right"),
            (result as ReleaseCatalog.Result.Found).candidate.artworkUrl)
        assertEquals(listOf(ReleaseCatalog.releaseUrl("right")), checked)
    }

    @Test fun swappedMetadataUsesTheExistingMatchingRule() {
        val candidate = info("Lady Gaga", "Poker Face (Radio Mix)")
        assertSame(candidate, CoverArtLookup.matchingAlternative(
            "POKER FACE", "LADY GAGA", candidate, emptySet()))
    }
}
