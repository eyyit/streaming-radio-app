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
class PhoneCastControllerTestPart2 {
    @Test
    fun staleLoadCompletionAfterStopCannotPausePhoneAndRemoteCallbackIsRemoved() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        val listener = rig.listener()
        listener.onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        val callback = rig.loadCallback(pending)

        rig.activityStopped()
        callback.onResult(rig.result(success = true))

        assertFalse(rig.controller.isCasting())
        verify(rig.player, never()).pause()
        verify(client).unregisterCallback(Mockito.any(RemoteMediaClient.Callback::class.java))
        verify(rig.manager).removeSessionManagerListener(listener, CastSession::class.java)
    }

    @Test
    fun inFlightAutoplayMustNotOverlapPhoneAudioAfterActivityStops() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.loadRequest(client)

        rig.activityStopped()
        rig.completeLoad(pending, success = true)

        assertFalse(
            "A receiver accepting an in-flight autoplay load must not leave phone audio playing too",
            rig.phonePlaying && rig.remoteIsPlaying(client)
        )
    }

    @Test
    fun failedBackgroundLoadLeavesPhoneAudioPlaying() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.loadRequest(client)

        rig.activityStopped()
        rig.completeLoad(pending, success = false)

        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
    }

    @Test
    fun sessionEndClearsOwnedRemoteStateAndUnregistersStatusCallback() {
        val rig = Rig(station = station("one", STREAM))
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        val listener = rig.listener()
        val session = rig.session(client)
        listener.onSessionStarted(session, "session")
        rig.confirmEmptyReceiver(client)
        rig.completeLoad(pending, success = true)
        assertTrue(rig.controller.isCasting())

        listener.onSessionEnded(session, 0)

        assertFalse(rig.controller.isCasting())
        verify(client).unregisterCallback(Mockito.any(RemoteMediaClient.Callback::class.java))
    }

    @Test
    fun durableLeaseHandsOffAfterUiControllerRelease() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true, durable = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        assertFalse(rig.loadRequest(client).autoplay == true)
        rig.activityStopped()
        rig.completeLoad(pending, success = true)
        assertFalse(rig.phonePlaying)
        assertTrue(rig.remoteIsPlaying(client))
        verify(rig.player, never()).pause()
        val order = Mockito.inOrder(rig.durablePlayer, client)
        order.verify(rig.durablePlayer).pause()
        order.verify(client).play()
        verify(rig.durablePlayer).release()
    }

    @Test
    fun failedBackgroundLoadDoesNotPauseDurablePhoneBoundary() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true, durable = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.activityStopped()
        rig.completeLoad(pending, success = false)
        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
        verify(rig.durablePlayer, never()).pause()
        verify(rig.durablePlayer).release()
    }

    @Test
    fun userPhonePauseDuringPendingLoadDoesNotStartReceiver() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.player.pause()
        rig.completeLoad(pending, success = true)
        assertFalse(rig.remoteIsPlaying(client))
    }

    @Test
    fun userRemotePauseDuringReplacementDoesNotRestartReceiver() {
        val rig = Rig(station = station("new-station", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        val pending = rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.controller.toggleRemotePlayback()
        rig.completeLoad(pending, success = true)
        assertFalse(rig.remoteIsPlaying(client))
    }

    @Test
    fun stoppingActivityDoesNotStopEstablishedCast() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.completeLoad(pending, success = true)
        rig.activityStopped()
        assertTrue(rig.remoteIsPlaying(client))
        verify(client, never()).stop()
    }

    @Test
    fun supersededLeaseCannotPauseOrStartReceiverWhenConnectionArrives() {
        val future = com.google.common.util.concurrent.SettableFuture.create<MediaController>()
        val lease = PhoneCastHandoffLease(future, java.util.concurrent.Executor { it.run() })
        val player = mock<MediaController>()
        var remoteStarted = false
        lease.confirm(STREAM, "one") { remoteStarted = true }
        lease.close()
        lease.close()
        future.set(player)
        assertFalse(remoteStarted)
        verify(player, never()).pause()
        assertTrue(future.isCancelled)
    }

    @Test
    fun failedBackgroundReplacementStopsRemoteBeforeDurablePhoneResume() {
        val rig = Rig(station = station("new-station", STREAM), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.activityStopped()
        rig.completeLoad(load, success = false)
        verify(client).stop()
        assertFalse(rig.phonePlaying)
        rig.completeStop(stop, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
        verify(rig.player, never()).play()
        verify(rig.durablePlayer).release()
    }

    @Test
    fun failedBackgroundReplacementStopNeverResumesDurablePhone() {
        val rig = Rig(station = station("new-station", STREAM), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.activityStopped()
        rig.completeLoad(load, success = false)
        rig.completeStop(stop, success = false)
        assertFalse(rig.phonePlaying)
        verify(rig.durablePlayer, never()).play()
        verify(rig.durablePlayer).release()
    }

    @Test
    fun stoppingActivityWhileFallbackStopIsPendingKeepsDurableResume() {
        val rig = Rig(station = station("new-station", STREAM), durable = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.completeLoad(load, success = false)
        rig.activityStopped()
        assertFalse(rig.phonePlaying)
        rig.completeStop(stop, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse(rig.remoteIsPlaying(client))
        verify(rig.player, never()).play()
        verify(rig.durablePlayer).release()
    }

    @Test
    fun userPauseDuringFallbackStopDoesNotRestartPhone() {
        val rig = Rig(station = station("new-station", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val load = rig.loadResult(client)
        val stop = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.completeLoad(load, success = false)
        rig.controller.toggleRemotePlayback()
        rig.completeStop(stop, success = true)
        assertFalse("user pause during fallback must remain paused", rig.phonePlaying)
        verify(rig.player, never()).play()
    }

    @Test
    fun ignoredPhonePauseCannotStartRemoteAudio() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        doAnswer { null }.`when`(rig.player).pause()
        val client = rig.remoteClient()
        val load = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.completeLoad(load, success = true)
        assertTrue(rig.phonePlaying)
        assertFalse("remote must wait for an accepted phone pause", rig.remoteIsPlaying(client))
    }
}
