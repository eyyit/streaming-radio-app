package net.mspanc.twinsenradio.playback

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TrackArtworkStorePolicyTest {
    private val url = "https://artwork.invalid/cover.jpg"
    private val unrestricted = M3uParser.stableKey(url) + ".jpg"
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun restrictedFetchCannotReuseAnUnrestrictedCompletedCacheEntry() = runBlocking {
        Prefs(context).onlineArtworkEnabled = true
        val directory = File(context.cacheDir, "track-art").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        File(directory, unrestricted).writeText("unrestricted image from a different redirect policy")
        val trusted = restrictedName("artwork.invalid")
        File(directory, trusted).writeText("image verified under restricted redirect policy")
        val downloads = TrackArtworkStore.downloadCalls.get()

        val ordinary = TrackArtworkStore.fetch(context, url)!!
        assertEquals(unrestricted, ordinary.lastPathSegment)
        val restricted = TrackArtworkStore.fetch(context, url, allowedHosts = setOf("artwork.invalid"))!!
        assertEquals("restricted requests must use their own policy cache", trusted, restricted.lastPathSegment)
        assertNotEquals(ordinary, restricted)
        assertEquals("image verified under restricted redirect policy",
            TrackArtworkStore.cachedFile(context, restricted.lastPathSegment!!)!!.readText())
        assertEquals("cache fixtures must never activate network downloads", downloads, TrackArtworkStore.downloadCalls.get())
    }

    @Test fun equivalentHostSetsShareCacheButDifferentPoliciesRemainSeparate() = runBlocking {
        Prefs(context).onlineArtworkEnabled = true
        val directory = File(context.cacheDir, "track-art").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        File(directory, unrestricted).writeText("unrestricted")
        val twoHosts = restrictedName("artwork.invalid", "other.invalid")
        val oneHost = restrictedName("artwork.invalid")
        File(directory, twoHosts).writeText("two hosts")
        File(directory, oneHost).writeText("one host")
        val downloads = TrackArtworkStore.downloadCalls.get()
        val first = TrackArtworkStore.fetch(context, url,
            allowedHosts = linkedSetOf("other.invalid", "artwork.invalid"))!!
        val equivalent = TrackArtworkStore.fetch(context, url,
            allowedHosts = linkedSetOf("ARTWORK.INVALID", "OTHER.INVALID"))!!
        val narrower = TrackArtworkStore.fetch(context, url,
            allowedHosts = setOf("artwork.invalid"))!!
        assertEquals(twoHosts, first.lastPathSegment)
        assertEquals(first, equivalent)
        assertEquals(oneHost, narrower.lastPathSegment)
        assertNotEquals(first, narrower)
        assertEquals(downloads, TrackArtworkStore.downloadCalls.get())
    }

    @Test fun novaTrackAssetsHitOnlyTheirRestrictedOfflineCache() = runBlocking {
        val prefs = Prefs(context)
        val wasEnabled = prefs.onlineArtworkEnabled
        val assets = NovaProducerArtworkFixture.trackAssets()
        val hosts = StreamArtworkPolicy.allowedHosts
        val policy = hosts.map { it.lowercase(java.util.Locale.ROOT) }
            .distinct().sorted().joinToString("") { "${it.length}:$it" }
        val directory = File(context.cacheDir, "track-art").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val downloads = TrackArtworkStore.downloadCalls.get()
        prefs.onlineArtworkEnabled = true
        try {
            assertEquals(16, assets.size)
            assertEquals(18, assets.sumOf { it.sourceFields.size })
            for (asset in assets) {
                val key = "restricted-artwork-v1\u0000$policy\u0000${asset.url}"
                val name = M3uParser.stableKey(key) + ".jpg"
                File(directory, name).writeText("offline policy-bound cache fixture")
                val uri = TrackArtworkStore.fetch(
                    context, asset.url, allowedHosts = hosts
                )
                assertEquals(name, uri?.lastPathSegment)
            }

            val lookalike = "https://www.lesinrocks.com.evil.example/cached.jpg"
            val lookalikeKey = "restricted-artwork-v1\u0000$policy\u0000$lookalike"
            val lookalikeName = M3uParser.stableKey(lookalikeKey) + ".jpg"
            File(directory, lookalikeName).writeText("must not bypass URL policy")
            assertNull(TrackArtworkStore.fetch(
                context, lookalike, allowedHosts = hosts
            ))
            assertEquals("cache fixtures must never activate network downloads",
                downloads, TrackArtworkStore.downloadCalls.get())
        } finally {
            prefs.onlineArtworkEnabled = wasEnabled
        }
    }
    private fun restrictedName(vararg hosts: String): String {
        val policy = hosts.sorted().joinToString("") { "${it.length}:$it" }
        return M3uParser.stableKey("restricted-artwork-v1\u0000$policy\u0000$url") + ".jpg"
    }
}
