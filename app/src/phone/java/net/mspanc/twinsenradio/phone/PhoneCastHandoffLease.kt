package net.mspanc.twinsenradio.phone

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.playback.RadioService

/** A single LOAD owns this app-context connection until its audio handoff resolves. */
class PhoneCastHandoffLease internal constructor(
    private val connection: ListenableFuture<MediaController>,
    private val executor: java.util.concurrent.Executor
) {
    private var closed = false

    fun confirm(uri: String, stationId: String, onHandoff: (Boolean) -> Unit) {
        connection.addListener({
            if (closed) return@addListener
            try {
                val player = connection.get()
                val item = player.currentMediaItem
                if (item?.mediaId?.let(Station::idFromMediaId) == stationId &&
                    item.localConfiguration?.uri?.toString() == uri) {
                    val wantsPlay = player.playWhenReady
                    player.pause()
                    if (!player.playWhenReady) onHandoff(wantsPlay)
                }
            } catch (_: Exception) {
                // The receiver remains paused if its phone boundary is unavailable.
            } finally {
                close()
            }
        }, executor)
    }

    fun resume(uri: String, stationId: String) {
        connection.addListener({
            if (closed) return@addListener
            try {
                val player = connection.get()
                val item = player.currentMediaItem
                if (item?.mediaId?.let(Station::idFromMediaId) == stationId &&
                    item.localConfiguration?.uri?.toString() == uri && !player.playWhenReady) {
                    player.play()
                }
            } catch (_: Exception) {
                // A failed phone connection cannot safely resume playback.
            } finally {
                close()
            }
        }, executor)
    }

    fun close() {
        if (closed) return
        closed = true
        MediaController.releaseFuture(connection)
    }

    companion object {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun connect(context: Context): PhoneCastHandoffLease {
            val app = context.applicationContext
            val future = MediaController.Builder(app,
                SessionToken(app, ComponentName(app, RadioService::class.java))).buildAsync()
            return PhoneCastHandoffLease(future, ContextCompat.getMainExecutor(app))
        }
    }
}
