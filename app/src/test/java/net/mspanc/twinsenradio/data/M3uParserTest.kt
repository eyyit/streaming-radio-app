package net.mspanc.twinsenradio.data

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun readsTvgNameAndUsefulUrlFallbackWithoutQuerySecrets() {
        val parsed = M3uParser.parse("#EXTINF:-1 tvg-name=\"My Station\",\nhttps://example.org/live?token=hidden\nhttps://other.example.org/mystation.aac?token=hidden", "test")
        assertEquals("My Station", parsed[0].name)
        assertEquals("other.example.org / mystation", parsed[1].name)
        assertFalse(parsed.any { "hidden" in it.name })
    }
    @Test fun identitySurvivesReorderAndExplicitIdSurvivesUrlChange() {
        val a = "#EXTINF:-1,A\nhttps://example.org/a"
        val b = "#EXTINF:-1 tvg-id=\"b\",B\nhttps://example.org/b"
        val first = M3uParser.parse("$a\n$b", "https://example.org/list")
        val reordered = M3uParser.parse("$b\n$a", "https://example.org/list")
        assertEquals(first.map { it.id }.toSet(), reordered.map { it.id }.toSet())
        val changed = M3uParser.parse(b.replace("org/b", "org/new"), "https://example.org/list")
        assertEquals(first[1].id, changed.single().id)
        assertNotEquals(first[0].id, M3uParser.parse(a, "other-source").single().id)
    }

    @Test fun parsesBomCrLfQuotedCommasAndFullQuery() {
        val result = M3uParser.parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"Soul, Jazz\" tvg-logo=\"https://example.org/a.png\",Name, with comma\r\nhttps://example.org/radio?a=1&b=2", "test").single()
        assertEquals("Soul, Jazz", result.genre)
        assertEquals("Name, with comma", result.name)
        assertEquals("https://example.org/radio?a=1&b=2", result.stream)
    }

    @Test fun rejectsInvalidSchemesAndDeduplicatesStations() {
        val result = M3uParser.parse("<html>oops</html>\nfile:///etc/passwd\njavascript:bad\nhttps://example.org/a\nhttps://example.org/a", "test")
        assertEquals(1, result.size)
    }

    @Test fun sectionCommentsPersistAndExplicitGroupOverridesOneStation() {
        val body = """
            #EXTM3U
            # SECTION: Jazz
            #EXTINF:-1,First
            https://example.org/1
            #EXTINF:-1 group-title="Soul",Second
            https://example.org/2
            # ordinary comment
            #EXTINF:-1,Third
            https://example.org/3
            # SECTION: World
            #EXTINF:-1,Fourth
            https://example.org/4
        """.trimIndent()
        assertEquals(listOf("Jazz", "Soul", "Jazz", "World"), M3uParser.parse(body, "test").map { it.genre })
    }

    @Test fun contextualMediaIdsRoundTripUnicodeSectionsWithoutChangingStationIdentity() {
        val id = Station.contextualMediaId("st:u_123", "genre:Jazz & 世界")
        assertEquals("u_123", Station.idFromMediaId(id))
        assertEquals("genre:Jazz & 世界", Station.parentFromMediaId(id))
        assertNull(Station.parentFromMediaId("st:u_123"))
    }
}
