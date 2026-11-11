package net.mspanc.twinsenradio.playback

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StationArtworkPreviewAttemptTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun failedPreviewLeavesServiceArtworkAttemptAvailable() = runBlocking {
        val station = Station(
            UUID.randomUUID().toString(), "X", "", "https://radio.invalid/live"
        )
        val id = M3uParser.stableKey(station.id)
        val checked = context.getSharedPreferences(
            "station_artwork_checks", Context.MODE_PRIVATE
        )
        Prefs(context).onlineArtworkEnabled = true
        StationArtworkStore.beginServiceLaunch()

        // A one-character name skips directory search; with no logo URL,
        // preload completes normally with no saved artwork and no network.
        StationArtworkStore.preloadPreview(context, listOf(station)) {}
        assertTrue(checked.contains(id))

        checked.edit().remove(id).commit()
        StationArtworkStore.preload(context, listOf(station)) {}
        assertTrue("service gets its own failed lookup", checked.contains(id))

        checked.edit().remove(id).commit()
        StationArtworkStore.preload(context, listOf(station)) {}
        assertFalse("service still attempts each station once per launch", checked.contains(id))
    }
}
