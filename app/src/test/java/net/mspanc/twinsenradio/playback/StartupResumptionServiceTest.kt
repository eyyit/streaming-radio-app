package net.mspanc.twinsenradio.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StartupResumptionServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private val imported = "/playlist/startup"
    private val controller get() = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        context.packageName, android.os.Process.myPid(), android.os.Process.myUid(),
        0, 0, false, Bundle.EMPTY, true)

    @Before fun seedPersistedCatalog() {
        context.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("station_navigation", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit().clear()
            .putString("sources", """[{"id":"startup","url":"https://radio.example/list.m3u","name":"Startup"}]""")
            .commit()
        val file = File(context.filesDir, "playlists/" +
            M3uParser.stableKey("https://radio.example/list.m3u") + ".m3u")
        file.parentFile!!.mkdirs()
        file.writeText("#EXTM3U\n#EXTINF:-1,Alpha\nhttps://radio.example/a\n" +
            "#EXTINF:-1,Beta\nhttps://radio.example/b\n")
        prefs = Prefs(context).apply {
            onlineArtworkEnabled = false
            favourites = emptySet()
            presetOrder = emptyList()
        }
        repo = newRepository()
        assertEquals(2, repo.all().size)
    }

    @Test fun contextualFirstStationPlayPersistsScopeAcrossColdResume() {
        prefs.activeBrowseParent = ""
        val selected = repo.sourceStations("startup").last()
        withService { service, player, callback, session ->
            val result = callback.onSetMediaItems(session, controller, mutableListOf(
                MediaItem.Builder().setMediaId(
                    Station.contextualMediaId(selected.mediaId, imported)).build()), 0, 0).get()
            val expectedParent = imported
            assertEquals(expectedParent, Prefs(context).activeBrowseParent)
            val expectedQueue = repo.sourceStations("startup")
            assertEquals(expectedQueue.map { it.mediaId }, result.mediaItems.map { it.mediaId })
            assertEquals(selected.mediaId, result.mediaItems[result.startIndex].mediaId)
            assertFalse(player.playWhenReady)
        }
        prefs.recent = listOf(selected.id)
        repo = newRepository()
        val expectedParent = imported
        assertResume(expectedParent, selected, repo.sourceStations("startup"))
    }

    @Test fun coldResumeRestoresAllFavoritesAndImportedWithoutAutoplay() {
        val stations = repo.all()
        prefs.favourites = setOf(stations.last().id)
        for (parent in listOf("/all", RadioService.NODE_FAVOURITES, imported)) {
            val members = when (parent) {
                RadioService.NODE_FAVOURITES -> repo.favourites()
                imported -> repo.sourceStations("startup")
                else -> repo.all()
            }
            prefs.activeBrowseParent = parent
            prefs.recent = listOf(members.last().id)
            repo = newRepository()
            assertResume(parent, members.last(), members)
        }
    }

    @Test fun missingAndEmptyCollectionKeepRecoverableStation() {
        val selected = repo.all().last()
        prefs.recent = listOf(selected.id)
        for (parent in listOf("/playlist/deleted", RadioService.NODE_FAVOURITES)) {
            prefs.activeBrowseParent = parent
            assertResume(parent, selected, listOf(selected))
        }
    }

    @Test fun alreadyLoadedStationWinsOverPersistedHistory() {
        val stations = repo.all()
        prefs.activeBrowseParent = imported
        prefs.recent = listOf(stations.first().id)
        withService { _, player, callback, session ->
            player.setMediaItem(MediaItem.Builder().setMediaId(stations.last().mediaId)
                .setUri(stations.last().stream).build())
            val result = callback.onPlaybackResumption(session, controller).get()
            assertEquals(stations.last().mediaId, result.mediaItems[result.startIndex].mediaId)
            assertEquals(stations.last().mediaId, player.currentMediaItem!!.mediaId)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun shufflePreferenceAndTraversalSurviveFreshServiceAndResumption() {
        val stations = repo.all()
        prefs.activeBrowseParent = imported
        prefs.randomStationOrder = true
        prefs.recent = listOf(stations.first().id)
        val state = StationTraversal.Snapshot(listOf(stations.first().id), 0,
            listOf(stations.last().id), stations.map { it.id })
        withService { service, _, _, _ ->
            (field(service, "stationTraversal") as StationTraversal).restore(state)
            invoke(service, "saveStationTraversal")
        }
        withService { service, player, callback, session ->
            invoke(service, "restoreStationTraversal")
            callback.onPlaybackResumption(session, controller).get()
            assertTrue(Prefs(context).randomStationOrder)
            assertEquals(state, (field(service, "stationTraversal") as StationTraversal).snapshot())
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun collectionChoicePersistsButBrowsingDoesNotChoosePlayback() {
        prefs.activeBrowseParent = "/all"
        withService { _, _, callback, session ->
            if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
                val root = callback.onGetLibraryRoot(session, controller, null).get().value!!
                val folders = callback.onGetChildren(session, controller, root.mediaId, 0, 100, null)
                    .get().value!!
                assertTrue(folders.any { it.mediaId == RadioService.NODE_PLAYLISTS })
            }
            val saved = callback.onGetChildren(session, controller, RadioService.NODE_PLAYLISTS, 0, 100, null)
                .get().value!!.single { it.mediaId == "/collection" + imported }
            callback.onGetChildren(session, controller, imported, 0, 100, null).get()
            assertEquals("/all", Prefs(context).activeBrowseParent)
            val result = callback.onSetMediaItems(session, controller, mutableListOf(
                saved), 0, 0).get()
            assertEquals(imported, Prefs(context).activeBrowseParent)
            assertEquals(repo.sourceStations("startup").map { it.mediaId },
                result.mediaItems.map { it.mediaId })
            assertEquals(0, result.startIndex)
        }
    }

    @Test fun emptyAndMissingChoicesRejectWithoutReplacingCurrentAudio() {
        prefs.activeBrowseParent = imported
        withService { _, player, callback, session ->
            val current = repo.all().first()
            player.setMediaItem(MediaItem.Builder().setMediaId(current.mediaId)
                .setUri(current.stream).build())
            for (parent in listOf(RadioService.NODE_FAVOURITES, "/playlist/deleted")) {
                val result = callback.onSetMediaItems(session, controller, mutableListOf(
                    MediaItem.Builder().setMediaId("/collection" + parent).build()), 0, 0)
                assertTrue("An empty collection must fail rather than clear playback", result.isDone)
                assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get() }
                assertEquals(current.mediaId, player.currentMediaItem!!.mediaId)
                assertEquals(imported, Prefs(context).activeBrowseParent)
            }
        }
    }

    @Test fun outsideStationAndMembershipRefreshKeepSelectedScopeAndCurrentItem() {
        val stations = repo.all()
        prefs.activeBrowseParent = RadioService.NODE_FAVOURITES
        prefs.favourites = setOf(stations.last().id)
        withService { service, player, callback, session ->
            val result = callback.onSetMediaItems(session, controller, mutableListOf(
                MediaItem.Builder().setMediaId(Station.contextualMediaId(
                    stations.first().mediaId, imported)).build()), 0, 0).get()
            assertEquals(RadioService.NODE_FAVOURITES, Prefs(context).activeBrowseParent)
            assertEquals(stations.map { it.mediaId }, result.mediaItems.map { it.mediaId })
            player.setMediaItems(result.mediaItems, result.startIndex, 1234)
            val current = player.currentMediaItem
            prefs.favourites = emptySet()
            invoke(service, "refreshAutomotiveQueue")
            assertSame(current, player.currentMediaItem)
            assertEquals(1234L, player.currentPosition)
            assertEquals(1, player.mediaItemCount)
            prefs.favourites = setOf(stations.last().id)
            invoke(service, "refreshAutomotiveQueue")
            assertSame(current, player.currentMediaItem)
            assertEquals(stations.map { it.mediaId },
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        }
    }

    @Test fun removedPlaylistPrunesNeighborsButKeepsItsCurrentAudio() {
        prefs.activeBrowseParent = imported
        val stations = repo.sourceStations("startup")
        withService { service, player, callback, session ->
            player.setMediaItems(stations.map { MediaItem.Builder().setMediaId(it.mediaId)
                .setUri(it.stream).build() }, 0, 1234)
            val current = player.currentMediaItem
            assertTrue(repo.removePlaylist("startup"))
            assertNotNull(repo.byId(stations.first().id))
            invoke(service, "refreshAutomotiveQueue")
            assertSame(current, player.currentMediaItem)
            assertEquals(1234L, player.currentPosition)
            assertEquals(1, player.mediaItemCount)
            assertFalse(player.playWhenReady)
            val resume = callback.onPlaybackResumption(session, controller).get()
            assertEquals(listOf(stations.first().mediaId), resume.mediaItems.map { it.mediaId })
            assertEquals(imported, Prefs(context).activeBrowseParent)
        }
    }

    @Test fun phoneOrderedSkipsStayInsideScopeIncludingWrapAndPrevious() {
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) return
        val stations = repo.all()
        prefs.activeBrowseParent = RadioService.NODE_FAVOURITES
        prefs.favourites = setOf(stations.last().id)
        withService { service, player, _, _ ->
            player.setMediaItems(stations.map {
                MediaItem.Builder().setMediaId(it.mediaId).setUri(it.stream).build()
            }, 0, 0)
            val wrapper = KeepCurrentStreamPlayer(player).apply {
                stationNavigation = { delta -> navigate(service, delta) }
            }
            wrapper.seekToNext()
            assertEquals(stations.last().mediaId, player.currentMediaItem!!.mediaId)
            wrapper.seekToNextMediaItem()
            assertEquals(stations.last().mediaId, player.currentMediaItem!!.mediaId)
            wrapper.seekToPrevious()
            assertEquals(stations.last().mediaId, player.currentMediaItem!!.mediaId)
        }
    }

    @Test fun wrapperSelectionKeepsBufferingStreamWhileChangingNeighbors() {
        val stations = repo.all()
        withService { _, player, _, _ ->
            player.setMediaItem(MediaItem.Builder().setMediaId(stations.first().mediaId)
                .setUri(stations.first().stream).build())
            player.prepare()
            assertEquals(androidx.media3.common.Player.STATE_BUFFERING, player.playbackState)
            val current = player.currentMediaItem
            val replacement = stations.map { MediaItem.Builder().setMediaId(it.mediaId)
                .setUri(it.stream).build() }.toMutableList()
            KeepCurrentStreamPlayer(player).setMediaItems(replacement, 0, C.TIME_UNSET)
            assertSame("Retained current MediaItem proves no source replacement", current,
                player.currentMediaItem)
            assertEquals(stations.size, player.mediaItemCount)
            if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
                assertFalse("Changing neighbors must preserve paused playback", player.playWhenReady)
        }
    }

    @Test fun shuffleSkipsUsePersistedTraversalHistoryAcrossFreshServices() {
        val stations = repo.all()
        prefs.activeBrowseParent = imported
        prefs.randomStationOrder = true
        val state = StationTraversal.Snapshot(listOf(stations.last().id), 0,
            listOf(stations.first().id), stations.map { it.id })
        withService { service, player, _, _ ->
            player.setMediaItems(stations.map { MediaItem.Builder().setMediaId(it.mediaId)
                .setUri(it.stream).build() }, 1, 0)
            (field(service, "stationTraversal") as StationTraversal).restore(state)
            val wrapper = KeepCurrentStreamPlayer(player).apply {
                stationNavigation = { delta -> navigate(service, delta) }
            }
            wrapper.seekToNext()
            assertEquals(stations.first().mediaId, player.currentMediaItem!!.mediaId)
        }
        withService { service, player, _, _ ->
            invoke(service, "restoreStationTraversal")
            player.setMediaItems(stations.map { MediaItem.Builder().setMediaId(it.mediaId)
                .setUri(it.stream).build() }, 0, 0)
            val wrapper = KeepCurrentStreamPlayer(player).apply {
                stationNavigation = { delta -> navigate(service, delta) }
            }
            wrapper.seekToPreviousMediaItem()
            assertEquals(stations.last().mediaId, player.currentMediaItem!!.mediaId)
        }
    }

    @Test fun sessionActivityNeverFallsBackToParkedSettings() {
        val service = Robolectric.buildService(RadioService::class.java).get()
        val route = RadioService::class.java.getDeclaredMethod("sessionActivityIntent")
            .apply { isAccessible = true }
        val intent = route.invoke(service) as android.content.Intent?
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
            assertNull(intent)
            val action = "android.car.intent.action.MEDIA_TEMPLATE"
            val host = android.content.pm.ActivityInfo().apply {
                packageName = "example.car.media"
                name = "example.car.media.MediaActivity"
                exported = true
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
                }
            }
            org.robolectric.Shadows.shadowOf(service.packageManager).addResolveInfoForIntent(
                android.content.Intent(action),
                android.content.pm.ResolveInfo().apply { activityInfo = host; isDefault = true }
            )
            val native = route.invoke(service) as android.content.Intent
            assertEquals(action, native.action)
            assertEquals(host.name, native.component?.className)
            assertEquals(android.content.ComponentName(service, RadioService::class.java).flattenToString(),
                native.getStringExtra("android.car.intent.extra.MEDIA_COMPONENT"))
        } else {
            assertEquals("net.mspanc.twinsenradio.phone.PhoneLibraryActivity", intent?.component?.className)
            assertEquals(context.packageName, intent?.component?.packageName)
        }
        (field(service, "scope") as CoroutineScope).cancel()
    }

    @Test fun sessionSkipsAdvertisedAtFirstLastSingletonAndOutsideCurrent() {
        val stations = repo.all()
        withService { _, player, _, session ->
            for (members in listOf(stations, listOf(stations.first()))) {
                player.setMediaItems(members.map { MediaItem.Builder().setMediaId(it.mediaId)
                    .setUri(it.stream).build() }, 0, 0)
                for (index in members.indices) {
                    player.seekToDefaultPosition(index)
                    for (command in listOf(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
                        assertTrue("Session must advertise skip $command at $index/${members.size}",
                            session.player.isCommandAvailable(command))
                    }
                }
            }
            prefs.activeBrowseParent = RadioService.NODE_FAVOURITES
            prefs.favourites = setOf(stations.last().id)
            player.setMediaItems(stations.map { MediaItem.Builder().setMediaId(it.mediaId)
                .setUri(it.stream).build() }, 0, 0)
            assertTrue(session.player.isCommandAvailable(
                androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        }
    }

    @Test fun shuffleCommandUsesExistingTrustBoundaryAndPreservesFavorites() {
        val stations = repo.all()
        prefs.favourites = setOf(stations.first().id)
        val command = androidx.media3.session.SessionCommand(
            "net.mspanc.twinsenradio.TOGGLE_SHUFFLE", Bundle.EMPTY)
        withService { _, _, callback, session ->
            val untrusted = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                "untrusted.test", 10, 20, 0, 0, false, Bundle.EMPTY, false)
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,
                callback.onCustomCommand(session, untrusted, command, Bundle.EMPTY).get().resultCode)
            assertFalse(Prefs(context).randomStationOrder)
            assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS,
                callback.onCustomCommand(session, controller, command, Bundle.EMPTY).get().resultCode)
            assertTrue(Prefs(context).randomStationOrder)
            assertEquals(setOf(stations.first().id), Prefs(context).favourites)
        }
    }

    @Test fun collectionSequenceRetainsBufferingSourceAndPausedState() {
        val station = repo.all().first()
        withService { _, player, callback, session ->
            player.setMediaItem(MediaItem.Builder().setMediaId(station.mediaId)
                .setUri(station.stream).build())
            player.prepare()
            var transitions = 0
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions++ }
            })
            val wasPlaying = session.player.playWhenReady
            val result = callback.onSetMediaItems(session, controller, mutableListOf(
                MediaItem.Builder().setMediaId("/collection" + imported).build()), 0, 0).get()
            session.player.setMediaItems(result.mediaItems.toMutableList(), result.startIndex,
                result.startPositionMs)
            session.player.prepare()
            session.player.playWhenReady = wasPlaying
            assertEquals(0, transitions)
            assertFalse(player.playWhenReady)
            assertEquals(station.mediaId, player.currentMediaItem!!.mediaId)
            assertEquals(2, player.mediaItemCount)
        }
    }

    @Test fun finiteEofKeepsPhoneStationPausedAndExplicitPlayAndSkipsStillWork() {
        prefs.activeBrowseParent = imported
        val stations = repo.all()
        withFiniteService { service, finite, wrapper, autoTransitions ->
            finite.prepare()
            wrapper.play()
            if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
                pumpUntil { autoTransitions.contains(stations.last().mediaId) }
                assertTrue("AAOS retains its existing automatic station advancement",
                    autoTransitions.contains(stations.last().mediaId))
            } else {
                pumpUntil { !finite.playWhenReady && finite.playbackState ==
                    androidx.media3.common.Player.STATE_IDLE }
                assertEquals("EOF must not choose a neighboring phone station", emptyList<String>(),
                    autoTransitions)
                assertEquals(stations.first().mediaId, finite.currentMediaItem!!.mediaId)
                assertFalse(finite.playWhenReady)
                assertEquals(androidx.media3.common.Player.STATE_IDLE, finite.playbackState)
                wrapper.play()
                assertEquals("Explicit Play must use existing reconnect to prepare a fresh source",
                    androidx.media3.common.Player.STATE_BUFFERING, finite.playbackState)
                assertEquals(stations.first().mediaId, finite.currentMediaItem!!.mediaId)
                assertTrue(finite.playWhenReady)
                wrapper.seekToNextMediaItem()
                assertEquals(stations.last().mediaId, finite.currentMediaItem!!.mediaId)
                wrapper.seekToPrevious()
                assertEquals(stations.first().mediaId, finite.currentMediaItem!!.mediaId)
            }
        }
    }

    @Test fun ordinaryPauseDoesNotClearTheCurrentConnection() {
        prefs.activeBrowseParent = imported
        withFiniteService { _, finite, wrapper, _ ->
            finite.prepare()
            wrapper.play()
            wrapper.pause()
            assertFalse(finite.playWhenReady)
            assertNotEquals("Only automatic EOF clears the connection",
                androidx.media3.common.Player.STATE_IDLE, finite.playbackState)
            assertEquals(repo.all().first().mediaId, finite.currentMediaItem!!.mediaId)
        }
    }

    private fun withFiniteService(block: (RadioService, ExoPlayer,
        KeepCurrentStreamPlayer, List<String>) -> Unit) {
        withService { service, productionPlayer, _, _ ->
            // Real Exo scheduling and finite media periods, without audio/device/network renderers.
            val finite = ExoPlayer.Builder(context).setRenderersFactory { _, _, _, _, _ ->
                arrayOf(object : androidx.media3.exoplayer.NoSampleRenderer() {
                    override fun getName() = "FiniteRadioSource"
                    override fun render(positionUs: Long, elapsedRealtimeUs: Long) { }
                })
            }.build()
            finite.repeatMode = productionPlayer.repeatMode
            finite.pauseAtEndOfMediaItems = productionPlayer.pauseAtEndOfMediaItems
            setField(service, "player", finite)
            val events = RadioService::class.java.declaredClasses.single {
                it.simpleName == "PlayerEvents"
            }.getDeclaredConstructor(RadioService::class.java).apply { isAccessible = true }
                .newInstance(service) as androidx.media3.common.Player.Listener
            val autoTransitions = mutableListOf<String>()
            finite.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    events.onPlayWhenReadyChanged(ready, reason)
                }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        item?.mediaId?.let(autoTransitions::add)
                }
            })
            val reconnect = ReconnectController(context, finite) { }
            reconnect.start()
            val wrapper = KeepCurrentStreamPlayer(finite).apply {
                stationNavigation = { delta -> navigate(service, delta) }
            }
            finite.setMediaSources(repo.all().map { station ->
                androidx.media3.exoplayer.source.SilenceMediaSource(20_000).apply {
                    updateMediaItem(MediaItem.Builder().setMediaId(station.mediaId).setUri("").build())
                }
            })
            try { block(service, finite, wrapper, autoTransitions) } finally {
                reconnect.stop()
                finite.release()
                setField(service, "player", productionPlayer)
            }
        }
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    private fun navigate(service: RadioService, delta: Int): Boolean =
        RadioService::class.java.getDeclaredMethod("navigateStationOrder", Int::class.java)
            .apply { isAccessible = true }.invoke(service, delta) as Boolean

    private fun assertResume(parent: String, selected: Station, members: List<Station>) {
        withService { _, player, callback, session ->
            val result = callback.onPlaybackResumption(session, controller).get()
            val expectedQueue = members
            assertEquals(expectedQueue.map { it.mediaId }, result.mediaItems.map { it.mediaId })
            assertEquals(selected.mediaId, result.mediaItems[result.startIndex].mediaId)
            assertEquals(C.TIME_UNSET, result.startPositionMs)
            assertEquals(parent, Prefs(context).activeBrowseParent)
            assertFalse(player.playWhenReady)
            assertEquals(0, player.mediaItemCount)
        }
    }

    private fun newRepository(): StationRepository =
        StationRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private fun withService(block: (RadioService, ExoPlayer,
        MediaLibrarySession.Callback, MediaLibrarySession) -> Unit) {
        // Attach the real callback without onCreate: no network, session lifecycle, or audio.
        val service = Robolectric.buildService(RadioService::class.java).get()
        setField(service, "prefs", Prefs(context))
        val player = RadioService::class.java.getDeclaredMethod("buildPlayer")
            .apply { isAccessible = true }.invoke(service) as ExoPlayer
        setField(service, "repo", repo)
        setField(service, "metadata", MetadataFactory(context, Prefs(context)))
        setField(service, "player", player)
        val callback = RadioService::class.java.declaredClasses.single { it.simpleName == "LibraryCallback" }
            .getDeclaredConstructor(RadioService::class.java).apply { isAccessible = true }
            .newInstance(service) as MediaLibrarySession.Callback
        val sessionPlayer = KeepCurrentStreamPlayer(player).apply {
            stationNavigation = { delta -> navigate(service, delta) }
        }
        val session = MediaLibrarySession.Builder(context, sessionPlayer, callback).build()
        setField(service, "session", session)
        try { block(service, player, callback, session) } finally {
            session.release()
            (field(service, "scope") as CoroutineScope).cancel()
            player.release()
        }
    }

    private fun field(service: RadioService, name: String): Any? =
        RadioService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun setField(service: RadioService, name: String, value: Any?) {
        RadioService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
    private fun invoke(service: RadioService, name: String) {
        RadioService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }
}
