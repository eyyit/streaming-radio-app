package net.mspanc.twinsenradio.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImportedPlaylistTest {
    private val body = "#EXTM3U\n#EXTINF:-1,Example station\nhttps://radio.example/live\n"
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun fresh() = StationRepository::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)

    @Test fun localImportsAreSeparatePersistedSourcesAndNeverRemoteRefresh() = runBlocking {
        val repo = fresh()
        val first = repo.saveLocalPlaylist("First", body)
        val second = repo.saveLocalPlaylist("Second", body.replace("/live", "/other"))
        assertTrue(first.isLocal)
        assertTrue(second.isLocal)
        assertNotEquals(first.url, second.url)
        assertEquals(2, repo.playlists().size)
        assertEquals(1, repo.sourceStations(first.id).size)
        assertTrue(PlaylistLibrary.remoteSources(repo.playlists()).isEmpty())
        repo.refreshUserLists(force = true)
        assertEquals(0, repo.lastRefreshFailureCount)
        val restarted = fresh()
        assertEquals(listOf("First", "Second"), restarted.playlists().map { it.name })
        assertEquals(1, restarted.sourceStations(first.id).size)
        assertTrue(restarted.renameLocalPlaylist("Renamed", first.id))
        assertEquals(listOf("Renamed", "Second"), restarted.playlists().map { it.name })
        val cacheDirectory = java.io.File(context.filesDir, "playlists")
        val firstCache = java.io.File(cacheDirectory, M3uParser.stableKey(first.url) + ".m3u")
        val secondCache = java.io.File(cacheDirectory, M3uParser.stableKey(second.url) + ".m3u")
        assertTrue(firstCache.exists())
        assertTrue(secondCache.exists())
        assertTrue(restarted.removePlaylist(first.id))
        assertFalse(firstCache.exists())
        assertTrue(secondCache.exists())
        val afterRemoval = fresh()
        assertEquals(1, afterRemoval.playlists().size)
        assertEquals(listOf(second.id), afterRemoval.playlists().map { it.id })
        assertEquals(1, afterRemoval.sourceStations(second.id).size)
        assertFalse(afterRemoval.playlists().any { it.id == first.id })
    }

    @Test fun badOrOversizedFileCannotReplaceExistingLibrary() = runBlocking {
        val repo = fresh()
        repo.saveLocalPlaylist("Keep me", body)
        val before = repo.playlists()
        for (candidate in listOf("#EXTM3U", "#EXTM3U\n#EXT-X-TARGETDURATION:6",
            "x".repeat(CatalogLimits.MAX_BYTES + 1))) {
            val failure = runCatching { repo.saveLocalPlaylist("Bad", candidate) }.exceptionOrNull()
            assertTrue("Expected invalid playlist rejection", failure is IllegalArgumentException)
            assertEquals(before, repo.playlists())
            assertEquals(before, fresh().playlists())
        }
    }

    @Test fun importedSourcesStillEnforceSourceLimitAndRejectArbitraryLocalPaths() = runBlocking {
        val repo = fresh()
        repeat(CatalogLimits.MAX_SOURCES) { repo.saveLocalPlaylist("List $it", body) }
        val failure = runCatching { repo.saveLocalPlaylist("Too many", body) }.exceptionOrNull()
        assertTrue("Expected source cap rejection", failure is IllegalArgumentException)
        assertEquals(CatalogLimits.MAX_SOURCES, fresh().playlists().size)
        for (url in listOf("local:imported:../../../etc/passwd", "local:imported:bad",
            "local:imported:12345678-1234-1234-1234-123456789abg")) {
            assertFalse(PlaylistLibrary.isLocalSnapshot(url))
        }
    }
}
