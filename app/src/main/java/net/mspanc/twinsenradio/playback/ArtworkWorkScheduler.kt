package net.mspanc.twinsenradio.playback

import android.os.Process
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Artwork must not borrow the main/playback threads or fan out over Dispatchers.IO.
 * One background worker handles station search/download/preview work; one separate
 * worker handles the current song so a long logo search cannot queue it behind
 * twenty images. Blocking work is capped at two workers, not given network QoS.
 */
object ArtworkWorkScheduler {
    val dispatcher: ExecutorCoroutineDispatcher = artworkWorker("station-artwork") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }
    val trackDispatcher: ExecutorCoroutineDispatcher = artworkWorker("track-artwork") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }
}

/** Injectable priority hook keeps the queue/concurrency contract JVM-testable. */
internal fun artworkWorker(name: String, backgroundPriority: () -> Unit = {}): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { task ->
        Thread({ backgroundPriority(); task.run() }, name).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
