package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import net.mspanc.twinsenradio.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Keep AndroidX router statics in this fixture's platform-service sandbox.
@Config(application = Application::class, sdk = [28],
    instrumentedPackages = ["androidx.mediarouter"])
class PhoneCastLegacyRouteVolumeTest {
    @org.junit.Before fun freshPublicRouter() {
        // AndroidX's singleton can outlive Robolectric's platform service reset between tests.
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(Context.MEDIA_ROUTER_SERVICE)
        MediaRouter.resetGlobalRouter()
    }

    @org.junit.After fun releasePublicRouter() { MediaRouter.resetGlobalRouter() }

    private class Provider(context: Context) : MediaRouteProvider(context) {
        override fun onCreateRouteController(routeId: String): RouteController = object : RouteController() {}
    }

    @Test fun ordinaryCastButtonShowsActualReceiver16InsteadOfAggregateZero() {
        val activityController = Robolectric.buildActivity(FragmentActivity::class.java)
        val activity = activityController.get()
        activity.setTheme(R.style.Theme_StreamingRadioPhone)
        activityController.create().start().resume().visible()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val router = MediaRouter.getInstance(activity)
        val filter = IntentFilter().apply { addCategory("test.OWNED_CAST") }
        val selector = MediaRouteSelector.Builder().addControlCategory("test.OWNED_CAST").build()
        val provider = Provider(activity)
        val callback = object : MediaRouter.Callback() {}
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        router.addProvider(provider)
        fun descriptor(id: String, volume: Int) = MediaRouteDescriptor.Builder(id, id)
            .addControlFilter(filter)
            .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
            .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(50).setVolume(volume)
            .setConnectionState(MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED)
        provider.descriptor = MediaRouteProviderDescriptor.Builder()
            .addRoute(descriptor("member", 16).build())
            .addRoute(descriptor("group", 0).addGroupMemberId("member").build()).build()
        shadowOf(Looper.getMainLooper()).idle()
        val route = router.routes.single { it.name == "group" }
        route.select()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Fixture must select an ordinary nondefault remote route", router.selectedRoute.isDefault)
        assertEquals(0, router.selectedRoute.volume)
        assertEquals(50, router.selectedRoute.volumeMax)

        val cast = mock(CastSession::class.java)
        val castContext = mock(CastContext::class.java)
        val manager = mock(SessionManager::class.java)
        val remote = mock(RemoteMediaClient::class.java)
        val info = mock(MediaInfo::class.java)
        val status = mock(MediaStatus::class.java)
        `when`(status.playerState).thenReturn(MediaStatus.PLAYER_STATE_PLAYING)
        `when`(remote.mediaStatus).thenReturn(status)
        `when`(castContext.sessionManager).thenReturn(manager)
        `when`(manager.currentCastSession).thenReturn(cast)
        `when`(cast.isConnected).thenReturn(true)
        `when`(cast.volume).thenReturn(0.32)
        `when`(cast.remoteMediaClient).thenReturn(remote)
        `when`(remote.mediaInfo).thenReturn(info)
        `when`(info.customData).thenReturn(JSONObject().put("app", PhoneCastPolicy.OWNER_MARKER))
        mockStatic(CastContext::class.java).use { sdk ->
            sdk.`when`<CastContext> { CastContext.getSharedInstance(any(Context::class.java)) }
                .thenReturn(castContext)
            mockStatic(CastButtonFactory::class.java).use { factory ->
                factory.`when`<Unit> {
                    CastButtonFactory.setUpMediaRouteButton(any(Context::class.java), any())
                }.thenAnswer { call ->
                    call.getArgument<androidx.mediarouter.app.MediaRouteButton>(1).routeSelector = selector
                    null
                }
                val controller = PhoneCastController(activity, { null }, { null }, {}, {})
                val button = controller.createButton(root)
                assertTrue("Ordinary public button click must open its dialog", button.performClick())
                activity.supportFragmentManager.executePendingTransactions()
                shadowOf(Looper.getMainLooper()).idle()
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<MediaRouteControllerDialogFragment>().single()
                val dialog = fragment.dialog!!
                assertTrue("Real AndroidX controller dialog must be showing", dialog.isShowing)
                val slider = dialog.window!!.decorView.findViewWithTag<SeekBar>("cast_receiver_volume")
                assertNotNull("Controller must expose its actual volume slider", slider)
                assertTrue("Controller slider must be visibly shown", slider.isShown)
                assertEquals(View.VISIBLE, slider.visibility)
                assertEquals(50, slider.max)
                assertEquals("Visible slider must match CastSession 0.32, not public aggregate 0",
                    16, slider.progress)
                verify(cast, never()).setVolume(org.mockito.ArgumentMatchers.anyDouble())
                val volumeEvents = org.mockito.ArgumentCaptor.forClass(com.google.android.gms.cast.Cast.Listener::class.java)
                verify(cast).addCastListener(volumeEvents.capture())
                val mediaEvents = org.mockito.ArgumentCaptor.forClass(RemoteMediaClient.Callback::class.java)
                verify(remote).registerCallback(mediaEvents.capture())
                `when`(cast.volume).thenReturn(0.58)
                volumeEvents.value.onVolumeChanged()
                assertEquals(29, slider.progress)
                verify(cast, never()).setVolume(org.mockito.ArgumentMatchers.anyDouble())
                dialog.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
                verify(cast).setVolume(0.60)
                `when`(info.customData).thenReturn(JSONObject().put("app", "foreign-sender"))
                mediaEvents.value.onStatusUpdated()
                assertFalse("Ownership changes disable the visible slider", slider.isEnabled)
                clearInvocations(cast)
                dialog.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
                verify(cast, never()).setVolume(org.mockito.ArgumentMatchers.anyDouble())
                `when`(info.customData).thenReturn(JSONObject().put("app", PhoneCastPolicy.OWNER_MARKER))
                `when`(manager.currentCastSession).thenReturn(mock(CastSession::class.java))
                mediaEvents.value.onStatusUpdated()
                assertFalse("A stale dialog cannot control a replacement session", slider.isEnabled)
                dialog.dismiss()
                verify(cast).removeCastListener(volumeEvents.value)
                verify(remote).unregisterCallback(mediaEvents.value)
            }
        }
        router.removeCallback(callback)
        router.removeProvider(provider)
        activityController.pause().stop().destroy()
    }
}
