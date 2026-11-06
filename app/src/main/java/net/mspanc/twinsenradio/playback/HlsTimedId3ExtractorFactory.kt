package net.mspanc.twinsenradio.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/** Adds the leading packed-MP3 ID3 snapshot to the playback metadata timeline. */
@androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class,
)
internal class HlsTimedId3ExtractorFactory(
    private val delegate: HlsExtractorFactory = DefaultHlsExtractorFactory(),
) : HlsExtractorFactory by delegate {
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory) = apply {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean) = apply {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int) = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        // MP3 sniffing in Media3 consumes leading ID3 before returning its extractor.
        val leadingId3 = peekLeadingId3(sniffingExtractorInput)
        val extractor = delegate.createExtractor(
            uri, format, muxedCaptionFormats, timestampAdjuster,
            responseHeaders, sniffingExtractorInput, playerId,
        )
        if (!extractor.isPackedAudioExtractor) return extractor
        // Keep Media3's format selection and wrap only positively sniffed MP3.
        val isMp3 = try {
            Mp3Extractor().sniff(sniffingExtractorInput)
        } catch (_: EOFException) {
            false
        } finally {
            sniffingExtractorInput.resetPeekPosition()
        }
        return if (isMp3) PackedMp3Id3Extractor(extractor, leadingId3, captureAtRead = false) else extractor
    }
}

/** One instance per segment; the delegate retains all audio/timestamp behavior. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PackedMp3Id3Extractor(
    private val delegate: HlsMediaChunkExtractor,
    initialSnapshot: ByteArray? = null,
    captureAtRead: Boolean = true,
) : HlsMediaChunkExtractor by delegate {
    private var capturePending = captureAtRead
    private var snapshot: ByteArray? = initialSnapshot
    private var metadataOutput: TrackOutput? = null

    override fun init(extractorOutput: ExtractorOutput) {
        delegate.init(object : ExtractorOutput by extractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val output = extractorOutput.track(id, type)
                if (type != C.TRACK_TYPE_AUDIO) return output
                return object : TrackOutput by output {
                    override fun sampleMetadata(
                        timeUs: Long, flags: Int, size: Int, offset: Int,
                        cryptoData: TrackOutput.CryptoData?,
                    ) {
                        // The metadata and first audio sample share the exact sample offset
                        // applied by HLS (including Apple's PRIV transport timestamp).
                        snapshot?.let { bytes ->
                            metadataOutput?.let { metadata ->
                                metadata.sampleData(ParsableByteArray(bytes), bytes.size)
                                metadata.sampleMetadata(
                                    timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null,
                                )
                                snapshot = null
                            }
                        }
                        output.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    }
                }
            }

            override fun endTracks() {
                metadataOutput = extractorOutput.track(1, C.TRACK_TYPE_METADATA).also {
                    it.format(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build())
                }
                extractorOutput.endTracks()
            }
        })
    }

    override fun read(extractorInput: ExtractorInput): Boolean {
        if (capturePending) {
            // Set only after a complete peek, so retryable network failures retry capture.
            snapshot = peekLeadingId3(extractorInput)
            capturePending = false
        }
        return delegate.read(extractorInput)
    }

    override fun recreate(): HlsMediaChunkExtractor = PackedMp3Id3Extractor(delegate.recreate())

    override fun onTruncatedSegmentParsed() {
        // Do not emit a captured tag against audio from a restarted/truncated segment.
        snapshot = null
        delegate.onTruncatedSegmentParsed()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun peekLeadingId3(input: ExtractorInput): ByteArray? {
    try {
        val header = ByteArray(10)
        if (!input.peekFully(header, 0, header.size, true)) return null
        if (header[0] != 73.toByte() || header[1] != 68.toByte() ||
            header[2] != 51.toByte() || header[3].toInt() !in 2..4 ||
            (6..9).any { header[it].toInt() and 0x80 != 0 }
        ) return null
        var size = 0
        for (i in 6..9) size = (size shl 7) or header[i].toInt()
        // The declared ID3 size includes any v2.4 footer, but excludes the header.
        val total = 10 + size
        if (total > MAX_ID3_BYTES) return null
        val bytes = ByteArray(total)
        header.copyInto(bytes)
        input.peekFully(bytes, 10, total - 10)
        return bytes
    } catch (_: EOFException) {
        return null
    } finally {
        input.resetPeekPosition()
    }
}

private const val MAX_ID3_BYTES = 16 * 1024
