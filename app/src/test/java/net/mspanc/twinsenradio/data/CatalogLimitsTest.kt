package net.mspanc.twinsenradio.data

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class CatalogLimitsTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection, never truncation") }
        catch (_: IllegalArgumentException) { }
    }

    private fun rows(count: Int) = (1..count).joinToString("\n") { "https://radio.example/$it" }
    private fun station(index: Int) = Station("s$index", "Station", "Group", "https://radio.example/$index")

    @Test fun acceptsExactStationLimitAndRejectsNextEntryEvenIfDuplicate() {
        val body = rows(CatalogLimits.MAX_STATIONS_PER_SOURCE)
        assertEquals(500, M3uParser.parse(body, "test").size)
        rejects { M3uParser.parse(body + "\nhttps://radio.example/501", "test") }
        rejects { M3uParser.parse(body + "\nhttps://radio.example/1", "test") }
    }

    @Test fun fieldBoundariesAreEnforcedForNamesGroupsIdsAndArtwork() {
        val attributes = listOf(
            "tvg-name" to CatalogLimits.MAX_NAME,
            "group-title" to CatalogLimits.MAX_GROUP,
            "tvg-id" to CatalogLimits.MAX_ID,
            "tvg-logo" to CatalogLimits.MAX_URL
        )
        attributes.forEach { (key, maximum) ->
            fun body(size: Int) = "#EXTINF:-1 $key=\"${"a".repeat(size)}\",\nhttps://radio.example/live"
            assertEquals(1, M3uParser.parse(body(maximum), "test").size)
            rejects { M3uParser.parse(body(maximum + 1), "test") }
        }
        fun named(size: Int) = "#EXTINF:-1,${"n".repeat(size)}\nhttps://radio.example/live"
        assertEquals(1, M3uParser.parse(named(CatalogLimits.MAX_NAME), "test").size)
        rejects { M3uParser.parse(named(CatalogLimits.MAX_NAME + 1), "test") }
        assertEquals(1, M3uParser.parse("# SECTION: ${"g".repeat(CatalogLimits.MAX_GROUP)}\nhttps://radio.example/live", "test").size)
        rejects { M3uParser.parse("# SECTION: ${"g".repeat(CatalogLimits.MAX_GROUP + 1)}\nhttps://radio.example/live", "test") }
    }

    @Test fun streamAndSourceUrlBoundariesDoNotTruncateTokens() {
        val prefix = "https://radio.example/live?token="
        val url = prefix + "a".repeat(CatalogLimits.MAX_URL - prefix.length)
        assertEquals(url, M3uParser.parse(url, url).single().stream)
        rejects { M3uParser.parse(url + "a", "test") }
        rejects { M3uParser.parse("https://radio.example/live", url + "a") }
    }

    @Test fun lineAndUtf8ByteLimitsApplyToCachedAndDownloadedInput() {
        val line = "#" + "a".repeat(CatalogLimits.MAX_LINE - 1)
        assertTrue(M3uParser.parse(line, "test").isEmpty())
        rejects { M3uParser.parse(line + "a", "test") }
        val exact = ByteArray(CatalogLimits.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(CatalogLimits.MAX_BYTES, CatalogLimits.readBody(ByteArrayInputStream(exact)).length)
        rejects { CatalogLimits.readBody(ByteArrayInputStream(exact + byteArrayOf(1))) }
        val multibyte = "é".repeat(CatalogLimits.MAX_BYTES / 2)
        CatalogLimits.body(multibyte)
        rejects { CatalogLimits.body(multibyte + "a") }
    }

    @Test fun sourceCountAndSourceFieldsHaveExactBoundaries() {
        val sources = (1..CatalogLimits.MAX_SOURCES).map { PlaylistSource("p$it", "https://source/$it", "Source") }
        CatalogLimits.sources(sources)
        rejects { CatalogLimits.sources(sources + PlaylistSource("extra", "https://source/extra", "Extra")) }
        val exact = PlaylistSource("i".repeat(CatalogLimits.MAX_ID), "u".repeat(CatalogLimits.MAX_URL), "n".repeat(CatalogLimits.MAX_NAME))
        CatalogLimits.sources(listOf(exact))
        rejects { CatalogLimits.sources(listOf(exact.copy(id = exact.id + "i"))) }
        rejects { CatalogLimits.sources(listOf(exact.copy(url = exact.url + "u"))) }
        rejects { CatalogLimits.sources(listOf(exact.copy(name = exact.name + "n"))) }
    }

    @Test fun aggregateCountsSourceRowsIncludingSharedStreams() {
        val shared = List(500) { station(it) }
        CatalogLimits.catalog(List(4) { shared })
        rejects { CatalogLimits.catalog(List(4) { shared } + listOf(listOf(station(0)))) }
    }

    @Test fun rejectedRefreshCannotWriteOrReplaceLastGoodAndValidReplacementRetainsOrder() {
        val previous = listOf(station(2), station(1))
        var saved = mapOf("changed" to previous, "a" to List(500) { station(it) },
            "b" to List(500) { station(it) }, "c" to List(500) { station(it) },
            "d" to List(498) { station(it) })
        val original = saved
        var writes = 0
        rejects {
            saved = CatalogLimits.commitReplacement(saved, "changed", List(3) { station(it) }) { writes++ }
        }
        assertSame(original, saved)
        assertSame(previous, saved["changed"])
        assertEquals(0, writes)
        val replacement = listOf(station(4), station(3))
        saved = CatalogLimits.commitReplacement(saved, "changed", replacement) { writes++ }
        assertEquals(listOf("s4", "s3"), saved["changed"]!!.map { it.id })
        assertEquals(1, writes)
    }

    @Test fun failedDiskWriteCannotPublishReplacement() {
        var saved = mapOf("source" to listOf(station(1)))
        val original = saved
        try {
            saved = CatalogLimits.commitReplacement(saved, "source", listOf(station(2))) { throw java.io.IOException("disk") }
            fail("Expected disk failure")
        } catch (_: java.io.IOException) { }
        assertSame(original, saved)
    }

    @Test fun oversizedRefreshBodyCannotReachPersistenceOrReplaceLastGood() {
        val previous = mapOf("source" to listOf(station(2), station(1)))
        val invalidBodies = listOf(rows(501),
            "#EXTINF:-1,${"n".repeat(CatalogLimits.MAX_NAME + 1)}\nhttps://radio.example/live")
        invalidBodies.forEach { body ->
            var saved = previous
            var writes = 0
            rejects {
                val parsed = M3uParser.parse(body, "source")
                saved = CatalogLimits.commitReplacement(saved, "source", parsed) { writes++ }
            }
            assertSame(previous, saved)
            assertEquals(listOf("s2", "s1"), saved["source"]!!.map { it.id })
            assertEquals(0, writes)
        }
    }
}
