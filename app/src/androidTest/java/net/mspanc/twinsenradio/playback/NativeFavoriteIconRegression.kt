package net.mspanc.twinsenradio.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads the real legacy custom action used by native automotive hosts; changes no favorite. */
object NativeFavoriteIconRegression {
    @androidx.media3.common.util.UnstableApi
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        var failed = false
        lateinit var browser: MediaBrowserCompat
        instrumentation.runOnMainSync {
            browser = MediaBrowserCompat(context, ComponentName(context, RadioService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                    override fun onConnectionFailed() { failed = true; connected.countDown() }
                }, null)
            browser.connect()
        }
        try {
            check(connected.await(10, TimeUnit.SECONDS) && !failed) { "Native media browser must connect" }
            var icon: Int? = null
            val deadline = System.nanoTime() + 5_000_000_000L
            while (icon == null && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    val action = MediaControllerCompat(context, browser.sessionToken).playbackState
                        ?.customActions?.firstOrNull { it.action == "net.mspanc.twinsenradio.TOGGLE_FAV" }
                    icon = action?.extras?.getInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT)
                }
                if (icon == null) Thread.sleep(50)
            }
            check(icon == CommandButton.ICON_HEART_FILLED || icon == CommandButton.ICON_HEART_UNFILLED) {
                "Native favorite action must advertise a heart, got $icon"
            }
        } finally { instrumentation.runOnMainSync { browser.disconnect() } }
    }
}
