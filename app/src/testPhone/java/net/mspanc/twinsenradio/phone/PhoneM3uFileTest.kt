package net.mspanc.twinsenradio.phone

import net.mspanc.twinsenradio.data.CatalogLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PhoneM3uFileTest {
    @Test fun readsAtTheOneMiBBoundAndRejectsOversize() {
        val exact = ByteArray(CatalogLimits.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(CatalogLimits.MAX_BYTES, PhoneM3uFile.read(ByteArrayInputStream(exact)).size)
        try {
            PhoneM3uFile.read(ByteArrayInputStream(exact + byteArrayOf('a'.code.toByte())))
            throw AssertionError("Expected file-size rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("1 MiB"))
        }
    }

    @Test fun rejectsInvalidUtf8WithoutReplacementCharacters() {
        try {
            PhoneM3uFile.parse(byteArrayOf(0xc3.toByte(), 0x28))
            throw AssertionError("Expected strict UTF-8 rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("UTF-8"))
        }
    }

    @Test fun parsesPlayableStationsInSourceOrder() {
        val input = """#EXTM3U
#EXTINF:-1,First Station
https://one.example/live
#EXTINF:-1,Second Station
https://two.example/live
#EXTINF:-1,Third Station
https://three.example/live
#EXTINF:-1,Fourth Station
https://four.example/live
""".toByteArray(Charsets.UTF_8)

        val parsed = PhoneM3uFile.parse(input)

        assertEquals(listOf("First Station", "Second Station", "Third Station", "Fourth Station"),
            parsed.stations.map { it.name })
        assertEquals(input.toString(Charsets.UTF_8), parsed.body)
    }

    @Test fun rejectsMediaManifestsAndFilesWithoutStations() {
        listOf(
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\nhttps://radio.example/live.m3u8" to "HLS",
            "#EXTM3U\n# comment only" to "No playable station entries"
        ).forEach { (body, expectedMessage) ->
            try {
                PhoneM3uFile.parse(body.toByteArray(Charsets.UTF_8))
                throw AssertionError("Expected invalid station catalog rejection")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains(expectedMessage))
            }
        }
    }
}
