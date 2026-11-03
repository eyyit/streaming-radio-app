package net.mspanc.twinsenradio.playback

import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether what a catalogue returned is actually the track we asked about.
 *
 * Both iTunes and MusicBrainz answer a loose text search and will hand back
 * their nearest guess rather than nothing. Taking that on trust put a stranger's
 * record on the dashboard: "Zalia - Tylko kochaj mnie" came back as
 * "Krzysztof Zalewski - Kochaj", and the instrument cluster showed the sleeve of
 * an album nobody was playing (11.09.2026). "Fast Boy - Music Sounds" had been
 * quietly answered with "Lee Brice - Boy" the day before, and nobody noticed.
 *
 * A wrong cover is worse than none: it is confidently, legibly false, and the
 * album and year we glue onto the description come from the same answer.
 *
 * Checked against every catalogue hit in the traces of 09-11.09.2026 - 112 of
 * them. This rule rejects exactly those two and keeps the other 110.
 */
object TrackMatch {

    /**
     * @param queryArtist what the station called the artist - which for some
     *   stations is really the title, see [CoverArtLookup.TrackInfo.looksSwapped].
     * @param catalogueArtist what came back; null when the source didn't say.
     */
    fun plausible(
        queryArtist: String?,
        queryTitle: String?,
        catalogueArtist: String?,
        catalogueTitle: String?
    ): Boolean {
        val qa = key(queryArtist)
        val qt = key(queryTitle)
        val ka = key(catalogueArtist)
        val kt = key(catalogueTitle)

        // Nothing to compare against - an answer with no title could be about
        // anything, so it is not about this.
        if (kt.isEmpty()) return false

        // The straightforward case, and the swapped one: Jacaranda FM sends
        // "TITLE - ARTIST", so the title we are looking for may be sitting in
        // the field the station called the artist.
        if (kt == qt || kt == qa) return true

        // Same artist, near-enough title: "A Picture Of You" against Boyzone's
        // "Picture of You", or a title the catalogue spells with an extra
        // qualifier. Containment is only safe once the artist agrees - without
        // that guard it is exactly what let "Kochaj" match "Tylko kochaj mnie".
        val sameArtist = ka.isNotEmpty() && (ka == qa || ka == qt)
        return sameArtist && qt.isNotEmpty() && (kt.contains(qt) || qt.contains(kt))
    }

    /**
     * Comparable form of a name.
     *
     * Everything that differs between a station and a catalogue without
     * changing which record is meant comes off: the trailing qualifier
     * ("(feat. ...)", "[Remastered]", "(7\" Edit)"), accents, case, and every
     * separator - which is what makes "THE KID LAROI [+] JUSTIN BIEBER" and
     * "The Kid LAROI & Justin Bieber" the same artist.
     */
    private fun key(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        // Catalogues stack qualifiers: "... (Original Version) [Remastered]".
        repeat(3) {
            val cut = TRAILING_QUALIFIER.replace(s, "").trim()
            if (cut == s) return@repeat
            s = cut
        }
        // A crossed L is its own letter rather than an L with a mark, so NFD
        // leaves it alone and the sweep below would delete it outright -
        // "Podsiadlo" would then fail to match "Podsiadło", which for a Polish
        // station is the common spelling difference rather than an exotic one.
        val flattened = s.lowercase(Locale.ROOT).replace('ł', 'l')
        return Normalizer.normalize(flattened, Normalizer.Form.NFD)
            .replace(COMBINING_MARK, "")
            .replace(NON_ALPHANUMERIC, "")
    }

    private val TRAILING_QUALIFIER = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
    private val COMBINING_MARK = Regex("""\p{Mn}""")
    private val NON_ALPHANUMERIC = Regex("""[^a-z0-9]""")
}
