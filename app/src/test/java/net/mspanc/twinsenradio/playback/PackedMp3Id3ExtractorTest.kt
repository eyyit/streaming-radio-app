package net.mspanc.twinsenradio.playback

import android.net.Uri
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PackedMp3Id3ExtractorTest {
    @Test fun `stock MP3 emits no timed ID3 but bridge preserves all audio`() {
        val tag = tag("Artist - Title")
        val stock = extract(chunk(), tag + audio())
        val bridged = extract(PackedMp3Id3Extractor(chunk()), tag + audio())
        assertFalse(stock.tracks.values.any { it.type == C.TRACK_TYPE_METADATA })
        val metadata = bridged.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }
        val originalAudio = stock.tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }
        val wrappedAudio = bridged.tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }
        assertArrayEquals(originalAudio.data.toByteArray(), wrappedAudio.data.toByteArray())
        assertEquals(originalAudio.times, wrappedAudio.times)
        assertArrayEquals(tag, metadata.data.toByteArray())
        assertEquals(listOf(wrappedAudio.times.first()), metadata.times)
        assertEquals(1, metadata.times.size)
        assertTrue(bridged.metadataDeclaredBeforeEnd)
    }

    @Test fun `recreated later segment emits repeated snapshot and artwork only changes`() {
        var extractor: HlsMediaChunkExtractor = PackedMp3Id3Extractor(chunk())
        for (artwork in listOf("https://example.com/one.jpg", "https://example.com/one.jpg", "")) {
            val tag = tag("Same title", artwork)
            val output = extract(extractor, tag + audio())
            val metadata = output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }
            assertArrayEquals(tag, metadata.data.toByteArray())
            assertEquals(listOf(0L), metadata.times)
            assertFalse(extractor.isReusable)
            assertTrue(extractor.isPackedAudioExtractor)
            extractor = extractor.recreate()
        }
    }

    @Test fun `missing oversized and truncated ID3 leave audio unchanged`() {
        val oversized = tag("x".repeat(17000))
        for (prefix in listOf(byteArrayOf(), oversized)) {
            val stock = extract(chunk(), prefix + audio())
            val wrapped = extract(PackedMp3Id3Extractor(chunk()), prefix + audio())
            assertArrayEquals(
                stock.tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }.data.toByteArray(),
                wrapped.tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }.data.toByteArray(),
            )
            assertTrue(wrapped.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }.times.isEmpty())
        }
        val output = extract(PackedMp3Id3Extractor(chunk()), tag("truncated").copyOf(12))
        assertTrue(output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }.times.isEmpty())
    }

    @Test fun `factory wraps MP3 but keeps existing AAC timed ID3 path`() {
        for (isMp3 in listOf(true, false)) {
            val tag = tag("Artist - Title")
            val adtsFrame = byteArrayOf(-1, -15, 80, -128, 1, 127, -4, 0, 0, 0, 0)
            val bytes = tag + if (isMp3) audio() else adtsFrame + adtsFrame + adtsFrame + adtsFrame
            val input = input(bytes)
            val extractor = HlsTimedId3ExtractorFactory().createExtractor(
                Uri.parse(if (isMp3) "https://example.com/segment.mp3" else "https://example.com/segment.aac"),
                Format.Builder().build(), null, TimestampAdjuster(0), emptyMap(), input, PlayerId.UNSET,
            )
            assertEquals(isMp3, extractor is PackedMp3Id3Extractor)
            val output = Output()
            extractor.init(output)
            while (extractor.read(input)) { /* Exhaust actual extractor samples. */ }
            val metadata = output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }
            assertArrayEquals(tag, metadata.data.toByteArray())
            assertEquals(1, metadata.times.size)
        }
    }

    @Test fun `ID3 footer never captures bytes from audio`() {
        val original = tag("Artist - Title")
        val size = original.size
        val header = "ID3".toByteArray() + byteArrayOf(4, 0, 16) + synchsafe(size)
        val footer = "3DI".toByteArray() + byteArrayOf(4, 0, 16) + synchsafe(size)
        val tag = header + original.copyOfRange(10, original.size) + footer
        val decoded = androidx.media3.extractor.metadata.id3.Id3Decoder().decode(tag, tag.size)
        assertNotNull(decoded)
        val output = extract(PackedMp3Id3Extractor(chunk()), tag + audio())
        assertArrayEquals(tag, output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }.data.toByteArray())
    }

    @Test fun `frozen relay vectors retain title artwork PRIV and audio across every segment`() {
        val manifest = org.json.JSONObject(resource("manifest.json").toString(Charsets.UTF_8))
        val cases = manifest.getJSONArray("cases")
        var segmentCount = 0
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val bytes = resource(case.getString("file"))
            assertEquals(case.getString("file_sha256"), sha256(bytes))
            assertEquals(case.getString("source_sha256"), sha256(resource(case.getString("source_file"))))
            val isMp3 = case.getString("mime") == "audio/mpeg"
            val segments = case.getJSONArray("segments")
            var recreated: HlsMediaChunkExtractor? = null
            for (index in 0 until segments.length()) {
                val segment = segments.getJSONObject(index)
                val offset = segment.getInt("offset")
                val packed = bytes.copyOfRange(offset, offset + segment.getInt("length"))
                val tagSize = 10 + (6..9).fold(0) { size, i -> (size shl 7) or packed[i].toInt() }
                val rawTag = packed.copyOfRange(0, tagSize)
                val rawAudio = packed.copyOfRange(tagSize, packed.size)
                assertEquals(segment.getString("audio_sha256"), sha256(rawAudio))
                val decoded = androidx.media3.extractor.metadata.id3.Id3Decoder().decode(rawTag.copyOf(), rawTag.size)!!
                val priv = (0 until decoded.length()).map { decoded[it] }
                    .filterIsInstance<androidx.media3.extractor.metadata.id3.PrivFrame>().single()
                assertEquals("com.apple.streaming.transportStreamTimestamp", priv.owner)
                val pts = priv.privateData.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
                assertEquals(segment.getLong("expected_apple_pts_90k"), pts)
                val parsed = HlsStreamsMetadataParser.parse(decoded)!!
                assertEquals(segment.getString("title"), parsed.title)
                assertEquals(segment.getString("artwork_url"), parsed.artworkUrl)
                assertTrue(parsed.hasTitle)
                assertTrue(parsed.hasArtwork)
                // Each range is also a valid late join through the actual factory.
                val input = input(packed)
                val factoryExtractor = HlsTimedId3ExtractorFactory().createExtractor(
                    Uri.parse("https://example.com/segment." + if (isMp3) "mp3" else "aac"),
                    Format.Builder().setSampleMimeType(case.getString("mime")).build(),
                    null, TimestampAdjuster(0), emptyMap(), input, PlayerId.UNSET,
                )
                assertEquals(isMp3, factoryExtractor is PackedMp3Id3Extractor)
                val output = Output()
                factoryExtractor.init(output)
                while (factoryExtractor.read(input)) { /* Read one frozen segment. */ }
                val metadata = output.tracks.values.single { it.type == C.TRACK_TYPE_METADATA }
                val audio = output.tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }
                assertArrayEquals(rawTag, metadata.data.toByteArray())
                assertEquals(listOf(audio.times.first()), metadata.times)
                if (isMp3) assertArrayEquals(rawAudio, audio.data.toByteArray())
                // Exercise HLS recreation as well as independent late joins.
                recreated = recreated?.recreate() ?: factoryExtractor.recreate()
                val recreatedOutput = extract(recreated, packed)
                assertArrayEquals(rawTag, recreatedOutput.tracks.values.single {
                    it.type == C.TRACK_TYPE_METADATA
                }.data.toByteArray())
                segmentCount++
            }
        }
        assertEquals(11, segmentCount)
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/hls-artwork/$name")!!.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun chunk() = BundledHlsMediaChunkExtractor(
        Mp3Extractor(0, 0), Format.Builder().build(), TimestampAdjuster(0),
    )

    private fun extract(extractor: HlsMediaChunkExtractor, bytes: ByteArray): Output {
        val output = Output()
        extractor.init(output)
        val input = input(bytes)
        var reads = 0
        while (extractor.read(input)) check(++reads < 1000)
        return output
    }

    private fun input(bytes: ByteArray): DefaultExtractorInput {
        var position = 0
        return DefaultExtractorInput(DataReader { buffer, offset, length ->
            if (position == bytes.size) -1 else {
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                count
            }
        }, 0, bytes.size.toLong())
    }

    private class Output : ExtractorOutput {
        val tracks = linkedMapOf<Int, Track>()
        var metadataDeclaredBeforeEnd = false
        override fun track(id: Int, type: Int) = tracks.getOrPut(id) { Track(type) }
        override fun endTracks() {
            metadataDeclaredBeforeEnd = tracks.values.any { it.type == C.TRACK_TYPE_METADATA }
        }
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class Track(val type: Int) : TrackOutput {
        val data = ByteArrayOutputStream()
        val times = mutableListOf<Long>()
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count > 0) data.write(bytes, 0, count)
            return count
        }
        override fun sampleData(bytes: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            bytes.readBytes(buffer, 0, length)
            data.write(buffer)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times.add(timeUs)
        }
    }

    private fun tag(text: String, artwork: String = "https://example.com/art.jpg"): ByteArray {
        fun frame(id: String, payload: ByteArray) =
            id.toByteArray() + synchsafe(payload.size) + byteArrayOf(0, 0) + payload
        val title = frame("TIT2", byteArrayOf(3) + text.toByteArray(Charsets.UTF_8))
        val art = frame("TXXX", byteArrayOf(3) + "StreamArtwork".toByteArray() +
            byteArrayOf(0) + artwork.toByteArray(Charsets.UTF_8))
        val timestamp = frame("PRIV", "com.apple.streaming.transportStreamTimestamp".toByteArray() +
            byteArrayOf(0) + byteArrayOf(0, 0, 0, 0, 0, 1, 95, -112))
        val body = timestamp + title + art
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + synchsafe(body.size) + body
    }

    private fun synchsafe(size: Int) = byteArrayOf(
        (size shr 21 and 127).toByte(), (size shr 14 and 127).toByte(),
        (size shr 7 and 127).toByte(), (size and 127).toByte(),
    )

    private fun audio(): ByteArray {
        // MPEG1 layer III, 128 kbit/s, 44.1 kHz: four valid 417-byte frames.
        val frame = ByteArray(417)
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(frame)
        return frame + frame + frame + frame
    }
}
