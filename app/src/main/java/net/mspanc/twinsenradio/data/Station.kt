// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.data

/**
 * One of the addresses under which this same station broadcasts.
 *
 * Stations often provide several: Radio Nowy Swiat exposes MP3 256 kb/s,
 * MP3 128, and AAC+ 64. The radio-browser catalog keeps them as separate
 * entries with the same name - we merge them into a single station with a
 * list of variants.
 */
data class StreamVariant(
    val url: String,
    val label: String,
    /** Bitrate in kb/s; 0 when unknown. Determines the default selection. */
    val kbps: Int = 0
)

/**
 * A single station. [logo] is the name of a drawable built into the APK
 * (can be null), [logoUrl] is remote artwork from an M3U list or from the
 * catalog.
 *
 * [stream] is the **effective** address - the one that will actually be sent
 * to the player. It's determined by [StationRepository] based on the user's
 * selection, or, when there is no selection, by taking the variant with the
 * highest bitrate.
 */
data class Station(
    val id: String,
    /**
     * What to show, everywhere - the list, the browse tree, the dashboard.
     * Always ready to use; for a catalog station it has already been through
     * [StationNames] or replaced by whatever the user renamed it to.
     */
    val name: String,
    val genre: String,
    val stream: String,
    /** All known addresses for this station. Never empty after passing through the repository. */
    val streams: List<StreamVariant> = emptyList(),
    val logo: String? = null,
    val logoUrl: String? = null,
    val source: Source = Source.BUILT_IN,
    /**
     * The name exactly as radio-browser gave it, when [name] no longer matches
     * it. Only the station's details screen shows this - it's the answer to
     * "why is this station called that", and the thing to fall back on if a
     * rename turns out worse than the original.
     */
    val catalogName: String? = null,
    /** Whether [name] is one the user typed, as opposed to one we shortened. */
    val renamed: Boolean = false,
    /**
     * The picture the station's own app shows before play, where it publishes
     * one (RNS: its placeholder banner). Without it the screen before play
     * shows the logo faded back.
     */
    val standbyArtUrl: String? = null
) {
    enum class Source {
        BUILT_IN,
        USER_M3U,

        /** Added manually from the radio-browser.info catalog, see [RadioBrowser]. */
        DISCOVERED
    }

    /** Identifier used in the Android Auto browsing tree. */
    val mediaId: String get() = "$MEDIA_ID_PREFIX$id"

    /** Variants to display - if a station has just one address, we turn it into a single entry. */
    fun variants(): List<StreamVariant> =
        streams.ifEmpty { listOf(StreamVariant(stream, DEFAULT_LABEL)) }

    companion object {
        const val MEDIA_ID_PREFIX = "st:"
        const val DEFAULT_LABEL = "Strumień stacji"

        fun idFromMediaId(mediaId: String): String? =
            if (mediaId.startsWith(MEDIA_ID_PREFIX)) mediaId.removePrefix(MEDIA_ID_PREFIX).substringBefore(CONTEXT_SEPARATOR) else null

        private const val CONTEXT_SEPARATOR = "!ctx!"
        fun contextualMediaId(mediaId: String, parentId: String): String =
            mediaId + CONTEXT_SEPARATOR + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(parentId.toByteArray(Charsets.UTF_8))

        fun parentFromMediaId(mediaId: String): String? = runCatching {
            if (CONTEXT_SEPARATOR !in mediaId) null else String(
                java.util.Base64.getUrlDecoder().decode(mediaId.substringAfter(CONTEXT_SEPARATOR)), Charsets.UTF_8)
        }.getOrNull()
    }
}
