package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistLibraryTest {
    @Test
    fun sourceRemovalKeepsSharedStationAndDetachedFavoriteOnlyInFavorites() {
        val shared=station("shared","https://radio/shared")
        val detached=station("detached","https://radio/detached")
        val active=PlaylistLibrary.aggregate(listOf(PlaylistSource("remaining","https://source/list","Remaining") to listOf(shared))).map { it.station }
        assertEquals(listOf("shared"),active.map { it.id })
        assertEquals(listOf("shared","detached"),PlaylistLibrary.favorites(active,listOf(detached),setOf("shared","detached")).map { it.id })
        assertEquals(emptyList<Station>(),PlaylistLibrary.favorites(emptyList(),listOf(detached),emptySet()))
    }
    @Test
    fun changedStreamWithReusedTvgIdGetsNewIdentity() {
        val known = mapOf("https://old/stream" to "tvg-one")
        assertEquals("tvg-one", PlaylistLibrary.stationId("https://old/stream", "new-parser-id", known))
        assertEquals("stream_${M3uParser.stableKey("https://new/stream")}",
            PlaylistLibrary.stationId("https://new/stream", "tvg-one", known))
    }

    @Test
    fun prebuiltIdentitySetAvoidsScanningKnownValuesAndPreservesCollisionRules() {
        val known = object : Map<String, String> by mapOf("https://old/stream" to "old") {
            override val values: Collection<String> get() = error("Must use the prebuilt ID set")
        }
        val used = setOf("old")
        assertEquals("old", PlaylistLibrary.stationId("https://old/stream", "new", known, used))
        assertEquals("new", PlaylistLibrary.stationId("https://new/stream", "new", known, used))
        assertEquals("stream_${M3uParser.stableKey("https://new/stream")}",
            PlaylistLibrary.stationId("https://new/stream", "old", known, used))
    }

    @Test
    fun independentOrdersSurviveSourceReorderingAndAppendNewStations() {
        val source = listOf("c", "a", "b", "new")
        val all = PlaylistLibrary.reconcileOrder(listOf("b", "a", "c"), source, source)
        val favorites = PlaylistLibrary.reconcileOrder(listOf("c", "b"), listOf("b", "c"), source)
        assertEquals(listOf("b", "a", "c", "new"), all)
        assertEquals(listOf("c", "b"), favorites)
        assertEquals(listOf("a", "b", "c", "new"), PlaylistLibrary.move(all, "b", 1))
        assertEquals(listOf("c", "b"), favorites)
    }

    @Test
    fun bulkEditorReconcilesRemoteOrderAndRetainsLocalAndCustomIdentity() {
        val a = PlaylistSource("a", "https://a/list.m3u", "My A", 7)
        val local = PlaylistSource("saved", PlaylistLibrary.LOCAL_SNAPSHOT_URL, "My saved", 4)
        val b = PlaylistSource("b", "https://b/list.m3u", "My B", 9)
        val result = PlaylistLibrary.reconcileRemoteUrls(listOf(a, local, b), listOf(
            "  https://b/list.m3u  ", "", "https://b/list.m3u", "https://new/list.m3u"
        ))
        assertEquals(b, result[0])
        assertEquals(local, result[1])
        assertEquals("https://new/list.m3u", result[2].url)
        assertEquals("new / list.m3u", result[2].name)
        assertEquals(3, result.map { it.id }.distinct().size)
        assertEquals(listOf(b), result.filter { it.id == "b" })
        assertEquals(listOf(local), PlaylistLibrary.reconcileRemoteUrls(listOf(a, local, b), emptyList()))
    }

    @Test
    fun bulkEditorRejectsInvalidUrlsAndCountsRetainedLocalSourcesBeforeReturning() {
        val local = PlaylistSource("saved", PlaylistLibrary.LOCAL_SNAPSHOT_URL, "Saved")
        listOf("http://radio/list", "file:///tmp/list", "local:saved-stations",
            "https://user:pass@example.com/list", "https://", "https://radio/" + "x".repeat(CatalogLimits.MAX_URL))
            .forEach { url ->
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                    PlaylistLibrary.reconcileRemoteUrls(listOf(local), listOf(url))
                }
            }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PlaylistLibrary.reconcileRemoteUrls(listOf(local),
                (1..CatalogLimits.MAX_SOURCES).map { "https://radio/$it.m3u" })
        }
    }

    private fun station(id: String, stream: String) = Station(
        id = id, name = id, genre = "Test", stream = stream,
        source = Station.Source.USER_M3U
    )

    @Test
    fun aggregatesExactStreamUrlsAndKeepsSourceMemberships() {
        val a = PlaylistSource("a", "https://a/list.m3u", "A")
        val b = PlaylistSource("b", "https://b/list.m3u", "B")
        val result = PlaylistLibrary.aggregate(listOf(
            a to listOf(station("a1", "https://radio/shared"), station("a2", "https://radio/other")),
            b to listOf(station("b1", "https://radio/shared"))
        ))
        assertEquals(listOf("https://radio/shared", "https://radio/other"), result.map { it.station.stream })
        assertEquals(listOf("a", "b"), result.first().memberships)
    }

    @Test
    fun sourceReconciliationRetainsSurvivorsThenAppendsNewEntries() {
        assertEquals(listOf("b", "a", "c"), PlaylistLibrary.reconcileOrder(
            saved = listOf("b", "removed", "a"), current = listOf("a", "b", "c"),
            discovered = listOf("c", "b")
        ))
    }

    @Test
    fun movementIsBoundedAndRejectsNonUnitSteps() {
        val order = listOf("a", "b", "c")
        assertEquals(order, PlaylistLibrary.move(order, "a", -1))
        assertEquals(listOf("b", "a", "c"), PlaylistLibrary.move(order, "a", 1))
        assertSame(order, PlaylistLibrary.move(order, "missing", 1))
    }
}
