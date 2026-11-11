package net.mspanc.twinsenradio.playback

import android.content.Context
import java.io.File
import java.util.UUID
import net.mspanc.twinsenradio.data.Station
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StationArtworkPreloadPruneTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun previewSubsetDoesNotPruneUnrelatedStationArtwork() {
        val directory = File(context.filesDir, "station-art").apply { mkdirs() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val unrelated = File(directory, "$id.jpg")
        val unrelatedSample = File(directory, "$id-sample.jpg")
        unrelated.writeText("retained artwork")
        unrelatedSample.writeText("retained sample")
        val previewStation = Station("preview-only", "Preview", "", "https://radio.invalid/live")

        try {
            StationArtworkStore.pruneUnusedArtworkFiles(
                context, listOf(previewStation), pruneUnused = false
            )

            assertTrue("unrelated artwork stays cached", unrelated.exists())
            assertTrue("unrelated samples stay cached", unrelatedSample.exists())
        } finally {
            unrelated.delete()
            unrelatedSample.delete()
        }
    }
}
