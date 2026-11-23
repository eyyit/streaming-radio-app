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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class PhoneCastControllerTestPart3 {
    @Test
    fun pickerRejoinWaitsForStatusAndPreservesAlreadyPlayingOwnedMedia() {
        val rig = Rig(station = station("saved-paused-selection", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        val playingStatus = client.mediaStatus
        `when`(client.mediaStatus).thenReturn(null)
        `when`(client.mediaInfo).thenReturn(null)
        rig.start()

        rig.listener().onSessionStarted(rig.session(client), "joined")

        verify(client).requestStatus()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        rig.controller.onLocalPlaybackChanged()
        verify(rig.player, never()).pause()
        verify(rig.player, never()).play()

        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "already-playing")
        )
        `when`(client.mediaStatus).thenReturn(playingStatus)
        rig.completeStatus(
            client, success = true, hasMediaSession = true, callbackFirst = true
        )

        assertTrue("The receiver's playing state should be adopted", rig.controller.isRemotePlaying())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(rig.player, never()).pause()
        verify(rig.player, never()).play()
        assertTrue(rig.phonePlaying.not())
    }

    @Test
    fun pickerRejoinDoesNotReplaceOwnedReceiverForStalePausedStationSelection() {
        val rig = Rig(station = station("saved-selection", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "different-playing-station")
        )
        rig.start()

        rig.listener().onSessionStarted(rig.session(client), "joined")
        rig.completeStatus(client, success = true, hasMediaSession = true)

        assertTrue(rig.controller.isRemotePlaying())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(rig.player, never()).pause()
        verify(rig.player, never()).play()
    }

    @Test
    fun failedInitialStatusRequestDoesNotOverwriteUnknownReceiverOrPhoneState() {
        val rig = Rig(station = station("saved-paused-selection", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaStatus).thenReturn(null)
        `when`(client.mediaInfo).thenReturn(null)
        rig.start()

        rig.listener().onSessionStarted(rig.session(client), "joined")
        rig.completeStatus(client, success = false, hasMediaSession = false)

        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(rig.player, never()).pause()
        verify(rig.player, never()).play()
        assertFalse(rig.phonePlaying)
        rig.controller.onLocalPlaybackChanged()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun staleInitialStatusResponseCannotLoadIntoNewlyAttachedSession() {
        val rig = Rig(station = station("one", STREAM))
        val oldClient = rig.remoteClient()
        val oldSession = rig.session(oldClient)
        rig.start()
        rig.listener().onSessionStarted(oldSession, "old")

        val newClient = rig.remoteClient()
        rig.loadResult(newClient)
        rig.listener().onSessionStarted(rig.session(newClient), "new")
        rig.confirmEmptyReceiver(oldClient)

        verify(oldClient, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(newClient, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        rig.confirmEmptyReceiver(newClient)
        verify(newClient).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(oldClient, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun initialLoadWaitsForRequestStatusCallbackEvenAfterRequestSucceeds() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")

        rig.completeStatusResultOnly(client, success = true)
        rig.controller.onLocalPlaybackChanged()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))

        `when`(client.mediaInfo).thenReturn(null)
        `when`(client.mediaStatus).thenReturn(null)
        rig.remoteStatusUpdated(client)
        rig.loadRequest(client)
        rig.completeLoad(load, success = true)
        assertTrue(rig.controller.isCasting())
    }

    @Test
    fun ignoredDurablePauseCannotStartRemoteAudioAfterStop() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true, durable = true)
        doAnswer { null }.`when`(rig.durablePlayer).pause()
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.activityStopped()
        rig.completeLoad(load, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse("remote must wait for an accepted durable pause", rig.remoteIsPlaying(client))
    }

    @Test
    fun opaqueReplacementWaitsForSelectedAacTrackBeforeChangingReceiver() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"))
        val client = rig.remoteClient(initiallyPlaying = true)
        rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        `when`(rig.player.playbackState).thenReturn(androidx.media3.common.Player.STATE_BUFFERING)
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "unknown")
        verify(client, never()).stop()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        assertTrue(rig.messages.isEmpty())

        val format = androidx.media3.common.Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(format), false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED), booleanArrayOf(true)
        )
        `when`(rig.player.currentTracks).thenReturn(androidx.media3.common.Tracks(listOf(group)))
        rig.controller.onLocalPlaybackChanged()

        assertEquals("audio/aac", rig.loadRequest(client).mediaInfo!!.contentType)
        verify(client, never()).stop()
        assertFalse(rig.phonePlaying)
    }

    @Test
    fun firstCastWaitsForAacAndSucceedsWithoutWarning() = firstCastWaitsForFormat("audio/mp4a-latm", "audio/aac")

    @Test
    fun firstCastWaitsForFlacAndSucceedsWithoutWarning() = firstCastWaitsForFormat("audio/flac", "audio/flac")

    @Test
    fun firstCastWaitsForOggFlacAndSucceedsWithoutWarning() =
        firstCastWaitsForFormat("audio/flac", "audio/ogg; codecs=flac", containerMimeType = "audio/ogg")

    private fun firstCastWaitsForFormat(sampleMimeType: String, expected: String, containerMimeType: String? = null) {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        `when`(rig.player.playbackState).thenReturn(androidx.media3.common.Player.STATE_BUFFERING)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)

        assertTrue(rig.phonePlaying)
        assertTrue(rig.messages.isEmpty())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        `when`(rig.player.playbackState).thenReturn(androidx.media3.common.Player.STATE_READY)
        rig.controller.onLocalPlaybackChanged()
        assertTrue(rig.messages.isEmpty())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))

        val format = androidx.media3.common.Format.Builder().setSampleMimeType(sampleMimeType).setContainerMimeType(containerMimeType).build()
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(format), false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED), booleanArrayOf(true)
        )
        `when`(rig.player.currentTracks).thenReturn(androidx.media3.common.Tracks(listOf(group)))
        rig.controller.onLocalPlaybackChanged()
        assertEquals(expected, rig.loadRequest(client).mediaInfo!!.contentType)
        rig.completeLoad(pending, success = true)

        assertTrue(rig.controller.isCasting())
        assertFalse(rig.phonePlaying)
        assertTrue(rig.messages.isEmpty())
    }

    @Test
    fun failedStopClearsCastOnlyWhenReceiverReportsIdle() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"))
        val client = rig.remoteClient(initiallyPlaying = true)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "unknown")
        rig.completeStop(stop, success = false)
        assertTrue(rig.controller.isCasting())
        rig.statusRequest(client, index = 1)
        rig.remoteStatusUpdated(client)
        assertTrue(rig.controller.isCasting())
        rig.remoteState(client, MediaStatus.PLAYER_STATE_IDLE)
        rig.remoteStatusUpdated(client)

        assertFalse(rig.controller.isCasting())
        rig.remoteStatusUpdated(client)
        assertFalse(rig.controller.isCasting())
        assertFalse(rig.phonePlaying)
    }

    @Test
    fun unidentifiedReplacementStopsOwnedRemoteBeforePhoneResume() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "unknown")
        verify(client).stop()
        assertFalse(rig.phonePlaying)
        rig.completeStop(stop, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun unidentifiedReplacementCanResumePhoneAfterUiStops() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "unknown")
        rig.activityStopped()
        rig.completeStop(stop, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
        verify(rig.player, never()).play()
        verify(rig.durablePlayer).play()
    }

    @Test
    fun unidentifiedReplacementFailedStopNeverResumesPhone() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "unknown")
        rig.completeStop(stop, success = false)
        assertFalse(rig.phonePlaying)
        assertTrue(rig.remoteIsPlaying(client))
        verify(rig.player, never()).play()
        verify(rig.durablePlayer, never()).play()
    }

    @Test
    fun initialUnidentifiedStreamKeepsPhonePlayingWithoutGuessingMime() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), localPlaying = true)
        val client = rig.remoteClient()
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        assertTrue(rig.phonePlaying)
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(client, never()).stop()
    }

    @Test
    fun newerConfirmedLoadSupersedesUnidentifiedFallbackStop() {
        val rig = Rig(station = station("unknown", "https://radio.test/live"), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val stop = rig.stopResult(client)
        val load = rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.controller.onExplicitStationSelection("unknown")
        rig.controller.onLocalPlaybackChanged()
        verify(client).stop()
        rig.select(station("new", "https://radio.test/new.mp3"))
        rig.controller.onExplicitStationSelection("new")
        rig.controller.onLocalPlaybackChanged()
        rig.completeLoad(load, success = true)
        val field = PhoneCastController::class.java.getDeclaredField("pendingStop")
        field.isAccessible = true
        assertFalse(field.getBoolean(rig.controller))
        rig.completeStop(stop, success = true)
        assertTrue(rig.controller.isCasting())
        assertFalse(rig.phonePlaying)
        verify(rig.player, never()).play()
    }

    @Test
    fun explicitPlayReloadsIdleOwnedMediaButStatusAloneDoesNot() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one"))
        rig.start(currentSession = rig.session(client))
        rig.remoteState(client, MediaStatus.PLAYER_STATE_IDLE)
        rig.remoteStatusUpdated(client)
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.controller.toggleRemotePlayback()
        assertFalse(rig.loadRequest(client).autoplay == true)
        verify(client, never()).play()
        rig.completeLoad(load, success = true)
        assertTrue(rig.remoteIsPlaying(client))
    }

    @Test
    fun explicitPlayResumesPausedOwnedMediaWithoutReload() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one"))
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.controller.toggleRemotePlayback()
        assertTrue(rig.remoteIsPlaying(client))
        verify(client).play()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun explicitPlayResumesPausedOwnedMediaWhenLocalLibraryIsEmpty() {
        val rig = Rig(station = station("one", STREAM))
        rig.emptyLocalQueue()
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one")
        )
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)

        rig.controller.toggleRemotePlayback()

        verify(client).play()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun userPauseWhileLoadPendingCannotSendEarlyPlayOrStartOnAck() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.controller.toggleRemotePlayback()
        verify(client, never()).play()
        rig.completeLoad(load, success = true)
        assertFalse(rig.remoteIsPlaying(client))
        assertFalse(rig.phonePlaying)
    }

    @Test
    fun phonePlayDuringPendingInitialLoadStartsReceiverAfterAck() {
        val rig = Rig(station = station("one", STREAM), localPlaying = false)
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        assertFalse(rig.loadRequest(client).autoplay == true)

        // While loadedUri is still null, the UI Play control targets the phone.
        rig.player.play()
        rig.controller.onLocalPlaybackChanged()
        verify(client, never()).play()

        rig.completeLoad(load, success = true)

        verify(rig.player).pause()
        verify(client).play()
        assertTrue(rig.remoteIsPlaying(client))
        assertFalse(rig.phonePlaying)
    }

    @Test
    fun phonePlayDuringPendingInitialLoadStartsDurableReceiverAfterAck() {
        val rig = Rig(station = station("one", STREAM), localPlaying = false, durable = true)
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        assertFalse(rig.loadRequest(client).autoplay == true)

        // The live UI routes Play to the phone until the initial load is adopted.
        rig.player.play()
        rig.controller.onLocalPlaybackChanged()
        verify(client, never()).play()
        rig.activityStopped()

        rig.completeLoad(load, success = true)

        verify(rig.durablePlayer).pause()
        verify(client).play()
        assertTrue(rig.remoteIsPlaying(client))
        assertFalse(rig.phonePlaying)
    }

    @Test
    fun userPauseWhileStopPendingCannotSendPlayOrResumePhone() {
        val rig = Rig(station = station("new", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new")
        rig.completeLoad(load, success = false)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.controller.toggleRemotePlayback()
        verify(client, never()).play()
        assertFalse(rig.phonePlaying)
        rig.completeStop(stop, success = true)
        assertFalse(rig.phonePlaying)
        verify(rig.player, never()).play()
    }

    @Test
    fun explicitPlayOnPausedRemoteReloadsBeforePausingActivePhone() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one"))
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.controller.toggleRemotePlayback()
        assertTrue(rig.phonePlaying)
        assertFalse(rig.loadRequest(client).autoplay == true)
        verify(client, never()).play()
        rig.completeLoad(load, success = true)
        assertFalse(rig.phonePlaying)
        assertTrue(rig.remoteIsPlaying(client))
        val order = Mockito.inOrder(rig.player, client)
        order.verify(rig.player).pause()
        order.verify(client).play()
    }

    @Test
    fun explicitPlayResumesPausedRemoteWithoutReplacingSavedStation() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one"))
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.select(station("new", "https://radio.test/new.mp3"))
        rig.controller.toggleRemotePlayback()
        verify(client).play()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        assertTrue(rig.remoteIsPlaying(client))
    }

    @Test
    fun pickerJoinPreservesForeignMediaAndDoesNotCommandIt() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, "foreign-app", "foreign"))
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.controller.toggleRemotePlayback()
        verify(client, never()).pause()
        verify(client, never()).play()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        assertTrue(rig.phonePlaying)
        assertTrue(rig.remoteIsPlaying(client))

        rig.controller.onExplicitStationSelection("one")
        rig.controller.onLocalPlaybackChanged()
        rig.controller.onExplicitQueueNavigation()
        rig.select(station("next", "https://radio.test/next.mp3"))
        rig.controller.onLocalPlaybackChanged()

        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(client, never()).stop()
        assertTrue(rig.remoteIsPlaying(client))
    }

    @Test
    fun explicitStationAndQueueActionsDoNotTakeOverUnknownActiveReceiver() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(null)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "selected")
        rig.completeStatus(client, success = true, hasMediaSession = true)

        rig.controller.onExplicitStationSelection("one")
        rig.controller.onLocalPlaybackChanged()
        rig.controller.onExplicitQueueNavigation()
        rig.select(station("next", "https://radio.test/next.mp3"))
        rig.controller.onLocalPlaybackChanged()

        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(client, never()).stop()
        assertTrue(rig.remoteIsPlaying(client))
    }

    @Test
    fun pendingLoadGetterUsesIntentWhileReceiverTemporarilyPaused() {
        val rig = Rig(station = station("new", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new")
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        assertTrue(rig.controller.isRemotePlaying())
        rig.controller.toggleRemotePlayback()
        assertFalse(rig.controller.isRemotePlaying())
        verify(client, never()).play()
    }

    @Test
    fun pendingStopGetterUsesIntentUntilExplicitPause() {
        val rig = Rig(station = station("new", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new")
        rig.completeLoad(load, success = false)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_IDLE)
        assertTrue(rig.controller.isRemotePlaying())
        rig.controller.toggleRemotePlayback()
        assertFalse(rig.controller.isRemotePlaying())
        verify(client, never()).play()
    }

    @Test
    fun bufferingReceiverIsPlayModeAndExplicitTogglePauses() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one"))
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_BUFFERING)
        rig.remoteStatusUpdated(client)
        assertTrue(rig.controller.isRemotePlaying())
        rig.controller.toggleRemotePlayback()
        verify(client).pause()
        verify(client, never()).play()
        assertFalse(rig.controller.isRemotePlaying())
    }

    @Test
    fun resumedSessionAdoptsLateOwnedMediaWithoutReloadAndCanPause() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val eventualStatus = client.mediaStatus
        `when`(client.mediaStatus).thenReturn(null)
        rig.start(currentSession = rig.session(client))
        assertFalse(rig.controller.isCasting())
        assertFalse(rig.controller.isRemotePlaying())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))

        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one")
        )
        `when`(client.mediaStatus).thenReturn(eventualStatus)
        rig.remoteStatusUpdated(client)
        rig.completeStatus(client, success = true, hasMediaSession = true)

        assertTrue("Late owned receiver media should be adopted", rig.controller.isCasting())
        assertTrue(rig.controller.isRemotePlaying())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verify(rig.player, never()).pause()
        verify(rig.player, never()).play()
        rig.controller.toggleRemotePlayback()
        verify(client).pause()
        assertFalse(rig.remoteIsPlaying(client))
    }

    @Test
    fun pausedOwnedRemoteResumesOnlyWhenPhonePlaybackIsKnownPaused() {
        for ((phonePlaying, connected) in listOf(false to true, true to true, false to false)) {
            val rig = Rig(station = station("one", STREAM), localPlaying = phonePlaying)
            val client = rig.remoteClient(initiallyPlaying = true)
            `when`(client.mediaInfo).thenReturn(
                remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one")
            )
            rig.emptyLocalQueue()
            rig.start(currentSession = rig.session(client))
            rig.completeStatus(client, success = true, hasMediaSession = true)
            if (!connected) rig.disconnectLocalController()
            rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)

            rig.controller.toggleRemotePlayback()

            val shouldResume = connected && !phonePlaying
            assertEquals("phonePlaying=$phonePlaying, connected=$connected",
                shouldResume, rig.remoteIsPlaying(client))
            if (shouldResume) verify(client).play() else verify(client, never()).play()
            verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
            verify(rig.player, never()).pause()
            verify(rig.player, never()).play()
            assertEquals(phonePlaying, rig.phonePlaying)
        }
    }

    @Test
    fun replacingOwnedMediaWithoutStatusUpdateDoesNotReload() {
        assertForeignMediaReplacementDoesNotCauseLoad(deliverStatusUpdate = false)
    }

    @Test
    fun replacingOwnedMediaAfterStatusUpdateDoesNotReload() {
        assertForeignMediaReplacementDoesNotCauseLoad(deliverStatusUpdate = true)
    }

    private fun assertForeignMediaReplacementDoesNotCauseLoad(deliverStatusUpdate: Boolean) {
        val rig = Rig(station = station("one", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "one")
        )
        rig.start(currentSession = rig.session(client))
        rig.completeStatus(client, success = true, hasMediaSession = true)
        assertTrue("The pre-existing owned session should be adopted",
            rig.controller.isCasting())
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))

        `when`(client.mediaInfo).thenReturn(
            remoteInfo("https://radio.test/foreign.mp3", "another-app", "foreign")
        )
        rig.select(station("one", "https://radio.test/new.mp3"))
        if (deliverStatusUpdate) rig.remoteStatusUpdated(client)

        rig.controller.onLocalPlaybackChanged()

        assertFalse("An ownership change must not auto-play the phone", rig.phonePlaying)
        verify(client, never()).play()
        verify(client, never()).pause()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun asyncPlayAfterAutoplayFalseLoadSurvivesTransientPausedStatusAndKeepsNextStationPlaying() {
        val rig = Rig(station = station("one", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        // Simulate a real Chromecast where client.play() is asynchronous and does not
        // synchronously flip mediaStatus from the post-load PAUSED state to PLAYING.
        doAnswer { null }.`when`(client).play()
        val firstLoad = rig.loadResult(client)
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "one")
        assertEquals("one", rig.controller.pendingTargetStationId())

        rig.completeLoad(firstLoad, success = true)
        verify(client).play()
        // Receiver emits its post-load(autoplay=false) PAUSED status before PLAY takes effect.
        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.remoteStatusUpdated(client)
        assertTrue("Transient post-load PAUSED status must not flip play state to paused",
            rig.controller.isRemotePlaying())

        // User skips to a second station before or after PLAY settles.
        val secondLoad = rig.loadResult(client)
        rig.controller.onExplicitQueueNavigation()
        rig.select(station("two", "https://radio.test/two.mp3"))
        rig.controller.onLocalPlaybackChanged()
        assertEquals("two", rig.controller.pendingTargetStationId())
        rig.completeLoad(secondLoad, success = true)
        verify(client, Mockito.times(2)).play()
        assertTrue(rig.controller.isRemotePlaying())
    }

    @Test
    fun skippingToAwaitingFormatStreamSupersedesInFlightLoadAndPreservesPlayIntent() {
        val rig = Rig(station = station("one", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        `when`(rig.player.playbackState).thenReturn(androidx.media3.common.Player.STATE_BUFFERING)
        val firstLoad = rig.loadResult(client)
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "one")

        // Before firstLoad finishes, user skips to an extensionless stream that awaits format.
        rig.controller.onExplicitQueueNavigation()
        rig.select(station("dublab", "http://dublab.out.airtime.pro:8000/dublab_a"))
        rig.controller.onLocalPlaybackChanged()
        assertEquals("dublab", rig.controller.pendingTargetStationId())
        assertTrue(rig.controller.isRemotePlaying())

        // Stale completion of firstLoad must be ignored and must not leave the receiver paused.
        rig.completeLoad(firstLoad, success = true)
        rig.remoteStatusUpdated(client)
        assertTrue("Superseded load must not clobber remoteShouldPlay", rig.controller.isRemotePlaying())

        // Once ExoPlayer sniffs the audio format for dublab, it loads and plays on Cast.
        val secondLoad = rig.loadResult(client)
        val format = androidx.media3.common.Format.Builder().setSampleMimeType("audio/mpeg").build()
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(format), false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED), booleanArrayOf(true)
        )
        `when`(rig.player.currentTracks).thenReturn(androidx.media3.common.Tracks(listOf(group)))
        rig.controller.onLocalPlaybackChanged()
        assertEquals("http://dublab.out.airtime.pro:8000/dublab_a", rig.loadRequest(client).mediaInfo!!.contentId)
        rig.completeLoad(secondLoad, success = true)
        assertTrue(rig.remoteIsPlaying(client))
        assertTrue(rig.controller.isRemotePlaying())
    }

    @Test
    fun pendingPlaySurvivesIntermediateBufferingBeforePostLoadPausedStatus() {
        val rig = Rig(station = station("one", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old"))
        doAnswer { null }.`when`(client).play()
        val firstLoad = rig.loadResult(client)
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "one")

        rig.completeLoad(firstLoad, success = true)
        verify(client).play()

        rig.remoteState(client, MediaStatus.PLAYER_STATE_BUFFERING)
        rig.remoteStatusUpdated(client)
        assertTrue(rig.controller.isRemotePlaying())

        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.remoteStatusUpdated(client)
        assertTrue(
            "Intermediate BUFFERING must not clear pendingPlay before post-load PAUSED arrives",
            rig.controller.isRemotePlaying()
        )

        rig.remoteState(client, MediaStatus.PLAYER_STATE_PLAYING)
        rig.remoteStatusUpdated(client)
        assertTrue(rig.controller.isRemotePlaying())

        rig.remoteState(client, MediaStatus.PLAYER_STATE_PAUSED)
        rig.remoteStatusUpdated(client)
        assertFalse(rig.controller.isRemotePlaying())
    }

    @Test
    fun postLoadReceiverIdleErrorClearsCastAndResumesLocalPhonePlayback() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        doAnswer { null }.`when`(client).play()
        val firstLoad = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)

        rig.completeLoad(firstLoad, success = true)
        assertTrue(rig.controller.isCasting())
        assertTrue(rig.controller.isRemotePlaying())
        assertFalse(rig.phonePlaying)

        val status = client.mediaStatus!!
        `when`(status.idleReason).thenReturn(MediaStatus.IDLE_REASON_ERROR)
        rig.remoteState(client, MediaStatus.PLAYER_STATE_IDLE)
        rig.remoteStatusUpdated(client)

        assertFalse("Error idle state must clear active Cast media", rig.controller.isCasting())
        assertFalse(rig.controller.isRemotePlaying())
        assertTrue("Local phone playback must resume when receiver errors after load", rig.phonePlaying)
        assertEquals(listOf("Could not play this station on the Cast device."), rig.messages)

        `when`(status.idleReason).thenReturn(MediaStatus.IDLE_REASON_NONE)
        val secondLoad = rig.loadResult(client)
        rig.select(station("two", "https://radio.test/two.mp3"))
        rig.controller.onExplicitStationSelection("two")
        rig.controller.onLocalPlaybackChanged()
        assertEquals("https://radio.test/two.mp3", rig.loadRequest(client).mediaInfo!!.contentId)
        rig.completeLoad(secondLoad, success = true)
        assertTrue(rig.controller.isCasting())
        assertTrue(rig.controller.isRemotePlaying())
    }
}
