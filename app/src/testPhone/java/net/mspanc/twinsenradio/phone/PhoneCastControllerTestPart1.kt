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
class PhoneCastControllerTestPart1 {
    @Test
    fun foreignCurrentSessionDoesNotTakeOverOrLoadPhoneStation() {
        val rig = Rig(station = station("local", STREAM))
        val foreignClient = rig.remoteClient()
        `when`(foreignClient.mediaInfo).thenReturn(remoteInfo(STREAM, "another-app", "foreign"))
        val session = rig.session(foreignClient)
        rig.start(currentSession = session)

        assertFalse(rig.controller.isCasting())
        verify(foreignClient, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verifyNoInteractions(rig.player)
    }

    @Test
    fun selectedReceiverLoadsLiveStationAndPausesOnlyAfterMatchingSuccess() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")

        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        rig.confirmEmptyReceiver(client)

        val request = rig.loadRequest(client)
        val info = request.mediaInfo
        assertEquals(STREAM, info!!.contentId)
        assertEquals(MediaInfo.STREAM_TYPE_LIVE, info.streamType)
        assertEquals("audio/mpeg", info.contentType)
        assertEquals(PhoneCastPolicy.OWNER_MARKER, info.customData!!.getString("app"))
        assertEquals("one", info.customData!!.getString("stationId"))

        rig.completeLoad(pending, success = true)
        assertTrue(rig.controller.isCasting())
        verify(rig.player).pause()
    }

    @Test
    fun failedInitialLoadKeepsPhonePlaybackRunning() {
        val rig = Rig(station = station("one", STREAM), localPlaying = true)
        val client = rig.remoteClient()
        val pending = rig.loadResult(client)
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "session")
        rig.confirmEmptyReceiver(client)
        rig.completeLoad(pending, success = false)

        assertFalse(rig.controller.isCasting())
        verify(rig.player, never()).pause()
        assertEquals(listOf("Could not start this station on the Cast device."), rig.messages)
    }

    @Test
    fun passiveResumePreservesOwnedMediaAndExplicitStationChangeLoads() {
        val rig = Rig(station = station("new-station", STREAM))
        val client = rig.remoteClient(initiallyPlaying = true)
        val nextLoad = rig.loadResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))

        rig.completeStatus(client, success = true, hasMediaSession = true)
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        assertTrue(rig.controller.isCasting())

        rig.controller.onExplicitStationSelection("new-station")
        rig.controller.onLocalPlaybackChanged()
        verify(client).load(Mockito.any(MediaLoadRequestData::class.java))
        assertEquals("new-station", rig.loadRequest(client).mediaInfo!!.customData!!.getString("stationId"))
        rig.completeLoad(nextLoad, success = true)
        val secondLoad = rig.loadResult(client)
        rig.controller.onExplicitStationSelection("another-station")
        rig.select(station("another-station", "https://radio.test/another.mp3"))
        rig.controller.onLocalPlaybackChanged()
        assertEquals("https://radio.test/another.mp3", rig.loadRequest(client).mediaInfo!!.contentId)
        rig.completeLoad(secondLoad, success = true)
    }

    @Test
    fun joiningPlayingOwnedMediaPausesLocalAudioWithoutLoading() {
        val rig = Rig(station = station("local", STREAM), localPlaying = true)
        val client = rig.remoteClient(initiallyPlaying = true)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "remote")
        )
        rig.start()
        rig.listener().onSessionStarted(rig.session(client), "selected")
        rig.completeStatus(client, success = true, hasMediaSession = true)

        assertFalse(rig.phonePlaying)
        verify(rig.player).pause()
        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
    }

    @Test
    fun failedReplacementStopsOldMediaThenResumesOnlyMatchingCurrentPhoneStream() {
        val station = station("new-station", STREAM)
        val rig = Rig(station = station, localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        val loadPending = rig.loadResult(client)
        val stopPending = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.completeLoad(loadPending, success = false)

        verify(client).stop()
        verify(rig.player, never()).play()
        rig.completeStop(stopPending, success = true)
        verify(rig.player).play()
    }

    @Test
    fun failedRemoteStopDoesNotResumePhonePlayback() {
        val rig = Rig(station = station("new-station", STREAM), localPlaying = false)
        val client = rig.remoteClient(initiallyPlaying = true)
        val loadPending = rig.loadResult(client)
        val stopPending = rig.stopResult(client)
        `when`(client.mediaInfo).thenReturn(
            remoteInfo(STREAM, PhoneCastPolicy.OWNER_MARKER, "old-station")
        )
        rig.start(currentSession = rig.session(client))
        rig.selectStationOnCast(client, "new-station")
        rig.completeLoad(loadPending, success = false)

        rig.completeStop(stopPending, success = false)

        verify(rig.player, never()).play()
        assertFalse(rig.phonePlaying)
        assertTrue("failed STOP must not claim Cast stopped", rig.messages.last().startsWith("Could not confirm"))
    }
}
