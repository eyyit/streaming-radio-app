package net.mspanc.twinsenradio.playback

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/** Parses title and artwork carried together in one HLS ID3 metadata event. */
@UnstableApi
internal object HlsStreamsMetadataParser {
    private const val MAX_METADATA_BYTES = 4080
    private const val MAX_ARTWORK_URL_BYTES = 2048

    fun parse(metadata: Metadata): IcyArtworkParser.Update? {
        var title: String? = null
        var artwork: String? = null
        var titleSeen = false
        var artworkSeen = false
        var invalidArtwork = false
        var totalBytes = 0
        for (index in 0 until metadata.length()) {
            when (val frame = metadata[index]) {
                is TextInformationFrame -> when {
                    frame.id == "TIT2" -> {
                        if (titleSeen || frame.values.size != 1) return null
                        titleSeen = true
                        title = frame.values.single()
                        totalBytes += title!!.toByteArray(Charsets.UTF_8).size
                    }
                    frame.id == "TXXX" &&
                        frame.description?.equals("StreamArtwork", true) == true -> {
                        if (artworkSeen) invalidArtwork = true
                        else {
                            artworkSeen = true
                            if (frame.values.size != 1) invalidArtwork = true
                            else {
                                artwork = frame.values.single()
                                totalBytes += artwork!!.toByteArray(Charsets.UTF_8).size
                            }
                        }
                    }
                }
            }
        }
        if (!titleSeen && !artworkSeen) return null
        if (totalBytes > MAX_METADATA_BYTES) return null
        val safeTitle = title
        val safeArtwork = when {
            !artworkSeen -> null
            invalidArtwork -> null
            artwork.isNullOrEmpty() -> ""
            artwork!!.toByteArray(Charsets.UTF_8).size > MAX_ARTWORK_URL_BYTES -> null
            else -> IcyArtworkParser.validArtworkUrl(artwork!!)
        }
        return IcyArtworkParser.Update(safeTitle, safeArtwork, titleSeen, artworkSeen)
    }
}
