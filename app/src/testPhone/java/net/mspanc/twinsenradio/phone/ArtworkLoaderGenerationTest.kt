package net.mspanc.twinsenradio.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import net.mspanc.twinsenradio.ui.ArtworkLoader
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper.shadowMainLooper
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ArtworkLoaderGenerationTest {
    @Test fun staleSameUriLoadCannotReplaceReboundStationsFallback() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = "artwork.generation.${UUID.randomUUID()}"
        val provider = BlockingArtworkProvider(entered, release)
        val providerInfo = ProviderInfo().apply {
            this.authority = authority
            applicationInfo = context.applicationInfo
        }
        ContentProviderController.of(provider).create(providerInfo)
        val uri = Uri.parse("content://$authority/image")
        val firstFallback = PhoneStationArtwork("Alpha Radio")
        val reboundFallback = PhoneStationArtwork("Beta Radio")
        val host = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = host.create().start().resume().visible().get()
        val target = ImageView(activity)
        try {
            ArtworkLoader.into(activity.lifecycleScope, null, uri, android.R.color.transparent,
                target, fallbackDrawable = firstFallback)
            assertTrue("First request should enter the blocking provider",
                entered.await(5, TimeUnit.SECONDS))

            // Simulate RecyclerView invalidating and rebinding the same ImageView
            // to another station that happens to advertise the same image URI.
            ArtworkLoader.into(activity.lifecycleScope, null, null,
                android.R.color.transparent, target)
            ArtworkLoader.into(activity.lifecycleScope, null, uri,
                android.R.color.transparent, target, fallbackDrawable = reboundFallback)
            release.countDown()

            val deadline = System.currentTimeMillis() + 5_000
            while (target.drawable !== firstFallback && target.drawable !== reboundFallback &&
                System.currentTimeMillis() < deadline
            ) {
                shadowMainLooper().idle()
                Thread.sleep(10)
            }
            shadowMainLooper().idle()
            assertSame("The rebound station's fallback must win after the stale request completes",
                reboundFallback, target.drawable)
        } finally {
            release.countDown()
            host.pause().stop().destroy()
        }
    }

    private class BlockingArtworkProvider(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release artwork read" }
            throw FileNotFoundException("Synthetic unavailable artwork")
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?) = 0
    }
}
