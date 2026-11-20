package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.NowPlaying
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class PhonePlaylistUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository

    @Before
    fun resetState() {
        context.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit().clear()
            .putString("sources", "[]").commit()
        context.getSharedPreferences("phone_player", Context.MODE_PRIVATE).edit().clear().commit()
        StationRepository::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)
        prefs = Prefs(context).apply {
            onlineArtworkEnabled = false
            favourites = emptySet()
            recent = emptyList()
        }
        repo = StationRepository.get(context)
        PlaybackStatusBus.setStation(null)
        PlaybackStatusBus.setNowPlaying(null)
    }

    @Test
    fun playlistAdapterShowsStationUrlEvenWhilePlayingAndLimitsToTwoLines() {
        val station = Station(
            id = "kexp",
            name = "KEXP 90.3 FM",
            genre = "Playlist",
            stream = "https://kexp.streamguys1.com/kexp160.aac",
            logoUrl = "https://logos.example/kexp.png",
            source = Station.Source.USER_M3U
        )
        PlaybackStatusBus.setStation(station.id)
        PlaybackStatusBus.setNowPlaying(NowPlaying("Time (You and I)", "Khruangbin", "Time (You and I)"))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val adapter = PhoneStationAdapter(host.get(), prefs, repo, showStreamUrl = true) { }
            adapter.update(listOf(station))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)

            assertEquals("KEXP 90.3 FM", holder.title.text.toString())
            assertEquals("https://kexp.streamguys1.com/kexp160.aac", holder.subtitle.text.toString())
            assertEquals(2, holder.subtitle.maxLines)
            assertEquals(View.VISIBLE, holder.logo.visibility)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test
    fun playlistDetailScreenRendersStationStreamUrlInsteadOfPlaylistGenre() {
        runBlocking {
            repo.saveLocalPlaylist(
                "World Independent Radio",
                "#EXTM3U\n#EXTINF:-1 tvg-logo=\"https://logos.example/kexp.png\",KEXP 90.3 FM\nhttps://kexp.streamguys1.com/kexp160.aac\n"
            )
        }
        val sourceId = repo.playlists().single().id
        val intent = Intent(context, PhonePlaylistActivity::class.java).putExtra("source_id", sourceId)
        val controller = Robolectric.buildActivity(PhonePlaylistActivity::class.java, intent)
        controller.get().setTheme(R.style.Theme_StreamingRadioPhone)
        try {
            val activity = controller.create().get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 1920)
            val list = descendants(root).filterIsInstance<RecyclerView>().single()
            val texts = descendants(list).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Expected station name in playlist view", "KEXP 90.3 FM" in texts)
            assertTrue(
                "Expected station stream URL in playlist view",
                "https://kexp.streamguys1.com/kexp160.aac" in texts
            )
            assertTrue("Must not show 'Playlist' as station subtitle", "Playlist" !in texts)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun playlistsCatalogShowsRefreshButtonOnlyForHostedPlaylists() {
        val hostedId = "pl_hosted_1"
        val localId = "pl_local_1"
        val sources = JSONArray()
            .put(
                JSONObject()
                    .put("id", hostedId)
                    .put("url", "https://radio.example.org/indie.m3u")
                    .put("name", "World Independent Radio")
                    .put("stations", JSONArray())
            )
            .put(
                JSONObject()
                    .put("id", localId)
                    .put("url", "local:imported:12345678-1234-1234-1234-1234567890ab")
                    .put("name", "Road Trip Backup")
                    .put("stations", JSONArray())
            )
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit()
            .putString("sources", sources.toString()).commit()
        StationRepository::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)

        val controller = Robolectric.buildActivity(PhonePlaylistCatalogActivity::class.java)
        controller.get().setTheme(R.style.Theme_TwinsenRadio)
        try {
            val activity = controller.setup().get()
            val root = activity.findViewById<View>(android.R.id.content)

            val hostedRefresh = root.findViewWithTag<ImageButton>("phone_playlist_refresh_$hostedId")
            val localRefresh = root.findViewWithTag<View>("phone_playlist_refresh_$localId")
            assertNotNull("Hosted playlist row must show a refresh button on the right", hostedRefresh)
            assertNull("Local import row must not show a refresh button", localRefresh)

            val hostedTitle = descendants(root).filterIsInstance<TextView>()
                .single { it.text.toString() == "World Independent Radio" }
            (hostedTitle.parent as View).performClick()
            val opened = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(PhonePlaylistActivity::class.java.name, opened.component?.className)
            assertEquals(hostedId, opened.getStringExtra("source_id"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
        }
    }
}
