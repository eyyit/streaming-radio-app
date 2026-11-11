package net.mspanc.twinsenradio.phone

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
import net.mspanc.twinsenradio.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneCastHandoffLeaseTest {
    @Test
    fun confirmPausesMatchingPhoneItemAndReportsPlayIntent() {
        val player = Mockito.mock(MediaController::class.java)
        var playing = true
        Mockito.`when`(player.currentMediaItem).thenReturn(mediaItem("one", STREAM))
        Mockito.`when`(player.playWhenReady).thenAnswer { playing }
        doAnswer { playing = false; null }.`when`(player).pause()
        val handoffs = mutableListOf<Boolean>()

        lease(player).confirm(STREAM, "one", handoffs::add)

        assertEquals(listOf(true), handoffs)
        assertFalse(playing)
        Mockito.verify(player).pause()
    }

    @Test
    fun confirmDoesNotPauseWhenStationOrUriDoesNotMatch() {
        val player = Mockito.mock(MediaController::class.java)
        Mockito.`when`(player.currentMediaItem).thenReturn(mediaItem("one", STREAM))
        Mockito.`when`(player.playWhenReady).thenReturn(true)
        val handoffs = mutableListOf<Boolean>()

        lease(player).confirm("https://example.test/other", "other", handoffs::add)

        assertTrue(handoffs.isEmpty())
        Mockito.verify(player, Mockito.never()).pause()
    }

    @Test
    fun resumePlaysOnlyMatchingPausedPhoneItem() {
        val player = Mockito.mock(MediaController::class.java)
        var playing = false
        Mockito.`when`(player.currentMediaItem).thenReturn(mediaItem("one", STREAM))
        Mockito.`when`(player.playWhenReady).thenAnswer { playing }
        doAnswer { playing = true; null }.`when`(player).play()

        lease(player).resume(STREAM, "one")

        assertTrue(playing)
        Mockito.verify(player).play()
    }

    private fun lease(player: MediaController) = PhoneCastHandoffLease(
        Futures.immediateFuture(player),
        Executor { command -> command.run() }
    )

    private fun mediaItem(stationId: String, uri: String) = MediaItem.Builder()
        .setMediaId(Station.MEDIA_ID_PREFIX + stationId)
        .setUri(uri)
        .build()

    private companion object {
        const val STREAM = "https://example.test/live"
    }
}
