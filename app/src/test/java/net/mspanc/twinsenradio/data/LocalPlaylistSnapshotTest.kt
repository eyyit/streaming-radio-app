package net.mspanc.twinsenradio.data

import org.junit.Assert.*
import org.junit.Test

class LocalPlaylistSnapshotTest {
    private val local = PlaylistSource("saved", PlaylistLibrary.LOCAL_SNAPSHOT_URL, "Saved stations")
    private val body = "#EXTM3U\n#EXTINF:-1,Sample station\nhttps://radio.example/live\n"

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun onlyExactInternalTokenIsAcceptedAndNeverScheduledForRefresh() {
        CatalogLimits.sources(listOf(local))
        assertTrue(local.isLocal)
        val remote = PlaylistSource("remote", "https://example.org/list.m3u", "Remote")
        assertEquals(listOf(remote), PlaylistLibrary.remoteSources(listOf(local, remote)))
        listOf("local:other", "LOCAL:saved-stations", "file:///tmp/stations", "content://catalog/stations").forEach {
            rejects { CatalogLimits.sources(listOf(local.copy(url = it))) }
        }
    }

    @Test fun localCacheUsesOrdinaryParserAndIdsCanBePinnedAcrossSourceAndStreamChanges() {
        val old = M3uParser.parse(body, "https://old.example/list").single()
        val cached = PlaylistLibrary.parseCachedPlaylist(body, local.url).single()
        assertEquals(old.name, cached.name)
        assertEquals(old.stream, cached.stream)
        assertEquals(old.id, PlaylistLibrary.stationId(cached.stream, cached.id, mapOf(cached.stream to old.id)))
        val replacement = "https://official.example/live"
        assertEquals(old.id, PlaylistLibrary.stationId(replacement, cached.id, mapOf(replacement to old.id)))
    }

    @Test fun localCacheRemainsBoundedAndRejectsHlsOrEmptyInput() {
        rejects { PlaylistLibrary.parseCachedPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6", local.url) }
        rejects { PlaylistLibrary.parseCachedPlaylist("#EXTM3U", local.url) }
        rejects { PlaylistLibrary.parseCachedPlaylist("x".repeat(CatalogLimits.MAX_BYTES + 1), local.url) }
        val overLimit = (1..501).joinToString("\n") { "https://radio.example/$it" }
        rejects { PlaylistLibrary.parseCachedPlaylist(overLimit, local.url) }
    }

    @Test fun addAndUrlEditCannotAdmitAnyLocalTokenOrHttp() {
        PlaylistLibrary.requireRemoteUrl("https://example.org/list.m3u")
        listOf(local.url, "file:///tmp/stations", "content://stations", "http://example.org/list.m3u").forEach {
            rejects { PlaylistLibrary.requireRemoteUrl(it) }
        }
    }

    @Test fun removingLocalSourceUsesSameMembershipAndDetachedFavoriteRules() {
        val saved = PlaylistLibrary.parseCachedPlaylist(body, local.url)
        val before = PlaylistLibrary.aggregate(listOf(local to saved))
        assertEquals(listOf(local.id), before.single().memberships)
        val after = PlaylistLibrary.aggregate(emptyList())
        assertTrue(after.isEmpty())
        assertEquals(saved, PlaylistLibrary.favorites(emptyList(), saved, setOf(saved.single().id)))
    }
}
