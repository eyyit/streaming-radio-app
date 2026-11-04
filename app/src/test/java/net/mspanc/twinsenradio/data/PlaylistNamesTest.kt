package net.mspanc.twinsenradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistNamesTest {
    @Test
    fun sameHostUsesPathToDistinguishPlaylistFiles() {
        assertEquals(
            "radio.example / jazz.m3u",
            PlaylistNames.fromUrl("https://radio.example/jazz.m3u", "Playlist 1")
        )
        assertEquals(
            "radio.example / soul.m3u",
            PlaylistNames.fromUrl("https://radio.example/soul.m3u", "Playlist 2")
        )
        assertTrue(
            PlaylistNames.fromUrl("https://radio.example/a/jazz.m3u", "Playlist") !=
                PlaylistNames.fromUrl("https://radio.example/b/jazz.m3u", "Playlist")
        )
    }

    @Test
    fun pathIsDecodedWithoutTreatingPlusAsSpace() {
        assertEquals(
            "listen.example / mix plus+radio.m3u",
            PlaylistNames.fromUrl(
                "https://listen.example/mix%20plus+radio.m3u",
                "Playlist"
            )
        )
    }

    @Test
    fun longGeneratedNamesKeepHostPrefixAndFilenameSuffixWithinLimit() {
        val filename = "playlist-tail.m3u"
        val url = "https://radio.example/${"a".repeat(700)}/$filename"
        val name = PlaylistNames.fromUrl(url, "Playlist 1")

        assertTrue(name.length <= CatalogLimits.MAX_NAME)
        assertTrue(name.startsWith("radio.example / "))
        assertTrue(name.endsWith(filename))
        assertTrue(name.contains("…"))
    }

    @Test
    fun decodedControlCharactersAreReplacedInGeneratedLabels() {
        val name = PlaylistNames.fromUrl(
            "https://radio.example/jazz%0A.m3u",
            "Playlist 1"
        )

        assertEquals("radio.example / jazz\uFFFD.m3u", name)
        assertTrue(name.none { it.isISOControl() })
    }

    @Test
    fun excludesUserInfoQueryAndFragment() {
        val name = PlaylistNames.fromUrl(
            "https://user:pass@radio.example/jazz.m3u?token=query-secret#fragment-secret",
            "Playlist"
        )

        assertEquals("radio.example / jazz.m3u", name)
        assertFalse(name.contains("user"))
        assertFalse(name.contains("pass"))
        assertFalse(name.contains("secret"))
    }

    @Test
    fun normalizesLegacyHostAndBlankNamesButKeepsCustomNames() {
        val url = "https://radio.example/collections/jazz.m3u"

        assertEquals(
            "radio.example / collections/jazz.m3u",
            PlaylistNames.normalizeStoredName("RADIO.EXAMPLE", url, "Playlist 1")
        )
        assertEquals(
            "radio.example / collections/jazz.m3u",
            PlaylistNames.normalizeStoredName("  ", url, "Playlist 1")
        )
        assertEquals(
            "My jazz favorites",
            PlaylistNames.normalizeStoredName("My jazz favorites", url, "Playlist 1")
        )
        assertEquals(
            "Saved stations",
            PlaylistNames.normalizeStoredName("Saved stations", "local:saved-stations", "Saved stations")
        )
    }

    @Test
    fun rootAndInvalidUrlsUseHostOrFallback() {
        assertEquals(
            "radio.example",
            PlaylistNames.fromUrl("https://radio.example/", "Playlist 3")
        )
        assertEquals(
            "Playlist 3",
            PlaylistNames.fromUrl("not a URL", "Playlist 3")
        )
        assertEquals(
            "Playlist 4",
            PlaylistNames.fromUrl("https://radio.example/%zz", "Playlist 4")
        )
    }
}
