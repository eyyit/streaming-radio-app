package net.mspanc.twinsenradio.phone

import android.app.Application
import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhoneCastNotificationTest {
    private class Rig : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val cast = mock(CastSession::class.java)
        val device = mock(CastDevice::class.java)
        val remote = mock(RemoteMediaClient::class.java)
        val info = mock(MediaInfo::class.java)
        val sessions = mockConstruction(MediaSession::class.java)
        var owner = PhoneCastPolicy.OWNER_MARKER
        var canDisconnect = true
        val endedSessions = mutableListOf<Boolean>()
        val native = PhoneCastNativeSession(
            context = context,
            ownsMedia = { true },
            playing = { true },
            toggle = {},
            current = { cast },
            routingId = { null },
            endSession = { endedSessions += it },
            canDisconnectAndKeepPlaying = { canDisconnect }
        )

        init {
            `when`(cast.isConnected).thenReturn(true)
            `when`(device.friendlyName).thenReturn("Kids Speaker")
            `when`(cast.castDevice).thenReturn(device)
            `when`(cast.remoteMediaClient).thenReturn(remote)
            `when`(remote.mediaInfo).thenReturn(info)
            `when`(info.customData).thenAnswer {
                JSONObject().put("app", owner).put("stationId", "station-id")
            }
        }

        fun session(): MediaSession = checkNotNull(native.session)

        fun metadata(): MediaMetadata {
            val value = ArgumentCaptor.forClass(MediaMetadata::class.java)
            verify(session()).setMetadata(value.capture())
            return value.value
        }

        fun playbackState(): PlaybackState {
            val value = ArgumentCaptor.forClass(PlaybackState::class.java)
            verify(session()).setPlaybackState(value.capture())
            return value.value
        }

        fun sessionActivity(): android.app.PendingIntent {
            val value = ArgumentCaptor.forClass(android.app.PendingIntent::class.java)
            verify(session()).setSessionActivity(value.capture())
            return value.value
        }

        override fun close() = sessions.close()
    }

    @Test
    fun notificationShowsReceiverNameAndPublishesItAsSessionSubtitle() {
        Rig().use { rig ->
            rig.native.sync()

            val notification = rig.native.notification()
            assertEquals("Kids Speaker", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(
                "Kids Speaker",
                rig.metadata().getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            )
        }
    }

    @Test
    fun displaySnapshotUsesOnlyOwnedRemoteStationAndReceiverMetadata() {
        Rig().use { rig ->
            val remoteMetadata = com.google.android.gms.cast.MediaMetadata(
                com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            ).apply {
                putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "Groove Salad")
            }
            `when`(rig.info.metadata).thenReturn(remoteMetadata)
            rig.native.sync()

            val snapshot = rig.native.displaySnapshot()
            assertEquals("Kids Speaker", snapshot?.receiverName)
            assertEquals("Groove Salad", snapshot?.title)
            assertEquals("station-id", snapshot?.stationId)
        }
    }

    @Test
    fun displaySnapshotIsUnavailableForForeignMedia() {
        Rig().use { rig ->
            rig.owner = "another-sender"
            rig.native.sync()

            assertEquals(null, rig.native.displaySnapshot())
        }
    }

    @Config(sdk = [28, 33])
    @Test
    fun notificationTapOpensExpandedPhonePlayer() {
        Rig().use { rig ->
            rig.native.sync()

            val tapIntent = rig.native.notification().contentIntent
            assertNotNull("The notification should open the expanded player", tapIntent)
            val target = shadowOf(checkNotNull(tapIntent)).savedIntent
            assertEquals(PhonePlayerActivity::class.java.name, target.component?.className)
            val sessionTarget = shadowOf(rig.sessionActivity()).savedIntent
            assertEquals(PhonePlayerActivity::class.java.name, sessionTarget.component?.className)
        }
    }

    @Config(sdk = [28, 33])
    @Test
    fun castNotificationExposesOnlyPauseRegardlessOfRouteCapability() {
        Rig().use { rig ->
            rig.native.sync()
            for (canDisconnect in listOf(true, false)) {
                rig.canDisconnect = canDisconnect
                val action = rig.native.notification().actions.single()
                assertEquals("Pause", action.title)
                val broadcast = shadowOf(action.actionIntent).savedIntent
                assertEquals("pause", broadcast.action)
                assertEquals(PhoneCastNotificationReceiver::class.java.name, broadcast.component?.className)
            }
            assertTrue(rig.playbackState().customActions.isNullOrEmpty())
        }
    }

    @Test
    fun staleDisconnectNeverEndsSessionAfterCapabilityIsLost() {
        Rig().use { rig ->
            rig.native.sync()
            rig.canDisconnect = false

            rig.native.action("disconnect")

            assertTrue(rig.endedSessions.isEmpty())
        }
    }

    @Test
    fun staleStopCastingDoesNotEndSessionAfterAnotherSenderJoins() {
        Rig().use { rig ->
            rig.canDisconnect = false
            rig.native.sync()
            rig.canDisconnect = true

            rig.native.action("stop_casting")

            assertTrue(rig.endedSessions.isEmpty())
        }
    }

    @Test
    fun stopCommandRetainsStopReceiverSemantics() {
        Rig().use { rig ->
            rig.native.sync()

            rig.native.action("stop")

            assertEquals(listOf(true), rig.endedSessions)
        }
    }

    @Test
    fun disconnectIsIgnoredAfterRemoteOwnershipChanges() {
        Rig().use { rig ->
            rig.native.sync()
            rig.owner = "another-sender"

            rig.native.action("disconnect")

            assertTrue(rig.endedSessions.isEmpty())
        }
    }
}
