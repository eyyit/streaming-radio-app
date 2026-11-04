package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.mspanc.twinsenradio.net.SafeHttp

class PlaylistUrlCaseTest {
    @Test fun acceptsCaseInsensitiveHttpsAtBothValidationBoundaries() {
        listOf("https", "HTTPS", "hTtPs").forEach { scheme ->
            val url = "$scheme://example.com/radio.m3u"
            assertTrue("Playlist validator must accept $scheme", runCatching {
                PlaylistLibrary.requireRemoteUrl(url)
            }.isSuccess)
            val checked = runCatching { SafeHttp.checkedUrl(url, false).protocol }
            assertEquals("HTTP validator must accept $scheme", "https", checked.getOrNull())
        }
    }
}
