package net.mspanc.twinsenradio.data

/**
 * What should appear in each line of the description on the dashboard.
 *
 * The line breakdown comes directly from measurements taken in the Passat
 * (2026-08-11, see FINDINGS.md). The Active Info Display reads three metadata
 * fields:
 *
 *   top line      <- subtitle
 *   middle        <- description
 *   bottom        <- displayTitle
 *
 * The Android Auto central screen reads the same fields, except it only
 * shows two of them: the large line is displayTitle, the small one is
 * subtitle. It doesn't show the description at all.
 *
 * The conclusion that governs this whole class: **AID cannot be controlled
 * independently of the central screen**. Whatever we put in the top or
 * bottom line will appear in both places. So a clock in a text line will
 * also be visible on the central screen, and this is a deliberate
 * trade-off, not a bug. The middle line is the only one AID has exclusively.
 */
enum class LineContent(val label: String) {
    // The order of these is frozen. A choice is stored as an ordinal, so
    // moving an entry silently rewrites the layout of anybody who upgrades -
    // new entries go on the end, never in the middle.
    TITLE("Tytuł utworu"),
    ARTIST("Wykonawca"),
    ARTIST_ALBUM("Wykonawca · płyta"),
    TITLE_ALBUM("Tytuł · płyta"),
    TRACK_FULL("Wykonawca · tytuł"),
    STATION("Nazwa stacji"),
    CLOCK("Godzina"),
    EMPTY("Puste"),
    SLOGAN("Slogan stacji"),
    DATE("Data"),
    CLOCK_DATE("Godzina · data"),
    ALBUM("Płyta"),
    PROGRAMME("Audycja (z ramówki)"),
    HOSTS("Prowadzący (z ramówki)");

    /**
     * The things this entry actually puts on the line.
     *
     * Every entry still on offer is now a single part, so this reduces to
     * equality - but it is what got us there. The retired composites were two
     * parts each and collided with their own halves without equalling them:
     * pairing "Godzina · data" with "Data" printed the date twice (seen on the
     * phone, 11.09.2026), which reads as a fault rather than a layout. The rule
     * stays because it is what keeps the list of offered entries honest if one
     * of them ever grows a second part again.
     */
    val parts: Set<Part>
        get() = when (this) {
            TITLE -> setOf(Part.TITLE)
            ARTIST -> setOf(Part.ARTIST)
            ARTIST_ALBUM -> setOf(Part.ARTIST, Part.ALBUM)
            TITLE_ALBUM -> setOf(Part.TITLE, Part.ALBUM)
            TRACK_FULL -> setOf(Part.ARTIST, Part.TITLE)
            STATION -> setOf(Part.STATION)
            CLOCK -> setOf(Part.CLOCK)
            SLOGAN -> setOf(Part.SLOGAN)
            DATE -> setOf(Part.DATE)
            CLOCK_DATE -> setOf(Part.CLOCK, Part.DATE)
            ALBUM -> setOf(Part.ALBUM)
            PROGRAMME -> setOf(Part.PROGRAMME)
            HOSTS -> setOf(Part.HOSTS)
            // Nothing to collide with, which is why it is always on offer.
            EMPTY -> emptySet()
        }

    /** Whether putting these two on one line would say something twice. */
    fun overlaps(other: LineContent): Boolean = parts.any { it in other.parts }

    enum class Part { TITLE, ARTIST, ALBUM, STATION, SLOGAN, CLOCK, DATE, PROGRAMME, HOSTS }

    companion object {
        /**
         * What can go on a line while a track is playing.
         *
         * [SLOGAN] is missing on purpose: the station's own announcement belongs
         * to the gap between tracks, and the last one we heard put next to a
         * playing song would be stale by definition.
         */
        val WHEN_PLAYING = listOf(
            TITLE, ARTIST, ALBUM, STATION, CLOCK, DATE, EMPTY
        )

        /**
         * What can go on a line when no track is playing.
         *
         * Everything that names a track is gone - there is none, and offering
         * the choice would only promise an empty line. What remains is what the
         * app still knows at that moment.
         *
         * [PROGRAMME] and [HOSTS] are here and not in [WHEN_PLAYING]: while a
         * record plays the lines are about the record, and the programme is
         * what fills the gap when the presenter talks.
         */
        val WHEN_IDLE = listOf(CLOCK, DATE, STATION, SLOGAN, PROGRAMME, HOSTS, EMPTY)

        /**
         * Entries that are no longer offered, because a line can now hold two
         * things and these were two things in a trenchcoat.
         *
         * They stay in the enum: the order is frozen, and a stored choice has
         * to keep parsing. [expandedBy] is what they become on the way out.
         * "Wykonawca · tytuł" is reproducible exactly now that the separator is
         * the dot; "Godzina · data" always was.
         *
         * Their own redundancy is what forced [overlaps] into existence - they
         * collided with their halves without equalling them. With [ALBUM] added
         * as an entry of its own, the last two could go the same way, and every
         * line is now assembled from parts rather than picked from a list of
         * pre-joined pairs.
         */
        val RETIRED = mapOf(
            CLOCK_DATE to (CLOCK to DATE),
            TRACK_FULL to (ARTIST to TITLE),
            ARTIST_ALBUM to (ARTIST to ALBUM),
            TITLE_ALBUM to (TITLE to ALBUM)
        )

        /**
         * A stored pair, with a retired composite unfolded into the two halves
         * it always was. Read-only: nothing is written back, so a downgrade
         * still finds the file it left.
         */
        fun expandedBy(primary: LineContent, secondary: LineContent): LineSpec =
            RETIRED[primary]?.let { (a, b) -> LineSpec(a, b) } ?: LineSpec(primary, secondary)

        /**
         * Labels for a picker over [choices]. [ALBUM] appends the year whenever the catalog knows it and the "add year"
         * setting is on - the suffix here just reflects that current setting, so
         * the list doesn't silently promise a year it won't show.
         */
        fun labels(choices: List<LineContent>, includeYear: Boolean): List<String> = choices.map {
            when (it) {
                ALBUM -> it.label + if (includeYear) " (z rokiem)" else " (bez roku)"
                else -> it.label
            }
        }

        fun at(index: Int) = entries.getOrElse(index) { TITLE }
    }
}

/**
 * What fills the picture slot - the square the head unit draws next to the
 * description, and the phone puts on the playback screen.
 *
 * This used to be three settings pulling against each other: a clock face, a
 * switch deciding whether that clock appeared always or only when no cover art
 * was found, and a delivery mode whose "no artwork" value was really a fourth
 * kind of content. Which of them won, in which state, was something you worked
 * out by trying it. Now it is one choice per state, alongside the lines.
 */
enum class ArtContent(val label: String) {
    /**
     * The track's cover art, and the station's logo when the catalogue found
     * none. The fallback is part of the choice rather than another setting:
     * a blank square where a cover was expected reads as a fault, and the logo
     * is the only other picture that is certainly about what is playing.
     */
    COVER("Okładka płyty, inaczej logo"),
    LOGO("Logo stacji"),
    CLOCK_DIGITAL("Zegar cyfrowy"),
    CLOCK_ANALOG("Zegar analogowy"),

    /**
     * Retired 30.10.2026: a playing station is never without a picture - an
     * empty square reads as artwork that failed to load, and a faded logo means
     * "not playing". No longer offered; [at] reads a stored one as [LOGO]. It
     * stays in the enum only because the choice is stored as an ordinal, and
     * [HOST_PHOTO] after it must keep its number.
     */
    EMPTY("Puste"),

    /**
     * The presenter's photo from the station's programme guide, and the logo
     * where there is none - another station, a night of music with nobody at
     * the desk who has a face on the website, or the news, which belongs to the
     * station rather than to the programme it interrupts.
     */
    HOST_PHOTO("Zdjęcie prowadzącego, inaczej logo");

    /** The face to draw, or null when this choice isn't a clock at all. */
    val clockFace: ClockFace?
        get() = when (this) {
            CLOCK_DIGITAL -> ClockFace.DIGITAL
            CLOCK_ANALOG -> ClockFace.ANALOG
            else -> null
        }

    companion object {
        /** What the picker offers, in order; a position in it is not an ordinal. */
        val OFFERED = entries.filter { it != EMPTY }

        val LABELS get() = OFFERED.map { it.label }

        fun at(index: Int) = entries.getOrElse(index) { COVER }.let { if (it == EMPTY) LOGO else it }
    }
}

/**
 * What goes on the three lines, in one of the two states the player can be in.
 *
 * There are two of these because the two states have nothing in common: with a
 * track playing the lines are about the track, and between tracks there is no
 * track to be about. Before this split, a line set for "Tytuł utworu" simply
 * went blank in the gaps, and the only way to get a station name there was an
 * automatic fallback hidden inside the clock option - which meant the layout
 * between tracks was something you inherited rather than chose.
 */
data class LineSet(
    val top: LineSpec,
    val middle: LineSpec,
    val bottom: LineSpec
) {
    fun contentFor(line: Line): LineSpec = when (line) {
        Line.TOP -> top
        Line.MIDDLE -> middle
        Line.BOTTOM -> bottom
    }

    fun all(): List<LineContent> = listOf(top, middle, bottom).flatMap { it.all() }

    /** Whether any line puts [part] on the dashboard. */
    fun shows(part: LineContent.Part): Boolean = all().any { part in it.parts }
}

/**
 * One line: up to two things, joined by a dot.
 *
 * Three fields is all the head unit gives us, and several of the choices are
 * short enough to leave most of a line empty - a clock is five characters.
 * Pairing two of them puts the time and the date, or the station and its
 * slogan, where one of them used to sit alone.
 *
 * [secondary] is [LineContent.EMPTY] by default, and then the line is exactly
 * what it always was - one thing, no separator. The separator only appears
 * between two things that are both there; a half-filled pair must not leave a
 * dot hanging off the end of the text, because on a dashboard that reads as
 * the line having been cut off.
 */
data class LineSpec(
    val primary: LineContent,
    val secondary: LineContent = LineContent.EMPTY
) {
    fun all(): List<LineContent> = listOf(primary, secondary)
}

/**
 * Which line on the dashboard. Order as on the AID, from the top.
 */
enum class Line(val label: String, val hint: String) {
    TOP(
        "Wiersz 1 — górny",
        "Na AID linia górna. Na ekranie centralnym mała linia pod tytułem."
    ),
    MIDDLE(
        "Wiersz 2 — środkowy",
        "Widoczny wyłącznie na AID. ReplaIO wstawia tu nazwę stacji, aplikacja RNŚ zostawia pusty."
    ),
    BOTTOM(
        "Wiersz 3 — dolny",
        "Na AID linia pogrubiona. Na ekranie centralnym duża linia."
    )
}

/**
 * Which face [net.mspanc.twinsenradio.playback.ClockArt] draws.
 *
 * No longer a setting of its own - [ArtContent] is what the user picks, and
 * this is the drawing parameter it resolves to. [NONE] survives only because
 * the old stored key still has to be read, once, to translate it.
 */
enum class ClockFace {
    NONE,
    DIGITAL,
    ANALOG;

    companion object {
        fun at(index: Int) = entries.getOrElse(index) { NONE }
    }
}

/** Color scheme for the clock drawn in place of the artwork. */
object ClockColors {

    val BACKGROUNDS: List<Pair<String, Int>> = listOf(
        "Czarne" to 0xFF000000.toInt(),
        "Granatowe (motyw aplikacji)" to 0xFF0B3D91.toInt(),
        "Ciemnoszare" to 0xFF202124.toInt(),
        "Białe" to 0xFFFFFFFF.toInt()
    )

    /** null means automatic selection, contrasting with the background. */
    val FOREGROUNDS: List<Pair<String, Int?>> = listOf(
        "Auto — kontrastowo do tła" to null,
        "Białe" to 0xFFFFFFFF.toInt(),
        "Czarne" to 0xFF000000.toInt(),
        "Bursztynowe" to 0xFFF2A900.toInt()
    )

    fun background(index: Int) = BACKGROUNDS.getOrElse(index) { BACKGROUNDS[0] }.second

    /**
     * Color of the digits and hands. For "auto", we compute the background's
     * brightness using the luminance formula and pick black or white - the
     * same thing every sensible theming system does, avoiding illegible
     * white-on-white.
     */
    fun foreground(index: Int, backgroundColor: Int): Int {
        FOREGROUNDS.getOrElse(index) { FOREGROUNDS[0] }.second?.let { return it }
        val r = (backgroundColor shr 16 and 0xFF) / 255.0
        val g = (backgroundColor shr 8 and 0xFF) / 255.0
        val b = (backgroundColor and 0xFF) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    val BACKGROUND_LABELS get() = BACKGROUNDS.map { it.first }
    val FOREGROUND_LABELS get() = FOREGROUNDS.map { it.first }
}

/**
 * Complete set of description settings: what goes in which line, and whether
 * a clock should replace the artwork.
 */
data class Presentation(
    /** Lines used while a track is playing. */
    val playing: LineSet,
    /** Lines used when it isn't - an ad, the station talking, or silence. */
    val idle: LineSet,
    /** The picture slot, chosen per state just like the lines. */
    val artPlaying: ArtContent,
    val artIdle: ArtContent
) {
    fun artFor(trackPlaying: Boolean): ArtContent = if (trackPlaying) artPlaying else artIdle

    /**
     * @param trackPlaying whether a real track is on air right now - an ad or a
     *   station announcing itself counts as no track, because neither has a
     *   title or an artist to put on a line.
     */
    fun setFor(trackPlaying: Boolean): LineSet = if (trackPlaying) playing else idle

    /**
     * Whether the layout needs refreshing every minute at all.
     *
     * The date is in here too. It changes once a day rather than once a minute,
     * but there is no separate tick for it, and a line reading yesterday until
     * the next track is worse than a redundant refresh.
     */
    val needsClock: Boolean
        get() = listOf(artPlaying, artIdle).any { it.clockFace != null } ||
            (playing.all() + idle.all()).any {
                it == LineContent.CLOCK || it == LineContent.DATE || it == LineContent.CLOCK_DATE
            } ||
            usesProgramme

    /**
     * Whether anything here comes from the programme guide. Such a layout
     * changes on the hour without anybody saying anything - a new programme,
     * the news starting and ending - so it needs the minute tick as much as a
     * clock does.
     */
    val usesProgramme: Boolean
        get() = listOf(artPlaying, artIdle).any { it == ArtContent.HOST_PHOTO } ||
            idle.shows(LineContent.Part.PROGRAMME) || idle.shows(LineContent.Part.HOSTS)

    companion object {
        /**
         * Default while a track plays: artist with album on top, station name in
         * the middle, title at the bottom. The middle line gets the station
         * name, because it's the only spot visible exclusively on the AID -
         * putting anything there that already appears in the other lines would
         * waste the one free line.
         */
        val DEFAULT_PLAYING = LineSet(
            top = LineSpec(LineContent.ARTIST, LineContent.ALBUM),
            middle = LineSpec(LineContent.STATION),
            bottom = LineSpec(LineContent.TITLE)
        )

        /**
         * Default between tracks. Chosen to say something useful rather than to
         * reproduce what the old single-set layout happened to produce: the time
         * where the artist was, the station where it already was, and on the
         * line the AID prints boldest the programme and who presents it - or,
         * where there is no programme guide, whatever the station is saying
         * for itself.
         */
        val DEFAULT_IDLE = LineSet(
            top = LineSpec(LineContent.CLOCK),
            middle = LineSpec(LineContent.STATION),
            bottom = LineSpec(LineContent.PROGRAMME, LineContent.HOSTS)
        )

        val DEFAULT = Presentation(
            playing = DEFAULT_PLAYING,
            idle = DEFAULT_IDLE,
            // A record's cover while it plays, the station's own mark otherwise.
            artPlaying = ArtContent.COVER,
            artIdle = ArtContent.LOGO
        )
    }
}
