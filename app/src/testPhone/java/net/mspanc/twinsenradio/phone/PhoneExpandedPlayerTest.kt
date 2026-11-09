package net.mspanc.twinsenradio.phone

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneExpandedPlayerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun offlineEmptyLibrary() {
        Prefs(context).onlineArtworkEnabled = false
        context.getSharedPreferences("phone_player", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun phoneStartsWithoutBundledStations() {
        val catalog = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        assertEquals("Phone app must start with user imports only", 0, org.json.JSONArray(catalog).length())
    }

    @Test fun secondaryActionsStayAboveSeparateReachableTransport() = withPlayer { activity ->
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val screen = root.getChildAt(0)
        val density = activity.resources.displayMetrics.density
        screen.measure(View.MeasureSpec.makeMeasureSpec((390*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((720*density).toInt(), View.MeasureSpec.EXACTLY))
        screen.layout(0,0,screen.measuredWidth,screen.measuredHeight)
        val actions = screen.findViewWithTag<ViewGroup>("phone_secondary_actions")
        val transport = screen.findViewWithTag<ViewGroup>("phone_primary_transport")
        assertTrue("Secondary actions must precede main transport", actions.bottom <= transport.top)
        assertEquals(3, actions.childCount)
        assertEquals(3, transport.childCount)
        assertTrue("Fixed transport must fit the viewport", transport.bottom <= screen.height)
        descendants(transport).filterIsInstance<ImageButton>().forEach {
            assertTrue("Primary touch width", it.width >= 48*density)
            assertTrue("Primary touch height", it.height >= 48*density)
        }
        val cast = descendants(screen).first { it.contentDescription == "Cast unavailable" }
        assertFalse("Do not advertise unimplemented Cast", cast.isEnabled)
        val shuffle = descendants(actions).filterIsInstance<ImageButton>().first {
            it.contentDescription == "Shuffle stations unavailable" }
        assertFalse("Do not silently flip an ineffective shuffle preference", shuffle.isEnabled)
    }

    @Test fun hideArtworkChoiceSurvivesActivityRecreationAndCanBeReversed() {
        withPlayer { activity ->
            val hide = descendants(activity.findViewById(android.R.id.content)).first { it.contentDescription == "Hide artwork" }
            hide.performClick()
            assertEquals("Show artwork", hide.contentDescription)
        }
        withPlayer { activity ->
            val views = descendants(activity.findViewById(android.R.id.content))
            assertEquals(View.GONE, views.first { it.contentDescription == "Current track artwork or station image" }.visibility)
            views.first { it.contentDescription == "Show artwork" }.performClick()
            assertEquals(View.VISIBLE, views.first { it.contentDescription == "Current track artwork or station image" }.visibility)
        }
    }

    @Test fun emptyLibraryCannotStartPlaybackOrFavoriteNothing() = withPlayer { activity ->
        val views = descendants(activity.findViewById(android.R.id.content))
        assertFalse(views.first { it.contentDescription == "Play" }.isEnabled)
        assertFalse(views.first { it.contentDescription == "Add station to Favorites" }.isEnabled)
        assertFalse(views.first { it.contentDescription == "Refresh track artwork" }.isEnabled)
        views.first { it.contentDescription == "Restore station library" }.performClick()
        assertTrue(activity.isFinishing)
    }

    private fun withPlayer(check: (PhonePlayerActivity) -> Unit) {
        val host = Robolectric.buildActivity(PhonePlayerActivity::class.java)
        host.get().setTheme(R.style.Theme_TwinsenRadio)
        try { host.create(); check(host.get()) } finally {
            if (host.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) host.destroy()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
