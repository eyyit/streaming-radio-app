package net.mspanc.twinsenradio.phone

import android.os.Bundle
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.ui.NowPlayingActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhonePlayerRoutingTest {
    @Test fun legacyPlayerEntryRoutesToPhonePlayerAndFinishes() = verifyRoute(null)

    @Test fun recreatedLegacyPlayerEntryStillRoutesToPhonePlayer() = verifyRoute(Bundle())

    private fun verifyRoute(savedState: Bundle?) {
        val host = Robolectric.buildActivity(NowPlayingActivity::class.java)
        host.get().setTheme(R.style.Theme_TwinsenRadio)
        try {
            host.create(savedState)
            val activity = host.get()
            val destination = shadowOf(activity).nextStartedActivity
            assertNotNull("Existing mini-player must reach the phone implementation", destination)
            assertEquals(PhonePlayerActivity::class.java.name, destination.component?.className)
            assertEquals(activity.packageName, destination.component?.packageName)
            assertTrue("Legacy player must not remain underneath the new view", activity.isFinishing)
            assertNull("Bridge must launch only once", shadowOf(activity).nextStartedActivity)
        } finally {
            host.destroy()
        }
    }
}
