package net.mspanc.twinsenradio.phone

import android.content.Context
import java.lang.ref.WeakReference
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.session.MediaController
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import org.json.JSONObject
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback
import com.google.android.gms.cast.MediaLoadRequestData
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

/** Phone-flavor Cast routing. Local playback remains the fallback until remote load succeeds. */
class PhoneCastController(
    private val activity: Context,
    private val localController: () -> MediaController?,
    private val currentStation: () -> Station?,
    private val onMessage: (String) -> Unit,
    private val onPlaybackChanged: () -> Unit,
    private val handoffLease: () -> PhoneCastHandoffLease? = { null }
) {
    private val castContext by lazy { CastContext.getSharedInstance(activity) }
    private var active = false
    private var remoteClient: RemoteMediaClient? = null
    private var remoteCallback: Callback? = null
    private var pendingLease: PhoneCastHandoffLease? = null
    private var pendingUri: String? = null
    private var pendingStationId: String? = null
    private var loadedUri: String? = null
    private var loadedStationId: String? = null
    private var remoteShouldPlay = true
    private var pendingStop = false
    private var requestGeneration = 0
    private var attachmentGeneration = 0
    private var initialStatusRequestGeneration: Int? = null
    private var initialStatusResultSucceededGeneration: Int? = null
    private var initialStatusCallbackObservedGeneration: Int? = null
    private var statusResolvedGeneration: Int? = null
    private var initialLoadAllowedGeneration: Int? = null
    private var explicitStationSelection: String? = null
    private var explicitQueueNavigationPending = false
    private var explicitQueueTarget: PlaybackTarget? = null
    private var unavailable = false
    private var mayLoadForSession = false
    private var castButton: WeakReference<MediaRouteButton>? = null
    private var unsupportedUri: String? = null
    private var failedUri: String? = null

    private data class PlaybackTarget(val mediaId: String?, val uri: String?)

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) =
            attach(session, userSelectedReceiver = true)
        override fun onSessionStartFailed(session: CastSession, error: Int) = clearRemote()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = endSession()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = clearRemote()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    fun createButton(parent: ViewGroup): MediaRouteButton = MediaRouteButton(
        ContextThemeWrapper(parent.context, R.style.ThemeOverlay_StreamingRadioPhone_CastButton)
    ).also { button ->
            castButton = WeakReference(button)
            button.contentDescription = "Cast to a device"
            val size = activity.resources.displayMetrics.density.let { (48 * it).toInt() }
            button.minimumWidth = size
            button.minimumHeight = size
            button.setPadding(size / 6, size / 6, size / 6, size / 6)
            parent.addView(button)
            try {
                CastButtonFactory.setUpMediaRouteButton(parent.context, button)
                button.dialogFactory = PhoneCastReceiverDialogFactory
            } catch (_: RuntimeException) {
                markUnavailable()
            }
        }

    fun onStart() {
        active = true
        if (unavailable) return
        runCatching {
            castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            val session = castContext.sessionManager.currentCastSession
            if (session == null) clearRemote() else attach(session)
        }.onFailure {
            markUnavailable()
            onPlaybackChanged()
        }
    }

    fun onStop() {
        active = false
        if (!unavailable) runCatching {
            castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
        remoteClient?.let { client -> remoteCallback?.let(client::unregisterCallback) }
        remoteCallback = null
    }

    /** Called when the existing RadioService queue advances or its active item changes. */
    fun onLocalPlaybackChanged() {
        if (!mayLoadForSession) return
        if (initialStatusRequestGeneration == attachmentGeneration ||
            statusResolvedGeneration != attachmentGeneration) return

        val explicitStation = explicitStationSelection
        if (explicitStation != null) {
            if (currentStation()?.id != explicitStation) return
            explicitStationSelection = null
            loadCurrentStation()
            return
        }

        val target = explicitQueueTarget
        if (explicitQueueNavigationPending) {
            val currentTarget = playbackTarget() ?: return
            if (currentTarget == target) return
            explicitQueueNavigationPending = false
            explicitQueueTarget = null
            loadCurrentStation()
            return
        }

        if (initialLoadAllowedGeneration == attachmentGeneration) loadCurrentStation()
    }

    fun onExplicitStationSelection(stationId: String) {
        mayLoadForSession = true
        explicitStationSelection = stationId
        explicitQueueTarget = null
        explicitQueueNavigationPending = false
    }

    fun onExplicitQueueNavigation() {
        mayLoadForSession = true
        explicitStationSelection = null
        explicitQueueNavigationPending = true
        explicitQueueTarget = playbackTarget()
    }

    private fun playbackTarget(): PlaybackTarget? = localController()?.currentMediaItem?.let {
        PlaybackTarget(it.mediaId, it.localConfiguration?.uri?.toString())
    }

    fun isCasting(): Boolean = remoteClient != null && loadedUri != null

    fun isRemotePlaying(): Boolean =
        if (pendingUri != null || pendingStop) remoteShouldPlay else remoteInPlayMode()

    private fun remoteInPlayMode(): Boolean = when (remoteClient?.mediaStatus?.playerState) {
        com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING,
        com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING -> true
        else -> false
    }

    fun toggleRemotePlayback() {
        val client = remoteClient ?: return
        if (!active || !mayLoadForSession) return
        if (initialStatusRequestGeneration == attachmentGeneration ||
            statusResolvedGeneration != attachmentGeneration) return

        if (pendingUri != null || pendingStop) {
            remoteShouldPlay = !remoteShouldPlay

            onPlaybackChanged()
            return
        }
        val remoteInfo = client.mediaInfo
        if (remoteInfo != null && !PhoneCastPolicy.ownsRemoteMedia(
                remoteInfo.customData?.optString(CUSTOM_OWNER))) return
        if (loadedUri == null) return
        if (remoteInPlayMode()) {
            remoteShouldPlay = false
            client.pause()
        } else {
            remoteShouldPlay = true
            val local = localController() ?: run {
                remoteShouldPlay = false
                onPlaybackChanged()
                return
            }
            val item = local.currentMediaItem
            val itemStation = item?.mediaId?.let(Station::idFromMediaId)
            val matches = itemStation == loadedStationId && currentStation()?.id == itemStation &&
                item?.localConfiguration?.uri?.toString() == loadedUri
            val unloaded = client.mediaStatus == null || client.mediaStatus?.playerState ==
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE
            if (local.playWhenReady && item == null) {
                remoteShouldPlay = false
                onPlaybackChanged()
                return
            }
            val localNeedsExactItemRecovery = local.playWhenReady && matches
            if (unloaded || localNeedsExactItemRecovery) {
                loadCurrentStation(forceReload = true)
                onPlaybackChanged()
                return
            }
            client.play()
        }
        onPlaybackChanged()
    }

    private fun attach(session: CastSession, userSelectedReceiver: Boolean = false) {
        if (!active) return
        pendingStop = false
        pendingLease?.close()
        pendingLease = null
        remoteClient?.let { client -> remoteCallback?.let(client::unregisterCallback) }
        val client = session.remoteMediaClient ?: run {
            clearRemote()
            return
        }
        remoteClient = client



        loadedUri = null
        loadedStationId = null
        pendingUri = null
        pendingStationId = null
        requestGeneration += 1
        attachmentGeneration += 1
        mayLoadForSession = userSelectedReceiver
        failedUri = null
        remoteShouldPlay = remoteInPlayMode()
        val statusGeneration = attachmentGeneration
        statusResolvedGeneration = null
        initialLoadAllowedGeneration = null
        initialStatusRequestGeneration = null
        initialStatusResultSucceededGeneration = null
        initialStatusCallbackObservedGeneration = null
        explicitStationSelection = null
        explicitQueueNavigationPending = false
        explicitQueueTarget = null
        remoteCallback = object : Callback() {
            override fun onStatusUpdated() {
                if (!active || remoteClient !== client) return
                if (initialStatusRequestGeneration == statusGeneration) {
                    initialStatusCallbackObservedGeneration = statusGeneration
                    if (initialStatusResultSucceededGeneration == statusGeneration) {
                        resolveInitialReceiverStatus(client, statusGeneration, userSelectedReceiver)
                    }
                }
                if (statusResolvedGeneration == statusGeneration) {
                    adoptOwnedRemoteMedia(client, requestGeneration)
                    relinquishForeignRemoteMedia(client)
                    if (pendingUri == null && !pendingStop && loadedUri != null && client.mediaStatus != null) {
                        remoteShouldPlay = remoteInPlayMode()
                    }
                }
                onPlaybackChanged()
            }
        }.also(client::registerCallback)
        requestInitialReceiverStatus(client, statusGeneration, userSelectedReceiver)
        onPlaybackChanged()
    }

    private fun requestInitialReceiverStatus(
        client: RemoteMediaClient,
        generation: Int,
        allowInitialLoad: Boolean
    ) {
        initialStatusRequestGeneration = generation
        initialStatusResultSucceededGeneration = null
        initialStatusCallbackObservedGeneration = null
        val request = try {
            client.requestStatus()
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastController", "Initial Cast status request failed", error)
            initialStatusRequestGeneration = null
            onMessage("Could not check the Cast device. Reconnect to retry.")
            return
        }
        try {
            request.setResultCallback { result ->
                if (!active || remoteClient !== client || attachmentGeneration != generation ||
                    initialStatusRequestGeneration != generation) return@setResultCallback
                if (!result.status.isSuccess) {
                    initialStatusRequestGeneration = null
                    initialStatusResultSucceededGeneration = null
                    initialStatusCallbackObservedGeneration = null
                    onMessage("Could not check the Cast device. Reconnect to retry.")
                    return@setResultCallback
                }
                initialStatusResultSucceededGeneration = generation
                if (initialStatusCallbackObservedGeneration == generation) {
                    resolveInitialReceiverStatus(client, generation, allowInitialLoad)
                }
            }
        } catch (error: RuntimeException) {
            if (initialStatusRequestGeneration == generation) {
                initialStatusRequestGeneration = null
                initialStatusResultSucceededGeneration = null
                initialStatusCallbackObservedGeneration = null
            }
            android.util.Log.w("PhoneCastController", "Initial Cast status callback failed", error)
            onMessage("Could not check the Cast device. Reconnect to retry.")
        }
    }

    private fun resolveInitialReceiverStatus(
        client: RemoteMediaClient,
        generation: Int,
        allowInitialLoad: Boolean
    ) {
        if (!active || remoteClient !== client || attachmentGeneration != generation ||
            initialStatusRequestGeneration != generation) return
        initialStatusRequestGeneration = null
        initialStatusResultSucceededGeneration = null
        initialStatusCallbackObservedGeneration = null

        val hasMedia = try {
            client.hasMediaSession() || client.mediaStatus != null || client.mediaInfo != null
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastController", "Initial Cast status unavailable", error)
            return
        }
        if (hasMedia) {
            adoptOwnedRemoteMedia(client, requestGeneration)
            if (loadedUri != null) remoteShouldPlay = remoteInPlayMode()
            statusResolvedGeneration = attachmentGeneration
            if (allowInitialLoad && loadedUri != null && remoteInPlayMode()) {
                localController()?.takeIf { it.playWhenReady }?.pause()
            }
            onPlaybackChanged()
            onLocalPlaybackChanged()
            return
        }

        // Require both successful request completion and its status callback. A null
        // pre-request cache alone never means the receiver is empty.
        loadedUri = null
        loadedStationId = null
        remoteShouldPlay = false
        statusResolvedGeneration = attachmentGeneration
        if (allowInitialLoad) {
            mayLoadForSession = true
            initialLoadAllowedGeneration = attachmentGeneration
        }
        onLocalPlaybackChanged()
    }

    private fun adoptOwnedRemoteMedia(client: RemoteMediaClient, generation: Int) {
        if (!active || remoteClient !== client || generation != requestGeneration ||
            pendingUri != null || pendingStop || loadedUri != null) return
        val info = client.mediaInfo ?: return
        val custom = info.customData
        if (!PhoneCastPolicy.ownsRemoteMedia(custom?.optString(CUSTOM_OWNER))) return
        loadedUri = info.contentId
        loadedStationId = custom?.optString(CUSTOM_STATION_ID)
        mayLoadForSession = true
        remoteShouldPlay = remoteInPlayMode()
    }

    private fun relinquishForeignRemoteMedia(client: RemoteMediaClient): Boolean {
        if (pendingUri != null || pendingStop || loadedUri == null) return false
        val info = client.mediaInfo ?: return false
        if (PhoneCastPolicy.ownsRemoteMedia(info.customData?.optString(CUSTOM_OWNER))) return false
        loadedUri = null
        loadedStationId = null
        mayLoadForSession = false
        return true
    }

    private fun loadCurrentStation(forceReload: Boolean = false) {
        val client = remoteClient ?: return
        if (relinquishForeignRemoteMedia(client)) {
            onPlaybackChanged()
            return
        }
        val currentInfo = client.mediaInfo
        val hasRemoteSession = try {
            client.hasMediaSession()
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastController", "Cast ownership unavailable", error)
            return
        }
        if ((hasRemoteSession || currentInfo != null) &&
            !PhoneCastPolicy.ownsRemoteMedia(currentInfo?.customData?.optString(CUSTOM_OWNER))) {
            mayLoadForSession = false
            explicitStationSelection = null
            explicitQueueNavigationPending = false
            explicitQueueTarget = null
            onPlaybackChanged()
            return
        }
        val station = currentStation() ?: return
        val localItem = localController()?.currentMediaItem ?: return
        val itemStation = localItem.mediaId.let(Station::idFromMediaId)
        if (itemStation != station.id) return
        val uri = localItem.localConfiguration?.uri?.toString() ?: station.stream
        if (failedUri == uri && !forceReload) return
        if (failedUri != null && failedUri != uri) failedUri = null
        val variantLabel = station.variants().firstOrNull { it.url == uri }?.label.orEmpty()
        val shouldLoad = forceReload || PhoneCastPolicy.shouldLoadCurrentStream(
            userSelectedReceiver = false,
            ownsRemoteMedia = loadedUri != null,
            remoteUri = loadedUri,
            requestedUri = uri,
            remoteStationId = loadedStationId,
            requestedStationId = station.id
        ) || (mayLoadForSession && loadedUri == null)
        if (!shouldLoad || (uri == pendingUri && station.id == pendingStationId)) return
        val measuredCodec = PlaybackStatusBus.quality.value?.codec
            ?.takeIf { PlaybackStatusBus.stationId.value == station.id && !PlaybackStatusBus.joining() }
        val contentType = PhoneCastPolicy.contentType(uri, variantLabel, measuredCodec)
        if (contentType == null) {
            if (unsupportedUri != uri) {
                onMessage("This stream format isn't identified for Cast.")
                unsupportedUri = uri
            }
            if (loadedUri != null) {
                pendingLease?.close()
                val lease = handoffLease()
                pendingLease = lease
                pendingUri = uri
                pendingStationId = station.id
                stopOldRemoteThenResumeLocal(client, uri, station.id, lease)
            }
            return
        }

        val previousLocalPlaying = localController()?.playWhenReady == true
        val initialLoad = loadedUri == null
        val autoplay = if (initialLoad) previousLocalPlaying else remoteShouldPlay
        val shouldPlayAfterLoad: (Boolean) -> Boolean = { phoneWantsPlay ->
            if (initialLoad && phoneWantsPlay != previousLocalPlaying) phoneWantsPlay
            else remoteShouldPlay && (!initialLoad || phoneWantsPlay)
        }
        remoteShouldPlay = autoplay
        val token = ++requestGeneration
        pendingStop = false
        pendingUri = uri
        pendingStationId = station.id
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, station.name)
            if (station.genre.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, station.genre)
        }
        val mediaInfo = MediaInfo.Builder(uri)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(metadata)
            .setCustomData(JSONObject().put(CUSTOM_OWNER, PhoneCastPolicy.OWNER_MARKER)
                .put(CUSTOM_STATION_ID, station.id))
            .build()

        pendingLease?.close()
        val lease = handoffLease()
        pendingLease = lease
        client.load(MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).setAutoplay(false).build())
            .setResultCallback { result ->
                if (token != requestGeneration || pendingUri != uri || pendingStationId != station.id) {
                    lease?.close()
                    return@setResultCallback
                }
                if (!active) {
                    pendingUri = null
                    pendingStationId = null
                    if (result.status.isSuccess) {
                        if (lease != null) lease.confirm(uri, station.id) { phoneWantsPlay ->
                            val shouldPlay = shouldPlayAfterLoad(phoneWantsPlay)
                            if (shouldPlay) client.play()
                        }
                        else if (autoplay) client.pause()
                    } else if (loadedUri != null) {
                        stopOldRemoteThenResumeLocal(client, uri, station.id, lease)
                    } else lease?.close()
                    return@setResultCallback
                }
                pendingUri = null
                pendingStationId = null
                if (result.status.isSuccess) {
                    lease?.close()
                    loadedUri = uri
                    loadedStationId = station.id
                    unsupportedUri = null
                    failedUri = null
                    val current = localController()
                    val currentUri = current?.currentMediaItem?.localConfiguration?.uri?.toString()
                    val currentStationId = current?.currentMediaItem?.mediaId?.let(Station::idFromMediaId)
                    if (current != null && currentStationId == station.id && currentStation()?.id == station.id &&
                        PhoneCastPolicy.shouldPauseLocalAfterLoad(true, currentUri, uri)) {
                        val shouldPlay = shouldPlayAfterLoad(current.playWhenReady)
                        remoteShouldPlay = shouldPlay
                        current.pause()
                        if (current.playWhenReady) {
                            loadedUri = null
                            loadedStationId = null
                            failedUri = uri
                            remoteShouldPlay = false
                        } else if (shouldPlay) client.play()
                    }
                } else {
                    failedUri = uri
                    onMessage("Could not start this station on the Cast device.")
                    if (loadedUri != null) {
                        stopOldRemoteThenResumeLocal(client, uri, station.id, lease)
                    } else lease?.close()
                }
                onPlaybackChanged()
            }
    }

    private fun stopOldRemoteThenResumeLocal(
        client: RemoteMediaClient,
        requestedUri: String,
        stationId: String,
        lease: PhoneCastHandoffLease? = null
    ) {
        pendingStop = true
        val stopToken = ++requestGeneration
        runCatching {
            client.stop().setResultCallback { result ->
                if (stopToken != requestGeneration) {
                    lease?.close()
                    return@setResultCallback
                }
                val resumePhone = remoteShouldPlay
                pendingUri = null
                pendingStationId = null
                pendingStop = false
                if (result.status.isSuccess) {
                    loadedUri = null
                    loadedStationId = null
                    remoteShouldPlay = false
                    if (!active) {
                        if (resumePhone) lease?.resume(requestedUri, stationId) else lease?.close()
                        return@setResultCallback
                    }
                    lease?.close()
                    val current = localController()
                    val currentItem = current?.currentMediaItem
                    val currentStationId = currentItem?.mediaId?.let(Station::idFromMediaId)
                    val currentUri = currentItem?.localConfiguration?.uri?.toString()
                    if (resumePhone && current != null && currentStationId == stationId &&
                        currentStation()?.id == stationId && currentUri == requestedUri &&
                        !current.playWhenReady) {
                        current.play()
                    }
                } else {
                    lease?.close()
                    if (active) onMessage("Could not confirm Cast stopped. Phone playback wasn't resumed.")
                }
                if (active) onPlaybackChanged()
            }
        }.onFailure {
            pendingStop = false
            lease?.close()
            if (active) {
                onMessage("Could not confirm Cast stopped. Phone playback wasn't resumed.")
                onPlaybackChanged()
            }
        }
    }

    private fun clearRemote() {
        pendingStop = false
        pendingLease?.close()
        pendingLease = null
        remoteClient?.let { client -> remoteCallback?.let(client::unregisterCallback) }
        remoteClient = null
        remoteCallback = null
        pendingUri = null
        pendingStationId = null
        requestGeneration += 1
        attachmentGeneration += 1
        loadedUri = null
        loadedStationId = null
        failedUri = null
        statusResolvedGeneration = null
        initialLoadAllowedGeneration = null
        initialStatusRequestGeneration = null
        initialStatusResultSucceededGeneration = null
        initialStatusCallbackObservedGeneration = null
        explicitStationSelection = null
        explicitQueueNavigationPending = false
        explicitQueueTarget = null
        onPlaybackChanged()
        mayLoadForSession = false
    }

    private fun endSession() {
        clearRemote()
    }

    private fun markUnavailable() {
        unavailable = true
        castButton?.get()?.apply {
            isEnabled = false
            contentDescription = "Cast unavailable: Google Play services required"
            tooltipText = contentDescription
        }
    }

    private companion object {
        const val CUSTOM_OWNER = "app"
        const val CUSTOM_STATION_ID = "stationId"
    }
}
