package net.mspanc.twinsenradio.phone

import android.app.Activity
import android.app.Application
import androidx.media3.session.MediaController
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import net.mspanc.twinsenradio.data.Station
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class PhoneCastControllerSmokeTest {
    @Test
    fun foreignCurrentSessionDoesNotLoadOrTouchPhonePlayer() {
        val player = mock<MediaController>()
        val station = Station("local", "Local", "", STREAM)
        val client = mock<RemoteMediaClient>()
        val foreignMedia = MediaInfo.Builder(STREAM)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/mpeg")
            .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK))
            .setCustomData(JSONObject().put("app", "another-app").put("stationId", "foreign"))
            .build()
        Mockito.`when`(client.mediaInfo).thenReturn(foreignMedia)
        val session = mock<CastSession>().also { Mockito.`when`(it.remoteMediaClient).thenReturn(client) }
        val manager = mock<SessionManager>().also {
            Mockito.`when`(it.currentCastSession).thenReturn(session)
        }
        val context = mock<CastContext>().also { Mockito.`when`(it.sessionManager).thenReturn(manager) }
        val controller = PhoneCastController(
            mock<Activity>(), { player }, { station }, {}, {}
        ).also {
            val field = PhoneCastController::class.java.getDeclaredField("castContext\$delegate")
            field.isAccessible = true
            field.set(it, lazyOf(context))
        }

        controller.onStart()

        verify(client, never()).load(Mockito.any(MediaLoadRequestData::class.java))
        verifyNoInteractions(player)
        assertFalse(controller.isCasting())
        controller.onStop()
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private companion object {
        const val STREAM = "https://radio.test/live.mp3"
    }
}
