// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.debug

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import net.mspanc.twinsenradio.playback.RadioService

/**
 * Klient starego API MediaBrowserCompat - dokladnie tej sciezki uzywa Android Auto.
 * Istnieje tylko w wariancie debug; sluzy do sprawdzenia, czy drzewo przegladania
 * odpowiada, bez odpalania calego DHU.
 *
 *   adb shell am start -n net.mspanc.twinsenradio/.debug.BrowseTestActivity
 *   adb logcat -s BrowseTest
 */
@androidx.media3.common.util.UnstableApi
class BrowseTestActivity : AppCompatActivity() {

    private lateinit var browser: MediaBrowserCompat

    private val callback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val root = browser.root
            val extras = browser.extras
            Log.i(TAG, "POLACZONO. root='$root'")
            Log.i(
                TAG,
                "schemat: supported=${extras?.get("android.media.browse.CONTENT_STYLE_SUPPORTED")} " +
                    "browsable=${extras?.get("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT")} " +
                    "playable=${extras?.get("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT")}"
            )
            subscribe(root, depth = 0)
            testSearch("nowy swiat")
            testSearch("rock")
        }

        private fun testSearch(query: String) {
            browser.search(query, null, object : MediaBrowserCompat.SearchCallback() {
                override fun onSearchResult(
                    query: String,
                    extras: Bundle?,
                    items: MutableList<MediaBrowserCompat.MediaItem>
                ) {
                    Log.i(TAG, "szukaj '$query' -> ${items.size}: ${items.take(3).joinToString { it.description.title.toString() }}")
                }

                override fun onError(query: String, extras: Bundle?) {
                    Log.e(TAG, "szukaj '$query' -> blad")
                }
            })
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "ODRZUCONO POLACZENIE")
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "polaczenie zawieszone")
        }
    }

    private fun subscribe(parentId: String, depth: Int) {
        browser.subscribe(parentId, object : MediaBrowserCompat.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                val pad = "  ".repeat(depth)
                Log.i(TAG, "$pad$parentId -> ${children.size} pozycji")
                children.take(4).forEach { child ->
                    val d = child.description
                    Log.i(
                        TAG,
                        "$pad  id=${d.mediaId} title='${d.title}' subtitle='${d.subtitle}' " +
                            "icon=${d.iconUri} browsable=${child.isBrowsable} playable=${child.isPlayable}"
                    )
                    if (child.isBrowsable && depth < 1) {
                        subscribe(d.mediaId!!, depth + 1)
                    }
                }
            }

            override fun onError(parentId: String) {
                Log.e(TAG, "blad ladowania dzieci dla $parentId")
            }
        })
    }

    override fun onStart() {
        super.onStart()
        browser = MediaBrowserCompat(
            this,
            ComponentName(this, RadioService::class.java),
            callback,
            null
        )
        browser.connect()
    }

    override fun onStop() {
        if (this::browser.isInitialized) browser.disconnect()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    private companion object {
        const val TAG = "BrowseTest"
    }
}
