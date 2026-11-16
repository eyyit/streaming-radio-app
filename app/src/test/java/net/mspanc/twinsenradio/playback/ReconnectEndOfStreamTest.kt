// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.os.Looper
import androidx.media3.common.Player
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.time.Duration

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReconnectEndOfStreamTest {
    private var wanted = true
    private var state = Player.STATE_ENDED
    private val calls = mutableListOf<String>()
    private val player = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getPlayWhenReady" -> wanted
            "getPlaybackState" -> state
            "removeListener" -> null
            "stop", "seekToDefaultPosition", "prepare", "play" -> {
                calls += method.name
                null
            }
            else -> error("Unexpected player call: ${method.name}")
        }
    } as Player
    private val controller = ReconnectController(
        RuntimeEnvironment.getApplication(), player
    ) {}

    @After fun tearDown() { controller.stop() }

    @Test fun cleanRelayDisconnectRejoinsLiveStreamAfterBackoff() {
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue("EOF should not restart synchronously", calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val expected = if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) emptyList()
            else listOf("stop", "seekToDefaultPosition", "prepare", "play")
        assertEquals(expected, calls)
    }

    @Test fun pausedEndOfStreamDoesNotRestartAudio() {
        wanted = false
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(calls.isEmpty())
    }

    @Test fun automaticEndOfItemPauseRejoinsTheSameLiveStation() {
        wanted = false
        state = Player.STATE_IDLE
        controller.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        val expected = if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) emptyList()
            else listOf("stop", "seekToDefaultPosition", "prepare", "play")
        assertEquals(expected, calls)
    }

    @Test fun pauseDuringBackoffCancelsRecovery() {
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        wanted = false
        controller.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(calls.isEmpty())
        assertEquals(ReconnectController.Status.OK, controller.status)
    }

    @Test fun readyReplacementCancelsOldEndOfStreamRetry() {
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        state = Player.STATE_READY
        controller.onPlaybackStateChanged(state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(calls.isEmpty())
    }

    @Test fun stoppedControllerCannotRestartPlayback() {
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        controller.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(calls.isEmpty())
    }
}
