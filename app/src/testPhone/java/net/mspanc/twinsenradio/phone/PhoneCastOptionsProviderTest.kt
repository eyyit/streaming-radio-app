package net.mspanc.twinsenradio.phone

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.mediarouter.media.MediaTransferReceiver
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhoneCastOptionsProviderTest {
    @Test
    fun mergedPhoneManifestEnablesNativeRemoteRouteRegistration() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = try {
            context.packageManager.getReceiverInfo(
                ComponentName(context, MediaTransferReceiver::class.java), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        assertNotNull("Android needs the remote-route integration declaration", receiver)
        assertTrue(checkNotNull(receiver).exported)
    }

    @Test
    fun castingUsesOneAppOwnedMediaSessionInsteadOfTheBrokenAggregate() {
        val options = PhoneCastOptionsProvider()
            .getCastOptions(RuntimeEnvironment.getApplication())
        val media = options.castMediaOptions

        assertNotNull("Cast needs a remote media session for system volume", media)
        assertFalse(checkNotNull(media).mediaSessionEnabled)
        assertFalse(options.showSystemOutputSwitcherOnCastIconClick)
        assertNull("The app supplies the matching native-session notification", media.notificationOptions)
    }
}
