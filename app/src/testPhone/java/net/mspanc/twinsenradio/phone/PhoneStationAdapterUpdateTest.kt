package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.MetadataFactory
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
    @Test fun visibleFallbackStaysUntilTheRowReturnsToView() =
        withLogoRow("steady", Color.CYAN) { adapter, holder, restore ->
            adapter.onBindViewHolder(holder, 0)
            val initialFallback = holder.logo.drawable
            assertTrue(initialFallback is PhoneStationArtwork)

            restore()
            var payload: Any? = null
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(start: Int, count: Int, value: Any?) {
                    if (start == 0) payload = value
                }
            })
            PlaybackStatusBus.setStation("steady")
            adapter.update(listOf(station("steady", "https://logos.example/steady.png")))
            adapter.onBindViewHolder(holder, 0, mutableListOf(requireNotNull(payload)))
            assertTrue(holder.logo.drawable === initialFallback)

            val returningHolder = org.mockito.Mockito.spy(holder)
            org.mockito.Mockito.doReturn(0).`when`(returningHolder).bindingAdapterPosition
            adapter.onViewAttachedToWindow(returningHolder)
            assertEquals(Color.CYAN, (holder.logo.drawable as BitmapDrawable).bitmap.getPixel(16, 16))
        }

    @Test fun coldRestartPersistentLogoIsReadyInTheBoundStationRow() =
        withLogoRow("cold", Color.MAGENTA) { adapter, holder, restore ->
            restore()
            adapter.onBindViewHolder(holder, 0)
            assertEquals(Color.MAGENTA, (holder.logo.drawable as BitmapDrawable).bitmap.getPixel(16, 16))
        }

    private fun withLogoRow(
        id: String,
        color: Int,
        checkRow: (PhoneStationAdapter, PhoneStationAdapter.Holder, restore: () -> Unit) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE).edit().clear()
            .putString("sources", "[]").commit()
        StationRepository::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)
        PlaybackStatusBus.setStation(null)
        PlaybackStatusBus.setNowPlaying(null)
        val prefs = Prefs(context).apply { onlineArtworkEnabled = true }
        val station = station(id, "https://logos.example/$id.png")
        val uri = requireNotNull(MetadataFactory(context, prefs).logoDisplayUri(station))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val cache = PhoneStationArtworkCache(context, host.get().lifecycleScope)
        val artworkFile = cache.cacheFileForTest(uri)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        artworkFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        try {
            val adapter = PhoneStationAdapter(host.get(), prefs, StationRepository.get(context),
                artworkCache = cache) { }
            adapter.update(listOf(station))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            checkRow(adapter, holder) { runBlocking { cache.restore(uri) } }
        } finally {
            artworkFile.delete()
            PlaybackStatusBus.setStation(null)
            host.pause().stop().destroy()
        }
    }

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
            changes.clear()

            PlaybackStatusBus.setStation("charlie")
            adapter.update(rows)

            assertFalse("Station changes must keep the existing list", invalidatedAll)
            assertEquals("Refresh both the former and current station", listOf(1, 2), changes.map { it.position })
        } finally {
            artworkLoading.value = previousArtworkLoading
            PlaybackStatusBus.setStation(null)
            PlaybackStatusBus.setNowPlaying(null)
            host.pause().stop().destroy()
        }
    }

    private fun station(id: String, logoUrl: String? = null) = Station(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        genre = "Radio",
        stream = "https://radio.example/$id",
        logoUrl = logoUrl
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
