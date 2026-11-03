package net.mspanc.twinsenradio.playback

import java.util.concurrent.CancellationException

/** Roll back both records if publishing, preference persistence or cancellation fails. */
internal object ArtworkSelectionTransaction {
    fun run(
        checkActive: () -> Unit,
        publishImage: () -> Unit,
        commitPreferences: () -> Boolean,
        restoreImage: () -> Unit,
        restorePreferences: () -> Unit,
    ): Boolean {
        try {
            checkActive()
            publishImage()
            checkActive()
            if (!commitPreferences()) {
                restoreImage()
                restorePreferences()
                return false
            }
            checkActive()
            return true
        } catch (failure: Exception) {
            runCatching { restoreImage() }
            runCatching { restorePreferences() }
            if (failure is CancellationException) throw failure
            return false
        }
    }
}
