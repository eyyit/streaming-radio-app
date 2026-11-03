package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * The retry policy deliberately doesn't propagate network errors upward, so
 * error messages don't flash on screen in the car. The side effect is that a
 * dropped stream looks exactly like slow buffering. This listener logs what
 * is actually happening - otherwise diagnosing issues in the field is
 * guesswork.
 *
 *   adb logcat -s LoadDiag
 */
@UnstableApi
class LoadDiagnostics(
    /**
     * The format as determined by the decoder. This is the only place where
     * we learn the real codec and sample rate - station headers can disagree
     * with them.
     */
    private val onAudioFormat: (Format) -> Unit = {}
) : AnalyticsListener {

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        Log.i(
            TAG,
            "format dzwieku: ${format.sampleMimeType} codecs=${format.codecs} " +
                "bitrate=${format.bitrate} sr=${format.sampleRate} ch=${format.channelCount}"
        )
        onAudioFormat(format)
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "start pobierania: ${DiagnosticUrls.endpoint(loadEventInfo.dataSpec.uri.toString())}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(
            TAG,
            "zakonczone: ${loadEventInfo.bytesLoaded} B w ${loadEventInfo.loadDurationMs} ms " +
                "(${DiagnosticUrls.endpoint(loadEventInfo.uri.toString())})"
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        Log.w(
            TAG,
            "BLAD pobierania (${DiagnosticUrls.endpoint(loadEventInfo.uri.toString())}) po ${loadEventInfo.loadDurationMs} ms, " +
                "pobrano ${loadEventInfo.bytesLoaded} B, anulowane=$wasCanceled: " +
                DiagnosticUrls.failure(error)
        )
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        Log.i(TAG, "anulowano pobieranie: ${DiagnosticUrls.endpoint(loadEventInfo.uri.toString())}")
    }

    private var audioUnderrunCount = 0
    private var lastAudioUnderrunLogNs = 0L
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
        bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        audioUnderrunCount++
        val now = System.nanoTime()
        if (now - lastAudioUnderrunLogNs >= 1_000_000_000L) {
            lastAudioUnderrunLogNs = now
            Log.w(TAG, "Audio sink underrun count=$audioUnderrunCount bufferBytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")
        }
    }

    private companion object {
        const val TAG = "LoadDiag"
    }
}
