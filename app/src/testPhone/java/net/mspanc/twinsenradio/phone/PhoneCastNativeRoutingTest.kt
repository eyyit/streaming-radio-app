package net.mspanc.twinsenradio.phone

import android.app.Application
import android.media.AudioManager
import android.os.Build
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhoneCastNativeRoutingTest {
    private class Rig {
        val cast = mock(CastSession::class.java)
        var owned = true
        var playing = true
        var actual = 0.32
        var muted = false
        var toggles = 0
        var owner = PhoneCastPolicy.OWNER_MARKER
        val native = PhoneCastNativeSession(RuntimeEnvironment.getApplication(), { owned },
            { playing }, { toggles++; playing = !playing }, { cast }, { "physical-session-id" })
        init {
            `when`(cast.isConnected).thenReturn(true)
            `when`(cast.volume).thenAnswer { actual }
            `when`(cast.isMute).thenAnswer { muted }
            val remote = mock(RemoteMediaClient::class.java)
            val info = mock(MediaInfo::class.java)
            `when`(cast.remoteMediaClient).thenReturn(remote)
            `when`(remote.mediaInfo).thenReturn(info)
            `when`(info.customData).thenAnswer { JSONObject().put("app", owner) }
        }
        fun listener(): Cast.Listener {
            val captured = ArgumentCaptor.forClass(Cast.Listener::class.java)
            verify(cast).addCastListener(captured.capture())
            return captured.value
        }
    }

    @Config(sdk = [28, 33])
    @Test fun initialDevice32PercentPublishes16WithoutWritingReceiver() {
        val rig = Rig(); rig.native.sync()
        // The MR2 group reported 0/50 while its member reported 16/50.
        val platform = checkNotNull(rig.native.volume)
        assertEquals(16, platform.currentVolume)
        assertEquals(50, platform.maxVolume)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals("physical-session-id", platform.volumeControlId)
        }
        assertTrue(rig.native.session!!.isActive)
        verify(rig.cast, never()).setVolume(anyDouble())
        verify(rig.cast, never()).setMute(anyBoolean())
    }

    @Test fun nativeRaiseUsesActualReceiverBaselineRatherThanZeroAggregate() {
        val rig = Rig(); rig.native.sync()
        rig.native.volume!!.onAdjustVolume(AudioManager.ADJUST_RAISE)
        verify(rig.cast).setVolume(0.34)
    }

    @Test fun twoRaisesBeforeReceiverAcknowledgesAccumulateFrom32To36() {
        val rig = Rig(); rig.native.sync()
        rig.native.adjust(1); rig.native.adjust(1)
        verify(rig.cast).setVolume(0.34)
        verify(rig.cast).setVolume(0.36000000000000004)
    }

    @Test fun externalVolumeEventUpdatesSameNativeSessionAndProvider() {
        val rig = Rig(); rig.native.sync()
        val session = rig.native.session; val provider = rig.native.volume
        rig.actual = 0.58; rig.listener().onVolumeChanged()
        assertSame(session, rig.native.session); assertSame(provider, rig.native.volume)
        assertEquals(29, rig.native.volume!!.currentVolume)
        verify(rig.cast, never()).setVolume(anyDouble())
    }

    @Test fun muteAndUnmutePreserveReceiverLevel() {
        val rig = Rig(); rig.native.sync()
        rig.native.adjust(AudioManager.ADJUST_MUTE)
        verify(rig.cast).setMute(true)
        rig.muted = true; rig.listener().onVolumeChanged()
        assertEquals(0, rig.native.volume!!.currentVolume)
        rig.native.adjust(AudioManager.ADJUST_UNMUTE)
        verify(rig.cast).setMute(false)
        verify(rig.cast, never()).setVolume(anyDouble())
    }

    @Test fun foreignOwnershipClosesSessionAndRejectsLateVolumeAndTransport() {
        val rig = Rig(); rig.native.sync(); val provider = rig.native.volume!!
        rig.owned = false; rig.native.sync()
        assertNull(rig.native.session)
        provider.onSetVolumeTo(17); provider.onAdjustVolume(1); rig.native.action("pause")
        assertEquals(0, rig.toggles)
        verify(rig.cast, never()).setVolume(anyDouble())
    }

    @Test fun changedReceiverOwnerRejectsCommandsBeforeControllerCallbackArrives() {
        val rig = Rig(); rig.native.sync()
        rig.owner = "another-sender"
        rig.native.adjust(1); rig.native.action("pause"); rig.native.sync()
        verify(rig.cast, never()).setVolume(anyDouble())
        assertEquals(0, rig.toggles)
        assertNull(rig.native.session)
    }

    @Test fun pauseAndPlayRemainAvailableWithoutAnyActivityListener() {
        val rig = Rig(); rig.native.sync()
        rig.native.action("pause"); assertFalse(rig.playing)
        rig.native.action("play"); assertTrue(rig.playing)
        assertEquals(2, rig.toggles)
    }

    @Test fun repeatedSyncCreatesOnlyOneSessionAndListener() {
        val rig = Rig(); rig.native.sync(); val session = rig.native.session
        repeat(3) { rig.native.sync() }
        assertSame(session, rig.native.session)
        rig.listener()
    }

    @Test fun absoluteSliderUsesDeviceScaleAndDoesNotChangePhoneVolume() {
        val rig = Rig(); rig.native.sync()
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val phone = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        rig.native.volume!!.onSetVolumeTo(17)
        verify(rig.cast).setVolume(0.34)
        assertEquals(phone, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
