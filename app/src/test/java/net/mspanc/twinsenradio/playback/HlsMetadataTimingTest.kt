package net.mspanc.twinsenradio.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HlsMetadataTimingTest {
    private val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build()

    @Test fun pairedSnapshotsAndArtOnlyChangesWaitForPlaybackPosition() {
        val seen = mutableListOf<IcyArtworkParser.Update>()
        val renderer = renderer(listOf(
            2_000_000L to tag("Artist - Track", "https://nova.fr/a.jpg"),
            6_000_000L to tag("Artist - Track", "https://nova.fr/b.jpg")), seen)
        renderer.render(0, 0)
        renderer.render(1_999_999, 0)
        assertTrue(seen.isEmpty())
        renderer.render(2_000_000, 0)
        assertEquals(1, seen.size)
        assertEquals("Artist - Track", seen.single().title)
        assertEquals("https://nova.fr/a.jpg", seen.single().artworkUrl)
        renderer.render(5_999_999, 0)
        assertEquals(1, seen.size)
        renderer.render(6_000_000, 0)
        assertEquals(2, seen.size)
        assertEquals(seen[0].title, seen[1].title)
        assertEquals("https://nova.fr/b.jpg", seen[1].artworkUrl)
    }

    @Test fun explicitClearWaitsForItsOwnSampleAndClearsAssociatedSourceArt() {
        val seen = mutableListOf<IcyArtworkParser.Update>()
        val renderer = renderer(listOf(
            0L to tag("Artist - Track", "https://nova.fr/a.jpg"),
            4_000_000L to tag("Artist - Track", "")), seen)
        renderer.render(0, 0)
        renderer.render(0, 0)
        val state = StreamArtworkState()
        state.update(seen.single())
        renderer.render(3_999_999, 0)
        assertEquals(1, seen.size)
        assertEquals("https://nova.fr/a.jpg", state.urlFor("Artist - Track"))
        renderer.render(4_000_000, 0)
        assertTrue(seen.last().hasArtwork)
        assertEquals("", seen.last().artworkUrl)
        state.update(seen.last())
        assertNull(state.urlFor("Artist - Track"))
    }

    private fun renderer(samples: List<Pair<Long, ByteArray>>,
        seen: MutableList<IcyArtworkParser.Update>): MetadataRenderer {
        val renderer = MetadataRenderer({ metadata: Metadata ->
            HlsStreamsMetadataParser.parse(metadata)?.let(seen::add)
        }, null)
        renderer.replaceStream(arrayOf(format), TimedStream(samples), 0, 0,
            MediaSource.MediaPeriodId(Any()))
        return renderer
    }

    private inner class TimedStream(private val samples: List<Pair<Long, ByteArray>>) : SampleStream {
        private var formatSent = false
        private var index = 0
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
            if (!formatSent || flags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                holder.format = format
                formatSent = true
                return C.RESULT_FORMAT_READ
            }
            if (index == samples.size) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            val (time, bytes) = samples[index]
            buffer.timeUs = time
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            if (flags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(bytes.size)
                buffer.data!!.put(bytes)
            }
            if (flags and SampleStream.FLAG_PEEK == 0) index++
            return C.RESULT_BUFFER_READ
        }
    }

    private fun tag(title: String, art: String): ByteArray {
        fun size(n: Int) = byteArrayOf(((n shr 21) and 127).toByte(),
            ((n shr 14) and 127).toByte(), ((n shr 7) and 127).toByte(), (n and 127).toByte())
        fun frame(id: String, text: String): ByteArray {
            val body = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
            return id.toByteArray(Charsets.US_ASCII) + size(body.size) + byteArrayOf(0, 0) + body
        }
        val body = frame("TIT2", title) + frame("TXXX", "StreamArtwork\u0000" + art)
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + size(body.size) + body
    }
}
