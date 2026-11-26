package net.mspanc.twinsenradio.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    @Test fun validatedEditPreservesLatestSourceOrder() = runBlocking {
        val validationStarted = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        val repo = StationRepository(context) {
            validationStarted.complete(Unit)
            finishValidation.await()
            body
        }
        val urls = listOf("https://radio.example/a", "https://radio.example/b", "https://radio.example/c")
        repo.replaceRemotePlaylists(urls)
        val target = repo.playlists()[1]
        val edit = async { repo.savePlaylist("Edited", target.url, target.id) }
        validationStarted.await()
        val reordered = listOf(urls[2], urls[0], urls[1])
        repo.replaceRemotePlaylists(reordered)
        finishValidation.complete(Unit)
        assertEquals(target.id, edit.await().id)
        assertEquals(reordered, repo.playlists().map { it.url })
    }

    @Test fun validatedEditDoesNotRecreateRemovedSource() = runBlocking {
        val validationStarted = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        val repo = StationRepository(context) {
            validationStarted.complete(Unit)
            finishValidation.await()
            body
        }
        repo.replaceRemotePlaylists(listOf("https://radio.example/playlist"))
        val target = repo.playlists().single()
        val edit = async { runCatching { repo.savePlaylist("Edited", target.url, target.id) } }
        validationStarted.await()
        assertTrue(repo.removePlaylist(target.id))
        finishValidation.complete(Unit)
        assertTrue(edit.await().isFailure)
        assertTrue(fresh().playlists().isEmpty())
    }

    @Test fun sortByListeningTimeOrdersAllAndFavoritesAndRestoresSavedOrderWhenDisabled() = runBlocking {
        val repo = fresh()
        val prefs = Prefs(context)
        try {
            val playlist = repo.saveLocalPlaylist(
                "Stations",
                "#EXTM3U\n#EXTINF:-1,Alpha\nhttps://radio.example/a\n#EXTINF:-1,Beta\nhttps://radio.example/b\n#EXTINF:-1,Gamma\nhttps://radio.example/c\n"
            )
            val initial = repo.all()
            assertEquals(listOf("Alpha", "Beta", "Gamma"), initial.map { it.name })
            val alpha = initial[0]
            val beta = initial[1]
            val gamma = initial[2]
            prefs.toggleFavourite(alpha.id)
            prefs.toggleFavourite(gamma.id)
            assertEquals(listOf("Alpha", "Gamma"), repo.favourites().map { it.name })

            prefs.addListeningTimeMs(gamma.id, 90_000L)
            prefs.addListeningTimeMs(beta.id, 30_000L)
            prefs.addListeningTimeMs(alpha.id, 10_000L)
            assertEquals(90_000L, prefs.listeningTimeMs(gamma.id))

            prefs.sortByListeningTime = true
            assertEquals(listOf("Gamma", "Beta", "Alpha"), repo.all().map { it.name })
            assertEquals(listOf("Gamma", "Alpha"), repo.favourites().map { it.name })
            assertEquals(listOf("Alpha", "Beta", "Gamma"), repo.sourceStations(playlist.id).map { it.name })

            prefs.sortByListeningTime = false
            assertEquals(listOf("Alpha", "Beta", "Gamma"), repo.all().map { it.name })
            assertEquals(listOf("Alpha", "Gamma"), repo.favourites().map { it.name })
        } finally {
            prefs.favourites = emptySet()
            prefs.sortByListeningTime = false
        }
    }
}
