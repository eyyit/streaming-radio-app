package net.mspanc.twinsenradio.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy

/** Runs on the instrumentation main thread; no player or network is opened. */
@UnstableApi
object ReconnectPauseRegression {
    fun run(context: Context) {
        var wanted = true
        var state = Player.STATE_BUFFERING
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
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
        val reported = mutableListOf<ReconnectController.Status>()
        val controller = ReconnectController(context, player) { reported += it }
        val failure = PlaybackException(
            "Fixture connection failure", null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        try {
            controller.onPlayerError(failure)
            check(controller.status != ReconnectController.Status.OK)
            wanted = false
            controller.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            check(controller.status == ReconnectController.Status.OK) {
                "Explicit pause must clear the previous connection complaint"
            }
            check(reported.last() == ReconnectController.Status.OK)
            state = Player.STATE_IDLE
            controller.onPlaybackStateChanged(state)
            check(controller.status == ReconnectController.Status.OK)
            wanted = true
            controller.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            check(calls == listOf("stop", "seekToDefaultPosition", "prepare", "play")) {
                "Resuming an idle failed connection must restart at the live position"
            }
        } finally {
            controller.stop()
        }
    }
}
