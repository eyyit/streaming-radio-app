package net.mspanc.twinsenradio.phone

import android.app.Activity
import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.ResultCallback
import com.google.android.gms.common.api.Status
import net.mspanc.twinsenradio.data.Station
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

    internal class Rig(
        station: Station,
        localPlaying: Boolean = false,
        durable: Boolean = false
    ) {
        val player: MediaController = mock()
        val durablePlayer: MediaController = mock()
        val manager: SessionManager = mock()
        private val castContext: CastContext = mock()
        val messages = mutableListOf<String>()
        private var activeStation: Station? = station
        private var localConnectionAvailable = true
        private val remotePlayback = IdentityHashMap<RemoteMediaClient, Int>()
        private val remoteStatuses = IdentityHashMap<RemoteMediaClient, MediaStatus>()
        private val loadRequests = IdentityHashMap<RemoteMediaClient, MediaLoadRequestData>()
        private val statusRequests = IdentityHashMap<RemoteMediaClient,
            MutableList<PendingResult<RemoteMediaClient.MediaChannelResult>>>()
        private val loadOwners = IdentityHashMap<PendingResult<RemoteMediaClient.MediaChannelResult>, RemoteMediaClient>()
        private val stopOwners = IdentityHashMap<PendingResult<RemoteMediaClient.MediaChannelResult>, RemoteMediaClient>()
        var phonePlaying = localPlaying
            private set
        val controller = PhoneCastController(
            activity = mock<Activity>(),
            localController = { player.takeIf { localConnectionAvailable } },
            currentStation = { activeStation.takeIf { localConnectionAvailable } },
            onMessage = messages::add,
            onPlaybackChanged = {},
            handoffLease = {
                if (durable) PhoneCastHandoffLease(
                    com.google.common.util.concurrent.Futures.immediateFuture(durablePlayer),
                    java.util.concurrent.Executor { it.run() }
                ) else null
            }
        ).also { controller ->
            val field = PhoneCastController::class.java.getDeclaredField("castContext\$delegate")
            field.isAccessible = true
            field.set(controller, lazyOf(castContext))
        }

        init {
            `when`(castContext.sessionManager).thenReturn(manager)
            `when`(player.currentMediaItem).thenReturn(mediaItem(station))
            `when`(durablePlayer.currentMediaItem).thenReturn(mediaItem(station))
            doAnswer { phonePlaying = false; null }.`when`(durablePlayer).pause()
            doAnswer { phonePlaying = true; null }.`when`(durablePlayer).play()
            `when`(player.playWhenReady).thenAnswer { phonePlaying }
            `when`(durablePlayer.playWhenReady).thenAnswer { phonePlaying }
            doAnswer {
                if (localConnectionAvailable) phonePlaying = false
                null
            }.`when`(player).pause()
            doAnswer {
                if (localConnectionAvailable) phonePlaying = true
                null
            }.`when`(player).play()
        }

        fun emptyLocalQueue() {
            `when`(player.currentMediaItem).thenReturn(null)
            activeStation = null
        }

        fun disconnectLocalController() {
            localConnectionAvailable = false
        }

        fun select(station: Station) {
            activeStation = station
            `when`(player.currentMediaItem).thenReturn(mediaItem(station))
            `when`(durablePlayer.currentMediaItem).thenReturn(mediaItem(station))
        }

        fun start(currentSession: CastSession? = null) {
            `when`(manager.currentCastSession).thenReturn(currentSession)
            controller.onStart()
        }

        fun activityStopped() {
            controller.onStop()
            localConnectionAvailable = false
        }

        fun listener(): SessionManagerListener<CastSession> {
            val field = PhoneCastController::class.java.getDeclaredField("sessionListener")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(controller) as SessionManagerListener<CastSession>
        }

        fun session(client: RemoteMediaClient): CastSession = mock<CastSession>().also {
            `when`(it.remoteMediaClient).thenReturn(client)
        }

        fun remoteClient(initiallyPlaying: Boolean = false): RemoteMediaClient {
            val client = mock<RemoteMediaClient>()
            remotePlayback[client] = if (initiallyPlaying) MediaStatus.PLAYER_STATE_PLAYING else MediaStatus.PLAYER_STATE_IDLE
            val status = mock<MediaStatus>()
            remoteStatuses[client] = status
            `when`(status.playerState).thenAnswer {
                remotePlayback[client] ?: MediaStatus.PLAYER_STATE_IDLE

            }
            `when`(client.mediaStatus).thenReturn(status)
            doAnswer {
                if (remotePlayback[client] != MediaStatus.PLAYER_STATE_IDLE) remotePlayback[client] = MediaStatus.PLAYER_STATE_PLAYING
                null
            }.`when`(client).play()
            doAnswer {
                if (remotePlayback[client] != MediaStatus.PLAYER_STATE_IDLE) remotePlayback[client] = MediaStatus.PLAYER_STATE_PAUSED
                null
            }.`when`(client).pause()
            `when`(client.requestStatus()).thenAnswer {
                mock<PendingResult<RemoteMediaClient.MediaChannelResult>>().also { pending ->
                    statusRequests.getOrPut(client) { mutableListOf() }.add(pending)
                }
            }
            return client
        }

        fun statusRequest(client: RemoteMediaClient, index: Int = 0) =
            statusRequests.getValue(client)[index]

        fun completeStatus(
            client: RemoteMediaClient,
            success: Boolean,
            hasMediaSession: Boolean,
            index: Int = 0,
            callbackFirst: Boolean = false
        ) {
            `when`(client.hasMediaSession()).thenReturn(hasMediaSession)
            val pending = statusRequest(client, index)
            val captor = resultCallbackCaptor()
            verify(pending).setResultCallback(captor.capture())
            if (callbackFirst) remoteStatusUpdated(client)
            captor.value.onResult(result(success))
            if (!callbackFirst) remoteStatusUpdated(client)
        }

        fun completeStatusResultOnly(
            client: RemoteMediaClient,
            success: Boolean,
            index: Int = 0
        ) {
            val pending = statusRequest(client, index)
            val captor = resultCallbackCaptor()
            verify(pending).setResultCallback(captor.capture())
            captor.value.onResult(result(success))
        }

        fun confirmEmptyReceiver(client: RemoteMediaClient) {
            `when`(client.mediaInfo).thenReturn(null)
            `when`(client.mediaStatus).thenReturn(null)
            completeStatus(client, success = true, hasMediaSession = false)
        }

        fun selectStationOnCast(client: RemoteMediaClient, stationId: String) {
            completeStatus(client, success = true, hasMediaSession = true)
            controller.onExplicitStationSelection(stationId)
            controller.onLocalPlaybackChanged()
        }

        fun remoteState(client: RemoteMediaClient, state: Int) { remotePlayback[client] = state }

        fun remoteStatusUpdated(client: RemoteMediaClient) {
            val captor = ArgumentCaptor.forClass(RemoteMediaClient.Callback::class.java)
            verify(client).registerCallback(captor.capture())
            captor.value.onStatusUpdated()
        }

        fun remoteIsPlaying(client: RemoteMediaClient): Boolean =
            client.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

        fun loadResult(client: RemoteMediaClient): PendingResult<RemoteMediaClient.MediaChannelResult> {
            val pending = mock<PendingResult<RemoteMediaClient.MediaChannelResult>>()
            loadOwners[pending] = client
            `when`(client.load(Mockito.any(MediaLoadRequestData::class.java))).thenAnswer { invocation ->
                loadRequests[client] = invocation.getArgument(0)
                pending
            }
            return pending
        }

        fun stopResult(client: RemoteMediaClient): PendingResult<RemoteMediaClient.MediaChannelResult> {
            val pending = mock<PendingResult<RemoteMediaClient.MediaChannelResult>>()
            stopOwners[pending] = client
            `when`(client.stop()).thenReturn(pending)
            return pending
        }

        fun loadRequest(client: RemoteMediaClient): MediaLoadRequestData {
            val captor = ArgumentCaptor.forClass(MediaLoadRequestData::class.java)
            verify(client, Mockito.atLeastOnce()).load(captor.capture())
            return captor.value
        }

        fun loadCallback(
            pending: PendingResult<RemoteMediaClient.MediaChannelResult>
        ): ResultCallback<RemoteMediaClient.MediaChannelResult> {
            val captor = resultCallbackCaptor()
            verify(pending).setResultCallback(captor.capture())
            return captor.value
        }

        fun completeLoad(
            pending: PendingResult<RemoteMediaClient.MediaChannelResult>,
            success: Boolean
        ) {
            if (success) {
                val client = loadOwners.getValue(pending)
                remotePlayback[client] = if (loadRequests.getValue(client).autoplay == true)
                    MediaStatus.PLAYER_STATE_PLAYING else MediaStatus.PLAYER_STATE_PAUSED
                `when`(client.mediaStatus).thenReturn(remoteStatuses.getValue(client))
            }
            loadCallback(pending).onResult(result(success))
        }

        fun completeStop(
            pending: PendingResult<RemoteMediaClient.MediaChannelResult>,
            success: Boolean
        ) {
            val captor = resultCallbackCaptor()
            verify(pending).setResultCallback(captor.capture())
            if (success) remotePlayback[stopOwners.getValue(pending)] = MediaStatus.PLAYER_STATE_IDLE
            captor.value.onResult(result(success))
        }

        fun result(success: Boolean): RemoteMediaClient.MediaChannelResult =
            mock<RemoteMediaClient.MediaChannelResult>().also { result ->
                val status = mock<Status>()
                `when`(status.isSuccess).thenReturn(success)
                `when`(result.status).thenReturn(status)
            }

        @Suppress("UNCHECKED_CAST")
        private fun resultCallbackCaptor(): ArgumentCaptor<ResultCallback<RemoteMediaClient.MediaChannelResult>> =
            ArgumentCaptor.forClass(ResultCallback::class.java) as
                ArgumentCaptor<ResultCallback<RemoteMediaClient.MediaChannelResult>>
    }

internal const val STREAM = "https://radio.test/live.mp3"

internal inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

internal fun station(id: String, uri: String) = Station(
    id = id,
    name = "Station $id",
    genre = "",
    stream = uri
)

internal fun mediaItem(station: Station) = MediaItem.Builder()
    .setMediaId(station.mediaId)
    .setUri(station.stream)
    .build()

internal fun remoteInfo(uri: String, owner: String, stationId: String) =
    MediaInfo.Builder(uri)
        .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
        .setContentType("audio/mpeg")
        .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK))
        .setCustomData(JSONObject().put("app", owner).put("stationId", stationId))
        .build()
