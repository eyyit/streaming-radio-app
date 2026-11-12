package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.MutableStateFlow
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.NowPlaying
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.StationArtworkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@UnstableApi
class PhoneStationAdapterUpdateTest {
    @Test fun metadataAndLogoLoadingUpdatesOnlyNotifyAffectedStationRows() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit().clear()
            .putString("sources", "[]").commit()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        StationRepository::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)
        val prefs = Prefs(context).apply {
            onlineArtworkEnabled = false
            favourites = emptySet()
        }
        val repo = StationRepository.get(context)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val artworkLoading = StationArtworkStore::class.java.getDeclaredField("_loading")
            .apply { isAccessible = true }.get(StationArtworkStore) as MutableStateFlow<Set<String>>
        val previousArtworkLoading = artworkLoading.value
        val rows = listOf(station("alpha"), station("nova"), station("charlie"))
        val changes = mutableListOf<Change>()
        var invalidatedAll = false
        val adapter = PhoneStationAdapter(host.get(), prefs, repo) { }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { invalidatedAll = true }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                repeat(itemCount) { changes += Change(positionStart + it, null) }
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                repeat(itemCount) { changes += Change(positionStart + it, payload) }
            }
        })
        try {
            artworkLoading.value = emptySet()
            PlaybackStatusBus.setStation(null)
            PlaybackStatusBus.setNowPlaying(null)
            adapter.update(rows)
            changes.clear()
            invalidatedAll = false

            PlaybackStatusBus.setStation("nova")
            PlaybackStatusBus.setNowPlaying(NowPlaying("Track", "Artist", "Track"))
            adapter.update(rows)

            assertFalse("Playback metadata must not invalidate the full playlist", invalidatedAll)
            assertEquals("Only the active station row should update", listOf(1), changes.map { it.position })
            assertTrue("State-only updates should carry a reusable payload", changes.single().payload != null)
            assertPayloadReusesHolder(context, changes.single().payload)
            changes.clear()

            artworkLoading.value = setOf("nova")
            adapter.update(rows)

            assertFalse("Logo loading must not invalidate the full playlist", invalidatedAll)
            assertEquals("Loading Nova artwork should update only its row", listOf(1), changes.map { it.position })
            assertTrue("Logo updates should carry a reusable payload", changes.single().payload != null)
            assertPayloadReusesHolder(context, changes.single().payload)
            changes.clear()

            artworkLoading.value = emptySet()
            adapter.update(rows)

            assertEquals("Finishing Nova artwork should update only its row", listOf(1), changes.map { it.position })
            assertTrue(changes.single().payload != null)
            assertPayloadReusesHolder(context, changes.single().payload)
        } finally {
            artworkLoading.value = previousArtworkLoading
            PlaybackStatusBus.setStation(null)
            PlaybackStatusBus.setNowPlaying(null)
            host.pause().stop().destroy()
        }
    }

    private fun station(id: String) = Station(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        genre = "Radio",
        stream = "https://radio.example/$id"
    )

    private data class Change(val position: Int, val payload: Any?)

    private fun assertPayloadReusesHolder(context: Context, payload: Any?) {
        val holder = object : RecyclerView.ViewHolder(View(context)) { }
        assertTrue(
            "RecyclerView should reuse holders for adapter state payloads",
            DefaultItemAnimator().canReuseUpdatedViewHolder(holder, listOf(requireNotNull(payload)))
        )
    }
}
