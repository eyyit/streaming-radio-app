package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ArtworkAccessPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val authority = "com.impostorlabs.streamingradio.debug.logos"
    private val id = "a".repeat(64)

    @Test fun acceptsOnlyExplicitProviderRoutes() {
        listOf("logo", "icon", "custom", "muted", "station").forEach {
            assertEquals(it to id, ArtworkAccessPolicy.route("content://$authority/$it/$id", authority))
        }
        assertEquals("track" to "$id.jpg", ArtworkAccessPolicy.route("content://$authority/track/$id.jpg", authority))
        assertEquals("station" to id, ArtworkAccessPolicy.route("content://$authority/station/$id?v=123", authority))
    }

    @Test fun rejectsTraversalPrivatePathsAndCallerControlledSources() {
        listOf(
            "/custom/$id?p=/data/user/10/com.impostorlabs.streamingradio.debug/shared_prefs/preferences.xml",
            "/custom/$id?p=/sdcard/logo.png", "/muted/$id?src=file:///data/private.xml",
            "/muted/$id?src=content://$authority/muted/$id",
            "/muted/$id?src=https://attacker.example/logo.png",
            "/logo/$id?v=2131234567", "/logo/$id?revision=1",
            "/track/../$id.jpg", "/track/%2e%2e", "/custom/%2fdata%2fprivate.png",
            "/custom/$id/extra", "/unknown/$id", "/station/$id?v=1&v=2",
            "/custom/$id#fragment", "/custom/${id.uppercase()}"
        ).forEach { path -> assertNull(path, ArtworkAccessPolicy.route("content://$authority$path", authority)) }
        assertNull(ArtworkAccessPolicy.route("content://other.logos/custom/$id", authority))
        assertNull(ArtworkAccessPolicy.route("file://$authority/custom/$id", authority))
    }

    @Test fun canonicalFileCheckAllowsArtworkAndRejectsPrivateSiblingExternalPaths() {
        val root = temporary.newFolder("logo-custom")
        val art = File(root, "logo.png").apply { writeBytes(byteArrayOf(1)) }
        val private = temporary.newFolder("shared_prefs")
        val secret = File(private, "settings.xml").apply { writeText("secret") }
        val disguised = File(private, "settings.png").apply { writeText("secret") }
        val sibling = temporary.newFolder("logo-custom-sibling")
        val outside = File(sibling, "logo.png").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(art.canonicalFile, ArtworkAccessPolicy.allowedFile(art, listOf(root)))
        listOf(secret, disguised, outside, File(root, "../shared_prefs/settings.png"), File("/sdcard/logo.png"))
            .forEach { assertNull(ArtworkAccessPolicy.allowedFile(it, listOf(root))) }
        art.writeBytes(byteArrayOf())
        assertNull(ArtworkAccessPolicy.allowedFile(art, listOf(root)))
        java.io.RandomAccessFile(art, "rw").use { it.setLength(ArtworkAccessPolicy.MAX_BYTES.toLong() + 1) }
        assertNull(ArtworkAccessPolicy.allowedFile(art, listOf(root)))
    }

    @Test fun enforcesNetworkSchemeAndByteBudget() {
        assertTrue(ArtworkAccessPolicy.httpsSource("https://images.example/logo.png"))
        listOf("http://images.example/logo.png", "file:///private", "content://other/private",
            "https://user:pass@example.com/logo", "https:///logo", "https://example.com/logo#fragment")
            .forEach { assertFalse(it, ArtworkAccessPolicy.httpsSource(it)) }
        val exact = ByteArray(ArtworkAccessPolicy.MAX_BYTES)
        assertEquals(exact.size, ArtworkAccessPolicy.readBounded(ByteArrayInputStream(exact))?.size)
        assertNull(ArtworkAccessPolicy.readBounded(ByteArrayInputStream(ByteArray(exact.size + 1))))
    }

    @Test fun opaqueKeysAreStableAndDoNotContainSourcePaths() {
        val key = ArtworkAccessPolicy.key("/private/logo-custom/logo.png")
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertEquals(key, ArtworkAccessPolicy.key("/private/logo-custom/logo.png"))
        assertNotEquals(key, ArtworkAccessPolicy.key("/private/logo-custom/other.png"))
    }

    @Test fun samplingBoundsBothDimensionsIncludingRoundingAndExtremeAspectRatios() {
        assertNull(ArtworkAccessPolicy.sampleSize(0, 100))
        assertNull(ArtworkAccessPolicy.sampleSize(100, -1))
        assertEquals(1, ArtworkAccessPolicy.sampleSize(1024, 1024))
        assertEquals(2, ArtworkAccessPolicy.sampleSize(1025, 1))
        assertEquals(4, ArtworkAccessPolicy.sampleSize(2049, 1))
        listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, 8000 to 20).forEach { (width, height) ->
            val sample = ArtworkAccessPolicy.sampleSize(width, height)!!
            assertTrue((width - 1) / sample + 1 <= 1024)
            assertTrue((height - 1) / sample + 1 <= 1024)
        }
    }
}
