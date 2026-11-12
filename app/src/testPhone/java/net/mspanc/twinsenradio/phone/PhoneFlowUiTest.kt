package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.RadioService
import net.mspanc.twinsenradio.playback.StreamQuality
import net.mspanc.twinsenradio.ui.SettingsActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.security.MessageDigest

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class PhoneFlowUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository

    @Before fun offlineEmptyState() {
        context.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit().clear().putString("sources", "[]").commit()
        context.getSharedPreferences("phone_player", Context.MODE_PRIVATE).edit().clear().commit()
        StationRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        prefs = Prefs(context).apply { onlineArtworkEnabled = false; favourites = emptySet(); recent = emptyList() }
        repo = StationRepository.get(context)
        PlaybackStatusBus.setStation(null); PlaybackStatusBus.setNowPlaying(null)
        PlaybackStatusBus.setWantsToPlay(false); PlaybackStatusBus.setLostAir(false)
        PlaybackStatusBus.setTrackArtworkLoading(false); PlaybackStatusBus.setCoverArt(null)
    }

    @Test fun phonePlaybackStatusUsesCurrentStreamEvidence() {
        val startupBuffer = phonePlaybackStatusLabel(
            PlaybackStatusBus.Status.BUFFERING, hasCurrentStreamFormat = false
        )
        val delayedConnecting = phonePlaybackStatusLabel(
            PlaybackStatusBus.Status.CONNECTING, hasCurrentStreamFormat = false
        )
        val streamRefill = phonePlaybackStatusLabel(
            PlaybackStatusBus.Status.BUFFERING, hasCurrentStreamFormat = true
        )

        assertEquals("Connecting…", startupBuffer)
        assertEquals("Connecting…", delayedConnecting)
        assertEquals("Buffering…", streamRefill)
        assertEquals(
            "Reconnecting…",
            phonePlaybackStatusLabel(
                PlaybackStatusBus.Status.RECONNECTING, hasCurrentStreamFormat = false
            )
        )

        PlaybackStatusBus.setStation("phone-status-a")
        PlaybackStatusBus.setQuality(StreamQuality("MP3", 128, 44100, 2))
        PlaybackStatusBus.setStation("phone-status-b")
        assertNull("A prior station's decoder format must not end the new station's connecting state",
            PlaybackStatusBus.quality.value)
        PlaybackStatusBus.setStation(null)
    }

    @Test fun libraryStatusFloatsAboveTheDockWithoutMovingTheList() {
        fun layoutAt(state: PlaybackStatusBus.Status, label: String): List<Rect> {
            PlaybackStatusBus.setPlayerStatus(state)
            var layout = emptyList<Rect>()
            withActivity(PhoneLibraryActivity::class.java) { _, root ->
                measureCompact(root)
                val status = root.findViewWithTag<TextView>("phone_playback_status")
                val dock = root.findViewWithTag<View>("phone_secondary_actions")
                val list = descendants(root).filterIsInstance<RecyclerView>().single()
                assertEquals(label, status.text.toString())
                assertEquals(if (label.isEmpty()) View.INVISIBLE else View.VISIBLE, status.visibility)
                assertTrue(absoluteTop(status) + status.height < absoluteTop(dock))
                layout = listOf(list, dock).map { view ->
                    Rect(view.left, absoluteTop(view), view.right, absoluteTop(view) + view.height)
                }
            }
            return layout
        }
        PlaybackStatusBus.setNetworkStatus(null)
        try {
            val idle = layoutAt(PlaybackStatusBus.Status.IDLE, "")
            assertEquals(idle, layoutAt(PlaybackStatusBus.Status.CONNECTING, "Connecting…"))
            PlaybackStatusBus.setQuality(StreamQuality("MP3", 128, 44100, 2))
            assertEquals(idle, layoutAt(PlaybackStatusBus.Status.BUFFERING, "Buffering…"))
        } finally {
            PlaybackStatusBus.setPlayerStatus(PlaybackStatusBus.Status.IDLE)
            PlaybackStatusBus.setQuality(null)
        }
    }

    @Test fun phoneSettingsOpensTheBundledPrivacyPolicy() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java)
        host.get().setTheme(R.style.Theme_TwinsenRadio)
        try {
            val activity = host.setup().get()
            val content = activity.findViewById<View>(android.R.id.content)
            val privacyButton = descendants(content).filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == "Privacy policy" }
            assertNotNull("Phone Settings must expose its privacy policy", privacyButton)
            assertTrue(privacyButton!!.performClick())
            val dialog = ShadowDialog.getLatestDialog()
            val shown = dialog.findViewById<TextView>(android.R.id.message).text.toString()
            val bundled = activity.assets.open("legal/privacy-policy.txt")
                .bufferedReader().use { it.readText() }
            assertEquals(bundled, shown)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun packagedSelectedAssetPixelsMatchTheFrozenReference() {
        val reference = javaClass.getResourceAsStream("/phone_shuffle_A_reference.png")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(reference).joinToString("") { "%02x".format(it) }
        assertEquals("The unprocessed reference must remain the exact accepted A PNG",
            "b431a22813b224e68bc07b5362f79137fa7c063319a0c406a1cfc192e8a8d032", hash)
        val expected = BitmapFactory.decodeByteArray(reference, 0, reference.size)
        val packaged = BitmapFactory.decodeResource(context.resources, R.drawable.phone_shuffle_on)
        assertEquals(expected.width, packaged.width); assertEquals(expected.height, packaged.height)
        val originalPixels = IntArray(expected.width * expected.height)
        val packagedPixels = IntArray(packaged.width * packaged.height)
        expected.getPixels(originalPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        packaged.getPixels(packagedPixels, 0, packaged.width, 0, 0, packaged.width, packaged.height)
        assertArrayEquals("Packaging must preserve every ARGB pixel, including the dot and transparent canvas",
            originalPixels, packagedPixels)
    }

    @Test fun exactSelectedAssetHashAndOffCanvasArePreserved() {
        val bytes = javaClass.getResourceAsStream("/phone_shuffle_A_reference.png")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("The owner-selected A asset must never be redrawn", "b431a22813b224e68bc07b5362f79137fa7c063319a0c406a1cfc192e8a8d032", hash)
        val on = BitmapFactory.decodeResource(context.resources, R.drawable.phone_shuffle_on)
        val off = BitmapFactory.decodeResource(context.resources, R.drawable.phone_shuffle_off)
        assertEquals(on.width, off.width); assertEquals(on.height, off.height)
        var selectedDotPixels = 0
        for (y in 0 until on.height) for (x in 0 until on.width) {
            if (y < 701) assertEquals("Off state must not move or redraw the glyph", on.getPixel(x, y), off.getPixel(x, y))
            else {
                assertEquals(0, android.graphics.Color.alpha(off.getPixel(x, y)))
                if (android.graphics.Color.alpha(on.getPixel(x, y)) > 0) selectedDotPixels++
            }
        }
        assertTrue(selectedDotPixels > 0)
    }

    @Test fun browsingFavoritesKeepsPlaybackScopeAndPlaylistsOpenFromSettings() {
        runBlocking { repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,Alpha\nhttps://radio.example/a\n#EXTINF:-1,Beta\nhttps://radio.example/b\n") }
        val chosen = "/playlist/${repo.playlists().single().id}"
        prefs.activeBrowseParent = chosen
        val current = repo.all().first()
        PlaybackStatusBus.setStation(current.id)
        withActivity(PhoneLibraryActivity::class.java) { activity, root ->
            descendants(root).filterIsInstance<Button>().single { it.text == "Favorites" }.performClick()
            assertEquals(chosen, Prefs(context).activeBrowseParent)
            assertEquals(current.id, PlaybackStatusBus.stationId.value)
            assertEquals(0, descendants(root).filterIsInstance<RecyclerView>().single().adapter!!.itemCount)
            assertFalse(descendants(root).filterIsInstance<Button>().any { it.text == "Playlists" })
            assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text == "Playing from: Test list" })
            descendants(root).first { it.contentDescription == "Choose playback collection" }.performClick()
            val chooser = root.findViewWithTag<View>("phone_collection_chooser")
            assertEquals(listOf("All stations", "Favorites"), descendants(chooser)
                .mapNotNull { it.contentDescription?.toString()?.substringBefore(", ") }
                .filter { it.isNotBlank() })
            assertFalse(activity.isFinishing)
        }
        withActivity(PhoneSettingsActivity::class.java) { activity, root ->
            val playlists = descendants(root).filterIsInstance<TextView>().single { it.text == "Playlists" }
            (playlists.parent as View).performClick()
            val destination = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(PhonePlaylistCatalogActivity::class.java.name, destination.component?.className)
            assertEquals(chosen, Prefs(context).activeBrowseParent)
            assertEquals(current.id, PlaybackStatusBus.stationId.value)
        }
    }

    @Test fun emptyCollectionSelectionKeepsCurrentStationAndScope() {
        runBlocking { repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,Alpha\nhttps://radio.example/a\n") }
        prefs.activeBrowseParent = RadioService.NODE_ALL
        val current = repo.all().single()
        PlaybackStatusBus.setStation(current.id)
        withActivity(PhoneLibraryActivity::class.java) { _, root ->
            descendants(root).first { it.contentDescription == "Choose playback collection" }.performClick()
            val favoriteChoice = descendants(root).first { it.contentDescription?.toString()?.startsWith("Favorites, 0 stations") == true }
            favoriteChoice.performClick()
            assertEquals(RadioService.NODE_ALL, Prefs(context).activeBrowseParent)
            assertEquals(current.id, PlaybackStatusBus.stationId.value)
            assertEquals(View.VISIBLE, root.findViewWithTag<View>("phone_collection_chooser").visibility)
        }
    }

    @Test fun libraryAndImportExposeSameSingleAssetDock() {
        listOf(PhoneLibraryActivity::class.java, PhonePlayerActivity::class.java, PhoneImportActivity::class.java,
            PhonePlaylistActivity::class.java).forEach { type ->
            withActivity(type) { _, root ->
                assertEquals(1, descendants(root).count { it.tag == "phone_locked_a_shuffle" })
                assertEquals(1, descendants(root).count { it.tag == "phone_secondary_actions" })
                assertEquals(1, descendants(root).count { it.tag == "phone_primary_transport" })
                val image = root.findViewWithTag<ImageView>("phone_locked_a_shuffle")
                assertNull("Bitmap asset must not be theme-tinted", image.imageTintList)
            }
        }
    }

    @Test fun transportRemainsBelowActionsAtCompactPhoneSize() {
        listOf(PhoneLibraryActivity::class.java, PhonePlayerActivity::class.java, PhoneImportActivity::class.java).forEach { type ->
            withActivity(type) { _, root ->
                val density = context.resources.displayMetrics.density
                val width = (320 * density).toInt(); val height = (568 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val secondary = root.findViewWithTag<View>("phone_secondary_actions")
                val transport = root.findViewWithTag<View>("phone_primary_transport")
                assertTrue(absoluteTop(secondary) + secondary.height <= absoluteTop(transport))
                assertTrue("Transport must fit ${type.simpleName}", absoluteTop(transport) + transport.height <= height)
            }
        }
    }

    @Test fun largeTextLibraryTabsCanBeReachedAboveDock() {
        RuntimeEnvironment.setFontScale(1.8f)
        try {
            withActivity(PhoneLibraryActivity::class.java) { activity, root ->
                assertEquals(1.8f, activity.resources.configuration.fontScale, .01f)
                measureCompact(root)
                val tab = descendants(root).filterIsInstance<Button>().single { it.text == "All stations" }
                tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), true)
                val dock = root.findViewWithTag<View>("phone_secondary_actions")
                assertTrue("Large-text browse controls must remain reachable without overlapping the dock",
                    absoluteTop(tab) + tab.height <= absoluteTop(dock))
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun largeTextImportHeadingIsNotClipped() {
        RuntimeEnvironment.setFontScale(1.8f)
        try {
            withActivity(PhoneImportActivity::class.java) { activity, root ->
                assertEquals(1.8f, activity.resources.configuration.fontScale, .01f)
                measureCompact(root)
                val heading = descendants(root).filterIsInstance<TextView>().single { it !is Button && it.text == "Add playlist" }
                val layout = heading.layout
                assertNotNull(layout)
                assertTrue("Import heading must grow for system text size",
                    layout.getLineBottom(layout.lineCount - 1) + heading.compoundPaddingBottom <= heading.height)
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun compactLibraryKeepsAStationRowVisibleAtNormalAndLargeText() {
        runBlocking { repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,A station with a descriptive name\nhttps://radio.example/a\n") }
        PlaybackStatusBus.setStation(repo.all().single().id)
        for (scale in listOf(1f, 1.8f)) {
            RuntimeEnvironment.setFontScale(scale)
            try {
                withActivity(PhoneLibraryActivity::class.java) { activity, root ->
                    assertEquals(scale, activity.resources.configuration.fontScale, .01f)
                    measureCompact(root)
                    val list = descendants(root).filterIsInstance<RecyclerView>().single()
                    assertTrue("A station row must remain visible at font scale $scale; list height=${list.height}",
                        list.height >= (72 * activity.resources.displayMetrics.density).toInt())
                }
            } finally { RuntimeEnvironment.setFontScale(1f) }
        }
    }

    @Test fun phoneBrandAndArtworkSlotStayStableWhenAlbumArtIsHidden() {
        assertEquals("Streaming Radio", context.getString(R.string.app_name))
        context.getSharedPreferences("phone_player", Context.MODE_PRIVATE).edit().putBoolean("hide_art", true).commit()
        withActivity(PhonePlayerActivity::class.java) { _, root ->
            val art = descendants(root).filterIsInstance<ImageView>().single {
                it.contentDescription == "Current track artwork or station image"
            }
            assertEquals(View.VISIBLE, art.visibility)
            art.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals("The artwork frame must be square on wide phones", art.measuredWidth, art.measuredHeight)
        }
    }

    @Test fun playerMetadataHasBoundedLinesWithoutShrinkingText() {
        withActivity(PhonePlayerActivity::class.java) { _, root ->
            val texts = descendants(root).filterIsInstance<TextView>()
            val title = texts.single { it.text == "Add a playlist to start" }
            assertEquals(2, title.maxLines)
            assertEquals(android.text.TextUtils.TruncateAt.END, title.ellipsize)
            val artist = texts.single { kotlin.math.abs(it.textSize / context.resources.displayMetrics.scaledDensity - 22f) < .1f }
            assertEquals(1, artist.maxLines)
            assertEquals(android.text.TextUtils.TruncateAt.END, artist.ellipsize)
        }
    }

    @Test fun expandedCoverSizeStaysStableWhenMetadataChangesLineCount() {
        withActivity(PhonePlayerActivity::class.java) { activity, root ->
            val width = View.MeasureSpec.makeMeasureSpec(PhoneUi.dp(activity, 360), View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(PhoneUi.dp(activity, 560), View.MeasureSpec.EXACTLY)
            val art = descendants(root).filterIsInstance<ImageView>().single {
                it.contentDescription == "Current track artwork or station image"
            }
            val title = descendants(root).filterIsInstance<TextView>().single { it.text == "Add a playlist to start" }
            title.text = "Short title"
            root.measure(width, height)
            val short = art.measuredWidth
            title.text = "A title long enough to wrap onto the second line on this narrow phone"
            root.measure(width, height)
            assertTrue(short > 0)
            assertEquals(short, art.measuredWidth)
            assertEquals(short, art.measuredHeight)
        }
    }

    @Test fun joiningStationKeepsItsArtworkFallbackVisible() {
        runBlocking {
            repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,Night Signal\nhttps://radio.example/a\n")
        }
        PlaybackStatusBus.setStation(repo.all().single().id)
        val host = Robolectric.buildActivity(PhonePlayerActivity::class.java)
        host.get().setTheme(R.style.Theme_StreamingRadioPhone)
        try {
            val activity = host.create().get()
            (activity.lifecycle as androidx.lifecycle.LifecycleRegistry).currentState = Lifecycle.State.STARTED
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val art = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<ImageView>()
                .single { it.contentDescription == "Current track artwork or station image" }
            assertTrue(PlaybackStatusBus.artPending())
            assertEquals("station-initials:NS", art.drawable.toString())
        } finally {
            (host.get().lifecycle as androidx.lifecycle.LifecycleRegistry).currentState = Lifecycle.State.CREATED
            host.destroy()
            PlaybackStatusBus.setJoin(PlaybackStatusBus.Join.NONE)
        }
    }

    @Test fun coverWheelHoldsStationDetailsUntilTheFaceSettles() {
        val cover = PhoneCarouselArtwork(context)
        val title = TextView(context).apply { text = "Previous station" }
        val artist = TextView(context).apply { visibility = View.GONE }
        cover.attachDetails(title, artist)
        cover.setPanelMoving(true)
        cover.displayDetails { title.text = "Intermediate update" }
        cover.displayDetails { title.text = "Incoming station" }
        assertEquals(View.INVISIBLE, title.visibility)
        assertEquals("Previous station", title.text)
        assertEquals(View.GONE, artist.visibility)
        cover.setPanelMoving(false)
        assertEquals(View.VISIBLE, title.visibility)
        assertEquals("Incoming station", title.text)
        assertEquals(View.GONE, artist.visibility)
    }

    @Test fun tappingPlayerTitleCentersTheLibraryWithAPlaylistPlaying() {
        runBlocking {
            repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,Night Signal\nhttps://radio.example/a\n")
        }
        val source = repo.playlists().single()
        prefs.activeBrowseParent = "/playlist/${source.id}"
        PlaybackStatusBus.setStation(repo.sourceStations(source.id).single().id)
        withActivity(PhonePlayerActivity::class.java) { activity, root ->
            val title = descendants(root).filterIsInstance<TextView>().single { it.text == "Night Signal" }
            assertTrue(title.performClick())
            val destination = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(PhoneLibraryActivity::class.java.name, destination.component?.className)
            assertTrue(destination.getBooleanExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION, false))
            assertEquals("/playlist/${source.id}", Prefs(context).activeBrowseParent)
        }
    }

    @Test fun playerLibraryButtonReturnsToLibraryWithAPlaylistPlaying() {
        prefs.activeBrowseParent = "/playlist/saved-source"
        withActivity(PhonePlayerActivity::class.java) { activity, root ->
            descendants(root).single { it.contentDescription == "Restore station library" }.performClick()
            val destination = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(PhoneLibraryActivity::class.java.name, destination.component?.className)
            assertFalse(destination.getBooleanExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION, false))
        }
    }

    @Test fun longTrackingMetadataKeepsStationAndFormatAboveDock() {
        runBlocking { repo.saveLocalPlaylist("Test list", "#EXTM3U\n#EXTINF:-1,Night Signal\nhttps://radio.example/a\n") }
        PlaybackStatusBus.setStation(repo.all().single().id)
        PlaybackStatusBus.setJoin(PlaybackStatusBus.Join.NONE)
        val title = "Commercial Break - https://ads.example/banner/" + "tracking-field/".repeat(25)
        PlaybackStatusBus.setNowPlaying(net.mspanc.twinsenradio.playback.NowPlaying(
            title, "Artist " + "long name ".repeat(25), title))
        PlaybackStatusBus.setQuality(net.mspanc.twinsenradio.playback.StreamQuality("AAC", 96, 48000, 2))
        withActivity(PhonePlayerActivity::class.java) { _, root ->
            val density = context.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((520 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val dock = root.findViewWithTag<View>("phone_secondary_actions")
            val text = descendants(root).filterIsInstance<TextView>()
            val station = text.single { it.text == "Station: Night Signal" }
            val format = text.single { it.text.contains("48.0 kHz") }
            assertTrue(absoluteTop(station) + station.height <= absoluteTop(dock))
            assertTrue("Long URL metadata cannot push stream details behind the dock",
                absoluteTop(format) + format.height <= absoluteTop(dock))
        }
    }

    private fun measureCompact(root: View) {
        val density = context.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((320*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((568*density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun absoluteTop(view: View): Int = view.top + ((view.parent as? View)?.let { absoluteTop(it) - it.scrollY } ?: 0)
    private fun <T : androidx.appcompat.app.AppCompatActivity> withActivity(type: Class<T>, check: (T, View) -> Unit) {
        val host = Robolectric.buildActivity(type)
        host.get().setTheme(R.style.Theme_StreamingRadioPhone)
        try {
            host.create()
            check(host.get(), host.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
        } finally {
            if (host.get().lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) host.destroy()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
