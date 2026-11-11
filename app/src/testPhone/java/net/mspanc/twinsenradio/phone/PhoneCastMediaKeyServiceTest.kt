package net.mspanc.twinsenradio.phone

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.playback.KeepCurrentStreamPlayer
import net.mspanc.twinsenradio.playback.RadioService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real service callback's documented consume/fall-through contract. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhoneCastMediaKeyServiceTest {
    private class Rig : AutoCloseable {
        val context: Context = RuntimeEnvironment.getApplication()
        val sdk = mockStatic(CastContext::class.java)
        val castContext = mock(CastContext::class.java)
        val manager = mock(SessionManager::class.java)
        val cast = mock(CastSession::class.java)
        val remote = mock(RemoteMediaClient::class.java)
        var state = MediaStatus.PLAYER_STATE_PLAYING
        var owner = PhoneCastPolicy.OWNER_MARKER
        val service = Robolectric.buildService(RadioService::class.java).get()
        val app = PhoneApp()
        val player: ExoPlayer
        val callback: MediaLibrarySession.Callback
        val session: MediaLibrarySession
        val routing: PhoneCastRouting
        val controllerInfo: MediaSession.ControllerInfo

        init {
            sdk.`when`<CastContext> { CastContext.getSharedInstance(any(Context::class.java)) }
                .thenReturn(castContext)
            `when`(castContext.sessionManager).thenReturn(manager)
            // Start with no receiver; inject a settled owned session below.
            `when`(manager.currentCastSession).thenReturn(null)
            val appBase = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = app
            }
            android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext", Context::class.java).apply { isAccessible = true }
                .invoke(app, appBase)
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
                .set(service, appBase)
            Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }
                .set(service, app)
            routing = PhoneCastRouting::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context)
            set(PhoneCastRouting::class.java, null, "instance", routing)
            set(RadioService::class.java, service, "prefs", Prefs(context))
            player = RadioService::class.java.getDeclaredMethod("buildPlayer")
                .apply { isAccessible = true }.invoke(service) as ExoPlayer
            player.setMediaItem(MediaItem.fromUri("file:///missing-offline-fixture.mp3"))
            set(RadioService::class.java, service, "player", player)
            callback = RadioService::class.java.declaredClasses.single {
                it.simpleName == "LibraryCallback"
            }.getDeclaredConstructor(RadioService::class.java).apply { isAccessible = true }
                .newInstance(service) as MediaLibrarySession.Callback
            session = MediaLibrarySession.Builder(context, KeepCurrentStreamPlayer(player), callback).build()
            set(RadioService::class.java, service, "session", session)
            controllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                context.packageName, android.os.Process.myPid(), android.os.Process.myUid(),
                0, 0, true, Bundle.EMPTY, true)
            val status = mock(MediaStatus::class.java)
            val info = mock(MediaInfo::class.java)
            `when`(status.playerState).thenAnswer { state }
            `when`(remote.mediaStatus).thenReturn(status)
            `when`(remote.mediaInfo).thenReturn(info)
            `when`(info.customData).thenAnswer { JSONObject().put("app", owner) }
            `when`(cast.remoteMediaClient).thenReturn(remote)
            `when`(cast.isConnected).thenReturn(true)
            doAnswer { state = MediaStatus.PLAYER_STATE_PAUSED; null }.`when`(remote).pause()
            doAnswer { state = MediaStatus.PLAYER_STATE_PLAYING; null }.`when`(remote).play()
            val controller = routing.controller
            set(PhoneCastController::class.java, controller, "remoteClient", remote)
            set(PhoneCastController::class.java, controller, "loadedUri", "https://radio.example/live")
            set(PhoneCastController::class.java, controller, "mayLoadForSession", true)
            val native = PhoneCastNativeSession(context, { controller.isCasting() },
                { controller.isRemotePlaying() }, {
                    if (state == MediaStatus.PLAYER_STATE_PLAYING) remote.pause() else remote.play()
                }, { cast }, { null })
            native.sync()
            set(PhoneCastRouting::class.java, routing, "native", native)
            `when`(manager.currentCastSession).thenReturn(cast)
        }

        fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
                KeyEvent(100L, 100L, action, code, repeat))

        fun dispatch(intent: Intent): Boolean {
            // Media3 1.11.0 stops default handling exactly when this real callback returns true.
            return callback.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun close() {
            session.release()
            (RadioService::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(service) as CoroutineScope).cancel()
            player.release()
            set(PhoneCastRouting::class.java, null, "instance", null)
            sdk.close()
        }

        private fun set(type: Class<*>, target: Any?, name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }

    @Test fun legacySystemPrivilegeRequiresVerifiedPackageUid(): Unit = Rig().use { rig ->
        val packageManager = org.robolectric.Shadows.shadowOf(rig.context.packageManager)
        fun install(name: String, uid: Int) {
            packageManager.installPackage(android.content.pm.PackageInfo().apply {
                packageName = name
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = name
                    this.uid = uid
                }
            })
        }
        install("test.system.media", 1000)
        install("test.unprivileged.media", 22001)
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(-1, 1000, android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        val verified = androidx.media.MediaSessionManager.RemoteUserInfo("test.system.media", -1, 1000)
        assertTrue("The actual compat checker must recognize the granted privilege",
            androidx.media.MediaSessionManager.getSessionManager(rig.context).isTrustedForMediaControl(verified))
        fun legacy(name: String, uid: Int) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            name, -1, uid, 0, 0, false, Bundle.EMPTY, true)
        val caller = legacy("test.system.media", 1000)
        assertFalse("Match Media3's actual legacy adapter trust flag", caller.isTrusted)
        assertTrue(rig.callback.onMediaButtonEvent(rig.session, caller,
            rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertEquals(MediaStatus.PLAYER_STATE_PAUSED, rig.state)
        rig.state = MediaStatus.PLAYER_STATE_PLAYING
        assertFalse("A privileged UID paired with a different package must fail closed",
            rig.callback.onMediaButtonEvent(rig.session, legacy("test.unprivileged.media", 1000),
                rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertFalse("A matching but unprivileged package must fail closed",
            rig.callback.onMediaButtonEvent(rig.session, legacy("test.unprivileged.media", 22001),
                rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, rig.state)
        verify(rig.remote, times(1)).pause()
    }

    @Test fun untrustedCallerCannotControlOwnedReceiver(): Unit = Rig().use { rig ->
        val stranger = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "foreign.app", 500, 12345, 0, 0, false, Bundle.EMPTY, true)
        assertFalse(rig.callback.onMediaButtonEvent(rig.session, stranger,
            rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, rig.state)
        verify(rig.remote, never()).pause()
    }

    @Test fun playPause85AndExplicitPauseAreIdempotentAcrossUpAndRepeat(): Unit = Rig().use { rig ->
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(MediaStatus.PLAYER_STATE_PAUSED, rig.state)
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP)))
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat = 1)))
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        verify(rig.remote, times(1)).pause()
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertTrue(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        verify(rig.remote, times(1)).play()
        assertFalse(rig.player.playWhenReady)
    }

    @Test fun lateDisconnectedOrUnknownKeyDoesNotControlReceiver(): Unit = Rig().use { rig ->
        assertFalse(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_NEXT)))
        `when`(rig.cast.isConnected).thenReturn(false)
        assertFalse(rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, rig.state)
        verify(rig.remote, never()).pause()
    }

    @Test fun ownedPauseReachesReceiverThroughActualServiceCallback(): Unit = Rig().use { rig ->
        val consumed = rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals("General pause must pause the owned receiver", MediaStatus.PLAYER_STATE_PAUSED, rig.state)
        assertTrue("Owned Cast pause must stop Media3's default handling", consumed)
        assertFalse("General pause must leave the phone paused", rig.player.playWhenReady)
    }

    @Test fun ownedPlayIsConsumedBeforeLocalFallbackCanStartPhone(): Unit = Rig().use { rig ->
        rig.state = MediaStatus.PLAYER_STATE_PAUSED
        assertTrue("Owned Cast PLAY must stop Media3's default handling",
            rig.dispatch(rig.event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertFalse("Owned Cast PLAY must not reach Media3's local-player fallback", rig.player.playWhenReady)
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, rig.state)
    }

    @Test fun ownedUpIsConsumedWithoutTransportCommands(): Unit = Rig().use { rig ->
        val up = rig.event(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP)
        assertTrue("Owned Cast UP must be consumed by the service callback",
            rig.callback.onMediaButtonEvent(rig.session, rig.controllerInfo, up))
        verify(rig.remote, never()).play()
        verify(rig.remote, never()).pause()
        assertFalse(rig.player.playWhenReady)
    }

    @Test fun ownedRepeatedDownIsConsumedWithoutTransportCommands(): Unit = Rig().use { rig ->
        val repeat = rig.event(KeyEvent.KEYCODE_MEDIA_PLAY, repeat = 1)
        assertTrue("Owned Cast repeat must be consumed by the service callback",
            rig.callback.onMediaButtonEvent(rig.session, rig.controllerInfo, repeat))
        verify(rig.remote, never()).play()
        verify(rig.remote, never()).pause()
        assertFalse(rig.player.playWhenReady)
    }

    @Test fun noCastLeavesDefaultLocalHandlingAvailable(): Unit = Rig().use { rig ->
        val field = PhoneCastController::class.java.getDeclaredField("loadedUri").apply { isAccessible = true }
        field.set(rig.routing.controller, null)
        val intent = rig.event(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertFalse(rig.callback.onMediaButtonEvent(rig.session, rig.controllerInfo, intent))
        assertFalse(rig.dispatch(intent))
        verify(rig.remote, never()).play()
        verify(rig.remote, never()).pause()
    }

    @Test fun foreignReceiverFallsThroughWithoutRemoteCommand(): Unit = Rig().use { rig ->
        rig.owner = "unrelated-sender"
        val intent = rig.event(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertFalse(rig.callback.onMediaButtonEvent(rig.session, rig.controllerInfo, intent))
        assertFalse(rig.dispatch(intent))
        verify(rig.remote, never()).play()
        verify(rig.remote, never()).pause()
    }
}
