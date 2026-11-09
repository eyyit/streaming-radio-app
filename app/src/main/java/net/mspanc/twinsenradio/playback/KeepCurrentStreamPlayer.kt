package net.mspanc.twinsenradio.playback

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A wrapper that ignores a request to start a station that's already playing.
 *
 * Without this, selecting the same item from the list - whether in the app or in
 * Android Auto - triggered `setMediaItems` + `prepare`, i.e. disconnecting and
 * reconnecting to the stream. That's audible as a second-long gap, and for live
 * radio it makes no sense at all: it's the same, uninterrupted stream.
 *
 * The wrapper lives in the player layer on purpose - that way the guard applies
 * equally to the phone, the head unit, and voice control, without repeating the
 * condition in each of those places.
 */
@UnstableApi
class KeepCurrentStreamPlayer(player: Player) : ForwardingPlayer(player) {
    var stationNavigation: ((Int) -> Boolean)? = null

    override fun seekToPrevious() {
        if (stationNavigation?.invoke(-1) == true) return
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) super.seekToPreviousMediaItem()
        else super.seekToPrevious()
    }

    override fun seekToNext() {
        if (stationNavigation?.invoke(1) == true) return
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) super.seekToNextMediaItem()
        else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (stationNavigation?.invoke(1) != true) super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (stationNavigation?.invoke(-1) != true) super.seekToPreviousMediaItem()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (isAlreadyPlaying(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        if (startIndex in mediaItems.indices &&
            mediaItems[startIndex].mediaId == currentMediaItem?.mediaId &&
            playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            // Keep the current stream; only change its neighbors when choosing another browse context.
            val index = currentMediaItemIndex
            super.removeMediaItems(index + 1, mediaItemCount)
            super.removeMediaItems(0, index)
            super.addMediaItems(0, mediaItems.take(startIndex))
            super.addMediaItems(mediaItems.drop(startIndex + 1))
            if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE && !playWhenReady) play()
            return
        }
        if (isAlreadyPlaying(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    /**
     * A single item with the same mediaId as the one currently playing, with live
     * playback. State IDLE or ENDED means the stream has to be brought up again
     * anyway.
     */
    private fun isAlreadyPlaying(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val requested = mediaItems.first().mediaId
        val current = currentMediaItem?.mediaId ?: return false
        if (requested != current) return false
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return false

        Log.i(TAG, "stacja $requested juz gra - nie przerywam strumienia")
        if (!playWhenReady) play()
        return true
    }

    private companion object {
        const val TAG = "KeepCurrentStream"
    }
}
