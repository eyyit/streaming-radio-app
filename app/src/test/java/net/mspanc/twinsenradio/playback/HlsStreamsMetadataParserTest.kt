package net.mspanc.twinsenradio.playback

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import net.mspanc.twinsenradio.net.SafeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HlsStreamsMetadataParserTest {
    private fun synchsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7f).toByte(), ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(), (value and 0x7f).toByte()
    )

    private fun frame(id: String, content: String): ByteArray {
        val body = byteArrayOf(3) + content.toByteArray(Charsets.UTF_8)
        return id.toByteArray(Charsets.US_ASCII) + synchsafe(body.size) + byteArrayOf(0, 0) + body
    }

    private fun id3(vararg frames: ByteArray): Metadata {
        val body = frames.fold(byteArrayOf()) { result, next -> result + next }
        val tag = byteArrayOf(73, 68, 51, 4, 0, 0) + synchsafe(body.size) + body
        return Id3Decoder().decode(tag, tag.size)!!
    }

    @Test fun actualId3DecoderProducesPairedUtf8TxxxAndTitle() {
        val value = "Björk - Jóga \\ live; acoustic"
        val url = "https://art.example/artist%5Ccover.jpg?x=1;y=2"
        val metadata = id3(
            frame("TIT2", value),
            frame("TXXX", "StreamArtwork\u0000$url")
        )
        val update = HlsStreamsMetadataParser.parse(metadata)!!
        assertEquals(value, update.title)
        assertEquals(url, update.artworkUrl)
        assertTrue(update.hasTitle)
        assertTrue(update.hasArtwork)
    }

    @Test fun capturedNovaSourceHostArtworkPassesDownloadPolicy() {
        val capturedFormatUrl = "https://www.lesinrocks.com/wp-content/uploads/contract-cover.jpg"
        val update = HlsStreamsMetadataParser.parse(id3(
            frame("TIT2", "Nova Track"),
            frame("TXXX", "StreamArtwork\u0000$capturedFormatUrl")
        ))!!
        assertEquals("Nova Track", update.title)
        assertEquals(capturedFormatUrl, update.artworkUrl)
        val checkedUrl = runCatching {
            SafeHttp.checkedUrl(
                update.artworkUrl!!, false, StreamArtworkPolicy.allowedHosts
            ).toString()
        }.getOrNull()
        assertEquals(capturedFormatUrl, checkedUrl)
    }

    @Test fun actualId3DecoderPreservesExplicitEmptyArtwork() {
        val update = HlsStreamsMetadataParser.parse(
            id3(frame("TXXX", "StreamArtwork\u0000"))
        )!!
        assertFalse(update.hasTitle)
        assertTrue(update.hasArtwork)
        assertEquals("", update.artworkUrl)
    }

    @Test fun absentAndUnrelatedFramesDoNotInventAnUpdate() {
        assertNull(HlsStreamsMetadataParser.parse(id3(frame("TXXX", "Other\u0000value"))))
    }

    @Test fun ambiguousTitleAndOversizedAggregateAreRejected() {
        val ambiguous = Metadata(TextInformationFrame("TIT2", null, listOf("one", "two")))
        assertNull(HlsStreamsMetadataParser.parse(ambiguous))
        val oversized = id3(frame("TIT2", "x".repeat(3000)), frame("TXXX", "StreamArtwork\u0000https://a.example/" + "y".repeat(1200)))
        assertNull(HlsStreamsMetadataParser.parse(oversized))
    }
}
