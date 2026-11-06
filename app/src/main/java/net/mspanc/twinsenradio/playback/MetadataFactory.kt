package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtContent
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.LineSpec
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Presentation
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationNames
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.data.StationText
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the metadata that goes into the MediaSession, and from there to Android Auto
 * and on to the dashboard display.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MetadataFactory(private val context: Context, private val prefs: Prefs) {

    /**
     * Whether a head unit is listening right now.
     *
     * The presentation settings describe a dashboard, not a phone. The clearest
     * case is the clock face: it replaces the artwork because the instrument
     * cluster has nowhere else to put the time. Sent unconditionally, it also
     * landed in the phone's notification shade - a drawn clock sitting next to
     * the system's own clock, with the station's logo nowhere to be seen.
     *
     * Fixing that in the notification is not possible: the shade's media panel
     * reads its picture from the media session, the same metadata the head unit
     * reads, and it ignores the notification's own large icon (measured
     * 10.09.2026 - a notification provider that demonstrably swapped the icon
     * changed nothing on screen). One session, one set of artwork.
     *
     * So the rule is applied where it belongs: only while something is actually
     * driving. With no head unit attached the metadata carries the cover art or
     * the station logo, which is what every screen of the phone wants anyway.
     *
     * Only [RadioService] sets this; the copies the UI builds for logo lookups
     * leave it false, which is correct for them.
     */
    @Volatile
    var carAttached: Boolean = false

    private val artworkBytesCache = HashMap<String, ByteArray?>()

    /** Metadata for a browse-list item (Android Auto renders a tile from it). */
    fun forBrowseItem(station: Station): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(station.name)
            .setDisplayTitle(station.name)
            .setSubtitle(StationText.subtitle(station))
            .setDescription(StationText.subtitle(station))
            .setArtist(station.genre)
            .setStation(station.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(logoUri(station))
            .build()

    fun forFolder(title: String, mediaType: Int, artwork: Uri? = null): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setArtworkUri(artwork)
            .build()

    /**
     * Metadata for the currently playing station.
     *
     * In diagnostic mode, every field gets its own Polish name - this is the
     * version used to figure out the layout on the AID in the Passat.
     */
    fun forPlayback(
        station: Station,
        now: NowPlaying?,
        coverArtUrl: String? = null,
        trackInfo: CoverArtLookup.TrackInfo? = null,
        onAir: OnAir? = null,
        stopped: Boolean = false,
        standby: java.io.File? = null
    ): MediaMetadata {
        if (stopped && !net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) return forStopped(station, standby)
        val b = MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        if (prefs.diagnosticMode) {
            val withApi = prefs.diagnosticShowApiName
            val clock = clockText()

            // Each field carries its own name AND the clock, e.g. "TYT.WYSW 16:44".
            // A single drive then answers two questions at once: which field
            // the head unit shows, and whether it refreshes it during playback
            // or freezes it at the value from when the track started.
            fun v(api: String): String {
                val field = DiagnosticFields.TEXT.first { it.api == api }
                return DiagnosticFields.value(field, withApi) + " " + clock
            }

            b.setTitle(v("title"))
                .setArtist(v("artist"))
                .setAlbumTitle(v("albumTitle"))
                .setAlbumArtist(v("albumArtist"))
                .setDisplayTitle(v("displayTitle"))
                .setSubtitle(v("subtitle"))
                .setDescription(v("description"))
                .setStation(v("station"))
                .setGenre(v("genre"))
                .setComposer(v("composer"))
                .setWriter(v("writer"))
                .setConductor(v("conductor"))
                .setCompilation(v("compilation"))
                .setTrackNumber(DiagnosticFields.TRACK_NUMBER)
                .setTotalTrackCount(DiagnosticFields.TOTAL_TRACKS)
                .setDiscNumber(DiagnosticFields.DISC_NUMBER)
                .setTotalDiscCount(DiagnosticFields.TOTAL_DISCS)
                .setRecordingYear(DiagnosticFields.RECORDING_YEAR)
                .setReleaseYear(DiagnosticFields.RELEASE_YEAR)
        } else {
            val p = prefs.presentation

            // We fix up the text once, in one place - all lines and semantic
            // fields then use the same strings. While at it, we also
            // straighten out the order when a station broadcasts "title - artist"
            // instead of the other way round (Jacaranda FM).
            val title = now?.let { displayTitle(it, trackInfo) }.orEmpty()
            val artist = now?.let { displayArtist(it, trackInfo) }.orEmpty()

            // Which of the two layouts applies. An ad or a station announcing
            // itself is "no track": neither has a title or an artist, so the
            // lines that name one would go blank, and that blankness is exactly
            // what the second set exists to fill.
            val lines = p.setFor(now?.isRealSong == true)

            val top = lineOf(lines.top, station, now, trackInfo, title, artist, onAir)
            val bottom = lineOf(lines.bottom, station, now, trackInfo, title, artist, onAir)

            // The middle line is only visible on the AID - the one place where a
            // warning can go without cluttering the central screen too. What the
            // state of the connection has to say there, and when it says nothing,
            // is [StatusLine]'s to decide.
            val middle = StatusLine.textFor(context, PlaybackStatusBus.status.value)
                ?: lineOf(lines.middle, station, now, trackInfo, title, artist, onAir)

            // The fields the head unit actually renders. Measured in the Passat
            // (FINDINGS.md): the AID reads subtitle / description / displayTitle,
            // the central screen reads displayTitle as the large line and subtitle as the small one.
            b.setSubtitle(drawable(top))
                .setDescription(drawable(middle))
                .setDisplayTitle(drawable(bottom))
                // Semantic fields. They don't appear on any screen in the car,
                // but they describe what's actually playing - other systems may
                // reach for them, so we keep them honest rather than as copies of the lines.
                .setTitle(titleLine(now, title))
                .setArtist(artist)
                .setAlbumTitle(trackInfo?.album.orEmpty())
                .setStation(station.name)
                .setGenre(station.genre)
            if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
                val lines = AutomotiveText.playback(station.name, titleLine(now, title), artist,
                    trackInfo?.album, PlaybackStatusBus.quality.value,
                    when (val parent = prefs.activeBrowseParent) {
                        RadioService.NODE_FAVOURITES -> context.getString(R.string.node_favourites)
                        RadioService.NODE_ALL -> context.getString(R.string.node_all)
                        else -> StationRepository.get(context).playlists()
                            .firstOrNull { "/playlist/${it.id}" == parent }?.name.orEmpty()
                    }, PlaybackStatusBus.trackArtworkLoading.value)
                b.setTitle(lines.title)
                    .setDisplayTitle(station.name)
                    .setSubtitle(lines.subtitle)
                    .setDescription(lines.description)
                    .setExtras(android.os.Bundle().apply {
                        putString("com.impostorlabs.streamingradio.STATION_NAME", station.name)
                        PlaybackStatusBus.quality.value?.let {
                            putString("com.impostorlabs.streamingradio.CODEC", it.codec)
                            putInt("com.impostorlabs.streamingradio.BITRATE_KBPS", it.bitrateKbps)
                            putInt("com.impostorlabs.streamingradio.SAMPLE_RATE_HZ", it.sampleRateHz)
                            putInt("com.impostorlabs.streamingradio.CHANNELS", it.channels)
                        }
                    })
            }
        }

        applyArtwork(b, station, coverArtUrl, now?.isRealSong == true, onAir)
        return b.build()
    }

    /**
     * "Nie gra" - before play, or paused: the same state on the phone and in
     * the car.
     *
     * Nothing about what was on: a resume after a while joins the live stream
     * afresh (see RadioService.onPlayWhenReadyChanged), by when the track is
     * over and the programme may be another one, and a presenter's photo over
     * silence claims they are on air. What stays true is the station and what
     * it says about itself - so the gap layout, fed only the slogan it last
     * sent, and a picture faded back: the station's own one for this moment
     * where it publishes one, otherwise its logo at [LogoProvider.MUTED_ALPHA].
     */
    private fun forStopped(station: Station, standby: java.io.File?): MediaMetadata {
        val says = prefs.slogan(station.id)?.let { NowPlaying.standingBy(station.name, it) }
        val art = when {
            prefs.artworkMode == ArtworkMode.NONE -> null
            standby != null -> LogoProvider.fileUri(context, "standby-${station.id}", standby)
            else -> mutedLogoUri(station)
        }
        return forPlayback(station, says).buildUpon()
            .setArtworkData(null, null)
            .setArtworkUri(art)
            .build()
    }

    /**
     * A station just joined, before it has said what is on: the station's name
     * and an empty picture slot - not the gap's programme and presenter's
     * photo, which would flash up over a song the station is about to name,
     * and not the logo either, which a cover would replace a moment later.
     * The picture goes up once, when it is known. Lines with nothing to say
     * stay blank.
     */
    fun forJoining(station: Station): MediaMetadata =
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) forBrowseItem(station)
        else withBlankArt(forPlayback(station, null))

    /**
     * The same description with the picture slot emptied - a song that is
     * known while its cover is still being looked up.
     *
     * Empty as a picture, not as a missing field: a head unit that receives no
     * artwork may simply not repaint the slot, and would leave the previous
     * station's cover there - the same reason empty lines go out as a
     * non-breaking space (see [drawable]).
     */
    fun withBlankArt(m: MediaMetadata): MediaMetadata =
        m.buildUpon()
            .setArtworkUri(null)
            .setArtworkData(blankArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

    private val blankArt: ByteArray by lazy {
        val bmp = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /**
     * The station's logo faded back - see [LogoProvider.mutedUriFor]. Built
     * from whichever logo the station actually has, in the order [logoUri]
     * picks it, so the faded one is always the one shown unfaded elsewhere.
     */
    fun mutedLogoUri(station: Station): Uri {
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
            return LogoProvider.mutedUriFor(context, station, logoUri(station))
        val source = customLogoFile(station)?.let { Uri.fromFile(it) }
            ?: station.logoUrl?.takeIf { prefs.onlineArtworkEnabled }?.let { Uri.parse(it) }
            ?: Uri.fromParts("res", logoResId(station).toString(), null)
        return LogoProvider.mutedUriFor(context, station, source)
    }

    /**
     * A line with nothing to say, in a form the head unit will actually draw.
     *
     * We send a complete set on every station change, and measured on the phone
     * it really is complete: after switching to a station that has announced
     * nothing, the session carries three empty lines and no trace of the
     * previous one. What is left on the AID is therefore the head unit's doing -
     * a field it receives empty is a field it can simply not repaint, and the
     * previous station's track stays where it was drawn.
     *
     * An empty string is not a value; a non-breaking space is. It draws as a
     * blank line and it replaces what was there. Non-breaking, because a plain
     * space is trimmed away on the road somewhere and lands as "" again.
     *
     * This is a hypothesis about the Passat, not a measurement - the AID cannot
     * be read from the desk. If it turns out the head unit was clearing the
     * lines all along, [BLANK] goes back to "" and nothing else changes.
     */
    private fun drawable(line: String): String = line.ifBlank { BLANK }

    /**
     * A whole line: its two halves, joined by a dot.
     *
     * The dot appears only between two halves that both have something in them.
     * A line ending in a dangling separator reads on a dashboard as text that
     * was cut off, which is a worse lie than a short line.
     */
    private fun lineOf(
        spec: LineSpec,
        station: Station,
        now: NowPlaying?,
        info: CoverArtLookup.TrackInfo?,
        title: String,
        artist: String,
        onAir: OnAir?
    ): String {
        val first = lineText(spec.primary, station, now, info, title, artist, onAir)
        // The picker won't offer a second half that repeats the first, but a
        // stored pair can outlive the version that wrote it - and "Godzina ·
        // data" next to "Data" printed the date twice, which reads as a fault
        // rather than a layout. Overlap, not equality: the two entries that did
        // that were never the same entry.
        if (spec.secondary.overlaps(spec.primary)) return first
        val second = shortenSecond(spec.secondary, lineText(spec.secondary, station, now, info, title, artist, onAir))
        // Distinct, because a programme with no guide behind it says what the
        // slogan says - next to "Slogan stacji" that would be the same words twice.
        return listOf(first, second).filter { it.isNotBlank() }.distinct().joinToString(JOIN)
    }

    /**
     * Trims the second half of a line so it doesn't crowd out the first.
     *
     * The head unit cuts whatever doesn't fit, and it cuts from the end - so an
     * over-long second half doesn't lose itself, it loses the line. A station
     * called "Smooth FM 91.5 - Melbourne" put next to a track title takes the
     * title's room and gives back a city nobody needed.
     *
     * How much actually fits cannot be worked out from a character count: the
     * cluster draws a proportional font, so "IIII" and "mmmm" are different
     * widths at the same length. The budget below is therefore a judgement, not
     * a measurement - see FINDINGS.md, section 7.
     */
    private fun shortenSecond(content: LineContent, text: String): String {
        if (text.length <= SECOND_BUDGET) return text
        // A station has a shorter form that is still its name; everything else
        // can only be cut, and is cut on a word so the remnant stays readable.
        if (content == LineContent.STATION) {
            val short = StationNames.shortest(text)
            if (short.length <= SECOND_BUDGET) return short
            return clip(short)
        }
        return clip(text)
    }

    private fun clip(text: String): String {
        if (text.length <= SECOND_BUDGET) return text
        val cut = text.take(SECOND_BUDGET).substringBeforeLast(' ', "")
        return (cut.ifBlank { text.take(SECOND_BUDGET) }).trimEnd(' ', ',', '-', '·') + "…"
    }

    /**
     * The content of a single description line.
     *
     * The track-shaped entries still check [NowPlaying.isRealSong] even though
     * the picker no longer offers them for the between-tracks layout. A stored
     * choice outlives the version that wrote it, and a line reading the previous
     * song's title over an ad break is the exact fault this split was made to
     * remove - so the guard stays as a floor under the settings.
     */
    private fun lineText(
        content: LineContent,
        station: Station,
        now: NowPlaying?,
        info: CoverArtLookup.TrackInfo?,
        title: String,
        artist: String,
        onAir: OnAir?
    ): String = when (content) {
        LineContent.CLOCK -> clockText()
        LineContent.DATE -> dateText()
        LineContent.CLOCK_DATE -> "${clockText()} · ${dateText()}"
        LineContent.STATION -> station.name
        LineContent.SLOGAN -> stationSaysLine(now)
        LineContent.EMPTY -> ""
        // The programme fills a gap; over a record it would be a second
        // description competing with the real one. Without a guide - another
        // station, or the news - the line says what the slogan would.
        LineContent.PROGRAMME -> if (now?.isRealSong == true) "" else onAir?.name ?: stationSaysLine(now)
        LineContent.HOSTS -> if (now?.isRealSong == true) "" else onAir?.hosts.orEmpty()
        LineContent.TITLE -> titleLine(now, title)
        // Without the album RMF tacks onto the artist ("Wiktoria Kida / Ksiega"):
        // that has a line of its own now, and next to it the record was named
        // twice. The phone has always cut it the same way.
        LineContent.ARTIST -> if (now != null && now.isRealSong) composeArtistOnly(now, info) else ""
        LineContent.ALBUM ->
            if (now?.isRealSong == true) {
                info?.albumLabel(prefs.enrichWithYear).orEmpty()
            } else {
                ""
            }
        LineContent.ARTIST_ALBUM ->
            if (now?.isRealSong == true) {
                composeArtistLine(now, info, prefs.enrichWithYear)
            } else {
                ""
            }
        LineContent.TITLE_ALBUM ->
            if (now?.isRealSong == true) {
                composeTitleLine(now, info, prefs.enrichWithYear)
            } else {
                titleLine(now, title)
            }
        LineContent.TRACK_FULL ->
            if (now?.isRealSong == true) {
                listOf(artist, title).filter { it.isNotBlank() }.joinToString(JOIN)
            } else {
                // Outside of a track there's nothing to join - show the same thing as the title line
                titleLine(now, title)
            }
    }

    /**
     * The picture for the phone's own screens, by the same choice the dashboard
     * makes - null meaning the station's logo. A clock is [phoneClock]'s.
     *
     */
    fun phoneArtwork(trackPlaying: Boolean, coverArtUrl: String?, onAir: OnAir?): Uri? =
        if (!prefs.onlineArtworkEnabled) null else when (prefs.presentation.artFor(trackPlaying)) {
            ArtContent.COVER -> coverArtUrl?.let { Uri.parse(it) }
            ArtContent.HOST_PHOTO -> onAir?.photoUri
            ArtContent.LOGO, ArtContent.EMPTY, ArtContent.CLOCK_DIGITAL, ArtContent.CLOCK_ANALOG -> null
        }

    /**
     * The clock face for the phone's own screens, when the layout asks for one
     * in this state - in place of the cover, the logo, or the presenter's photo,
     * exactly as on the dashboard.
     *
     * The notification shade is the one place that still doesn't get it (see
     * [carAttached]): there it sat next to the system's own clock. The app's
     * screens are a choice the user made for the picture, and they get it.
     */
    fun phoneClock(trackPlaying: Boolean): Bitmap? {
        val face = prefs.presentation.artFor(trackPlaying).clockFace ?: return null
        val bg = ClockColors.background(prefs.clockBackground)
        return ClockArt.bitmap(face, bg, ClockColors.foreground(prefs.clockForeground, bg))
    }

    /**
     * Fills the picture slot according to the layout for the current state.
     *
     * One branch per choice, which is the whole point of [ArtContent]: this used
     * to be a clock face, an "always" switch and a delivery mode interacting,
     * and working out what would actually appear meant running it.
     *
     * Deliberately independent of diagnostic mode - that only swaps the text
     * fields for labels, and the picture should behave the same either way.
     */
    private fun applyArtwork(
        b: MediaMetadata.Builder,
        station: Station,
        coverArtUrl: String?,
        trackPlaying: Boolean,
        onAir: OnAir?
    ) {
        if (!prefs.onlineArtworkEnabled) { applyLogo(b, station); return }
        // A clock belongs to the dashboard - see [carAttached]. With no head
        // unit listening it would only put a drawn clock in the phone's own
        // notification, so those two choices fall back to what the phone wants.
        val chosen = prefs.presentation.artFor(trackPlaying)
        val art = if (chosen.clockFace != null && !carAttached) ArtContent.COVER else chosen

        when (art) {
            // Retired, and read as the logo before it gets here - see ArtContent.EMPTY.
            ArtContent.EMPTY -> applyLogo(b, station)

            ArtContent.CLOCK_DIGITAL, ArtContent.CLOCK_ANALOG -> {
                // Drawn on the fly, so there is no URI and it travels as bytes.
                val bg = ClockColors.background(prefs.clockBackground)
                val fg = ClockColors.foreground(prefs.clockForeground, bg)
                val face = art.clockFace ?: return
                ClockArt.pngBytes(face, bg, fg)?.let {
                    b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }

            ArtContent.COVER -> {
                if (coverArtUrl != null && prefs.onlineArtworkEnabled) {
                    b.setArtworkUri(Uri.parse(coverArtUrl))
                } else {
                    applyLogo(b, station)
                }
            }

            ArtContent.LOGO -> applyLogo(b, station)

            ArtContent.HOST_PHOTO -> {
                val photo = onAir?.photoUri
                if (photo != null) b.setArtworkUri(photo) else applyLogo(b, station)
            }
        }
    }

    /**
     * The station's logo, delivered the way [Prefs.artworkMode] asks for.
     *
     * That setting is about transport rather than content - some head units
     * refuse a URI and want the bytes - which is why it sits apart from
     * [ArtContent] and applies to whichever of them ends up here.
     */
    private fun applyLogo(b: MediaMetadata.Builder, station: Station) {
        when (prefs.artworkMode) {
            ArtworkMode.NONE -> Unit
            ArtworkMode.EMBEDDED_BYTES -> {
                val bytes = artworkBytes(station)
                if (bytes != null) {
                    b.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    b.setArtworkUri(logoUri(station))
                }
            }
            else -> b.setArtworkUri(logoUri(station))
        }
    }

    /**
     * The logo URI: a bundled resource or remote artwork from the M3U list.
     *
     * The bundled logo goes through [LogoProvider] rather than as an android.resource://
     * with a resource number - see the comment in that class: the numbers change
     * between versions, and Android Auto caches artwork by URI.
     */
    fun logoUri(station: Station): Uri {
        // The user's own custom artwork beats everything else
        customLogoFile(station)?.let { return LogoProvider.customUriFor(context, station, it) }
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) return StationArtworkStore.uri(context, station)
        station.logoUrl?.takeIf { prefs.onlineArtworkEnabled }?.let { return Uri.parse(it) }
        return LogoProvider.uriFor(context, station, logoResId(station))
    }

    /**
     * The logo URI to show on the phone. Unlike [logoUri] it may return a
     * `file://` URI - local screens read the file directly, without a middleman.
     */
    fun logoDisplayUri(station: Station): Uri? =
        customLogoFile(station)?.let { Uri.fromFile(it) }
            ?: if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) StationArtworkStore.uri(context, station)
            else station.logoUrl?.takeIf { prefs.onlineArtworkEnabled }?.let { Uri.parse(it) }

    private fun customLogoFile(station: Station): java.io.File? =
        prefs.customLogo(station.id)?.let { java.io.File(it) }?.takeIf { it.exists() }

    fun logoResId(station: Station): Int {
        val name = station.logo ?: return R.drawable.logo_placeholder
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.logo_placeholder
    }

    private fun artworkBytes(station: Station): ByteArray? =
        artworkBytesCache.getOrPut(station.id) {
            runCatching {
                val drawable = ContextCompat.getDrawable(context, logoResId(station)) ?: return@runCatching null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                    val size = ART_SIZE
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp)
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(c)
                    }
                }
                val scaled = if (bitmap.width > ART_SIZE || bitmap.height > ART_SIZE) {
                    Bitmap.createScaledBitmap(bitmap, ART_SIZE, ART_SIZE, true)
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }

    companion object {
        /** 512 px is a reasonable compromise: the HU gets a sharp image, and the Binder doesn't bloat. */
        private const val ART_SIZE = 512

        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE d.MM", Locale.forLanguageTag("pl"))

        /**
         * How much of a line the second half may take.
         *
         * A judgement rather than a measurement - the cluster font is
         * proportional, so no character count maps to a width. Chosen to clear
         * every ordinary station name ("Radio Nowy Swiat" is 16, "Jacaranda FM"
         * 12) while cutting the ones that are really a description.
         */
        private const val SECOND_BUDGET = 22

        /** The separator between two halves of a line, and inside a track line. */
        const val JOIN = " · "

        /** An empty line as a value the head unit can draw - see [drawable]. */
        private const val BLANK = "\u00A0"

        const val AD_LABEL = "Reklama"

        /**
         * What the phone's playback screen shows under the station name: a big
         * line, a smaller one under it, and the record.
         *
         * The phone's screen has its own shape, but not its own rules. It used to
         * keep a copy of them, and the copy drifted: the dashboard learned to call
         * the news by name and to show the programme and its presenters between
         * tracks, while the phone went on showing the bare slogan, or nothing. So
         * everything here is decided by the same layout and the same functions
         * the dashboard lines use - a part the dashboard doesn't show, the phone
         * doesn't either.
         *
         * Over a record: title, artist, album. Between records: what the
         * dashboard says about the gap - the programme, or the station's own words
         * (slogan, news, ad) - with the presenters under it.
         */
        fun forPhone(
            now: NowPlaying?,
            info: CoverArtLookup.TrackInfo?,
            onAir: OnAir?,
            p: Presentation,
            includeYear: Boolean
        ): PhoneText {
            if (now != null && now.isRealSong) {
                val lines = p.playing
                return PhoneText(
                    title = if (lines.shows(LineContent.Part.TITLE)) displayTitle(now, info) else "",
                    artist = if (lines.shows(LineContent.Part.ARTIST)) composeArtistOnly(now, info) else "",
                    album = if (lines.shows(LineContent.Part.ALBUM)) info?.albumLabel(includeYear).orEmpty() else ""
                )
            }
            val lines = p.idle
            val says = when {
                lines.shows(LineContent.Part.PROGRAMME) -> onAir?.name ?: stationSaysLine(now)
                lines.shows(LineContent.Part.SLOGAN) -> stationSaysLine(now)
                else -> ""
            }
            val hosts = if (lines.shows(LineContent.Part.HOSTS)) onAir?.hosts.orEmpty() else ""
            return PhoneText(title = says, artist = hosts.takeIf { it != says }.orEmpty(), album = "")
        }

        /**
         * What the station is saying for itself right now.
         *
         * Named "slogan" in the picker because that is what it usually is - "Pion i
         * poziom!" on RNS - but the news and an ad block belong here too. All three
         * are the same kind of thing: the station's own words standing in for a
         * track, and whichever of them is true at the moment is what that line
         * should carry.
         */
        private fun stationSaysLine(now: NowPlaying?): String = when {
            now?.isRealSong == true -> ""
            else -> titleLine(now, "")
        }

        /**
         * The title line. Outside of a track we insert whatever we know at that
         * moment: the news, the station's slogan, or an ad label - anything but an empty screen.
         */
        private fun titleLine(now: NowPlaying?, title: String): String = when {
            now?.isRealSong == true -> title
            now?.isNews == true -> "${now.slogan} — serwis informacyjny"
            now?.slogan != null -> now.slogan!!
            now?.isAd == true -> adText(now)
            else -> ""
        }

        private fun adText(now: NowPlaying): String {
            val seconds = now.adDurationMs / 1000
            return if (seconds > 0) "$AD_LABEL · ${seconds}s" else AD_LABEL
        }

        /**
         * Title and artist in a form fit for the screen: with corrected
         * order (when the station broadcasts it reversed) and tidied-up text.
         * Shared between the car and the phone so they don't drift apart from each other.
         */
        fun displayTitle(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val swapped = info?.looksSwapped(now.artist, now.songTitle) == true
            return joinedNames(TextCase.tidy(if (swapped) now.artist else now.songTitle, info?.trackName))
        }

        fun displayArtist(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val swapped = info?.looksSwapped(now.artist, now.songTitle) == true
            return joinedNames(TextCase.tidy(if (swapped) now.songTitle else now.artist, info?.artistName))
        }

        /**
         * Jacaranda FM joins collaborators with "[+]" - "BEYONCE [+] JAY Z",
         * "PITBULL [+] NE-YO [+] AFROJACK". Fine as a separator, odd on a
         * dashboard; shown as "&", the way the catalogue writes it and two
         * characters shorter than "and" on a line that runs out of room.
         *
         * Only on the way to the screen: the raw text keeps its "[+]", which is
         * what the catalogue lookup splits the names on (see CoverArtLookup).
         */
        fun joinedNames(text: String): String = text.replace(PLUS_JOIN, " & ")

        private val PLUS_JOIN = Regex("""\s*\[\+]\s*""")

        /**
         * The artist alone, without the album - for use where the album should go
         * on a separate line (the phone's playback screen), not after a dot.
         */
        fun composeArtistOnly(now: NowPlaying, info: CoverArtLookup.TrackInfo?): String {
            val artist = displayArtist(now, info)
            if (artist.isBlank() || info == null) return artist

            // Only trim the tail when the catalog confirms it's an album
            // name. Otherwise "Shimza / AR/CO / Kasango" would lose its third artist.
            val parts = artist.split(Regex("\\s+[/,]\\s+"))
            return if (parts.size > 1 && info.tailIsAlbum(parts.last())) {
                parts.dropLast(1).joinToString(" / ")
            } else {
                artist
            }
        }

        /**
         * @param includeYear whether the album gets its year appended, when the
         *   catalog knows it - see [Prefs.enrichWithYear]. The album itself
         *   always shows when we have one; this only controls the year suffix.
         */
        fun composeArtistLine(
            now: NowPlaying,
            info: CoverArtLookup.TrackInfo?,
            includeYear: Boolean
        ): String {
            val artistsOnly = composeArtistOnly(now, info)
            if (artistsOnly.isBlank() || info == null) return artistsOnly

            val album = info.albumLabel(includeYear) ?: return artistsOnly
            // We separate the release with a middle dot, not a slash - with several
            // artists separated by slashes there'd be no way to tell them apart from the album.
            return "$artistsOnly · $album"
        }

        /** Same idea as [composeArtistLine], but for the title instead of the artist. */
        fun composeTitleLine(
            now: NowPlaying,
            info: CoverArtLookup.TrackInfo?,
            includeYear: Boolean
        ): String {
            val titleOnly = displayTitle(now, info)
            if (titleOnly.isBlank() || info == null) return titleOnly

            val album = info.albumLabel(includeYear) ?: return titleOnly
            return "$titleOnly · $album"
        }

        /** Always a two-digit hour and minute, e.g. "09:07". */
        fun clockText(): String = LocalTime.now().format(CLOCK_FORMAT)

        /**
         * Day, month and weekday, e.g. "czw. 11.09".
         *
         * The weekday is the half a driver actually wants - the number is easy
         * to work out from a phone, the day of the week is what you lose track
         * of. Polish explicitly rather than by system locale: the rest of the
         * app's text is Polish regardless of how the phone is set.
         */
        fun dateText(): String = LocalDate.now().format(DATE_FORMAT)
    }
}

/** The text of the phone's playback screen - see [MetadataFactory.forPhone]. */
data class PhoneText(val title: String, val artist: String, val album: String)

/**
 * What came in the ICY (Icecast/SHOUTcast) metadata for the current stream.
 *
 * [isStationSelfTitle] denotes the case where the station put not a track but
 * its own name and slogan, or a show name, into that same field - e.g.
 * "Radio Nowy Świat - Pion i poziom!". A naive split on " - " then makes the
 * "artist" equal to the station name, and the name ends up on screen twice.
 */
data class NowPlaying(
    val raw: String,
    val artist: String?,
    val songTitle: String?,
    val isStationSelfTitle: Boolean = false,
    val isAd: Boolean = false,
    val adDurationMs: Long = 0,
    /** A station control marker, e.g. STOP_AD_BREAK - not content. */
    val isControlMarker: Boolean = false
) {
    /** Slogan or show name - what the station put in instead of a track. */
    val slogan: String? get() = if (isStationSelfTitle) songTitle else null

    /**
     * A news broadcast. RMF marks it as "RMF FM - FAKTY", other stations
     * use the words "wiadomosci", "informacje", "serwis". This isn't a track, but
     * it's worth calling it by name instead of showing an empty line.
     */
    val isNews: Boolean
        get() = slogan?.let { s -> NEWS_WORDS.any { s.contains(it, ignoreCase = true) } } == true

    /** Whether a track is really playing, as opposed to an ad, a marker, or the station's own announcement. */
    val isRealSong: Boolean
        get() = !isAd && !isStationSelfTitle && !isControlMarker && !songTitle.isNullOrBlank()

    companion object {
        /**
         * The station saying [slogan] about itself - what [parse] makes of
         * "Radio Nowy Swiat - Pion i poziom!". For "nie gra", which has no
         * stream to hear it from and takes the last one remembered.
         */
        fun standingBy(stationName: String, slogan: String) =
            NowPlaying("$stationName - $slogan", stationName, slogan, isStationSelfTitle = true)

        /**
         * A typical StreamTitle is "Artist - Title".
         *
         * @param stationName the station name, needed to detect its own slogan
         * @param rawBlock the whole ICY block - this is how we detect ads. RMF sends
         *   an empty StreamTitle along with adw_ad='true', adId, and durationMilliseconds.
         */
        fun parse(
            streamTitle: String?,
            stationName: String? = null,
            rawBlock: String? = null
        ): NowPlaying? {
            val block = rawBlock.orEmpty()
            val isAd = Regex("""adw_ad='true'""").containsMatchIn(block) ||
                Regex("""adId='[^']+'""").containsMatchIn(block)
            val adMs = Regex("""durationMilliseconds='(\d+)'""")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0

            val raw = streamTitle?.trim().orEmpty()

            // Stations mark ads in two ways and can switch between them:
            // RMF used to send a block with adw_ad='true', and now just sends
            // StreamTitle='Reklama'. Without this list, the word "Reklama" would end up in
            // the cover art search and come back with the cover of an Uzbek song by that
            // title.
            if (raw.isNotEmpty() && AD_WORDS.any { it.equals(raw, ignoreCase = true) }) {
                return NowPlaying(raw, null, null, isAd = true, adDurationMs = adMs)
            }

            // A hosting platform's default for a station that feeds it no titles
            // means exactly what an empty one does. Taken literally it would put
            // "Now Playing info goes here" on the dashboard and send it to the
            // catalogue as a track - all of RNS's mounts sent nothing else after
            // its studio move (27.10.2026).
            if (raw.isEmpty() || isPlaceholder(raw)) {
                // An empty title on its own carries nothing, but if it's accompanied
                // by an ad marker, that's concrete information worth showing.
                return if (isAd) NowPlaying("", null, null, isAd = true, adDurationMs = adMs) else null
            }

            // Control markers: RMF sends things like STOP_AD_BREAK in StreamTitle
            // to mark the end of an ad block. This isn't a track title -
            // taken literally it used to stay on screen together with the
            // previous song's cover art.
            if (CONTROL_MARKER.matches(raw)) {
                val upper = raw.uppercase()
                val breakStarts = upper.contains("START") && upper.contains("AD")
                return NowPlaying(
                    raw = raw,
                    artist = null,
                    songTitle = null,
                    isAd = isAd || breakStarts,
                    adDurationMs = adMs,
                    isControlMarker = true
                )
            }

            val dash = raw.indexOf(" - ")
            if (dash <= 0) {
                // Radio Zlote Przeboje separates the two with a slash instead:
                // "KIM CARNES / BETTE DAVIS EYES" (measured 11.09.2026). Without
                // this the whole thing became the title, the artist line stayed
                // empty, and the catalogue was asked a question with no artist
                // in it - which it answered with something else entirely.
                //
                // Only when there is no dash, and only for a single slash. A
                // slash usually joins artists - "Jonatan / Livka / Pezet" - but
                // those arrive alongside a dash, so the dash has already split
                // them off by the time we get here.
                val slash = raw.indexOf(" / ")
                if (slash > 0 && raw.indexOf(" / ", slash + 1) < 0) {
                    val who = raw.substring(0, slash).trim()
                    val what = raw.substring(slash + 3).trim()
                    val selfTitled = stationName != null && similar(who, stationName)
                    return NowPlaying(raw, who, what, selfTitled, isAd, adMs)
                }
                val selfTitled = stationName != null && similar(raw, stationName)
                return NowPlaying(raw, null, raw, selfTitled, isAd, adMs)
            }

            val left = raw.substring(0, dash).trim()
            val right = raw.substring(dash + 3).trim()
            val selfTitled = stationName != null && similar(left, stationName)
            return NowPlaying(raw, left, right, selfTitled, isAd, adMs)
        }

        /**
         * Only uppercase letters, digits, and underscores, no spaces - that's what
         * station control markers look like, not track titles.
         */
        private val CONTROL_MARKER = Regex("^[A-Z0-9][A-Z0-9_]{3,}$")

        /** Words used to recognize a news broadcast. */
        private val NEWS_WORDS = listOf(
            "fakty", "wiadomosci", "wiadomości", "informacje", "serwis", "news"
        )

        /** What a streaming platform sends when the station gave it no title (Revma/RCS). */
        private val PLACEHOLDERS = listOf("Now Playing info goes here")

        /** Whether the stream is saying, in so many words, that it has no title to give. */
        fun isPlaceholder(streamTitle: String?): Boolean {
            val raw = streamTitle?.trim().orEmpty()
            return PLACEHOLDERS.any { it.equals(raw, ignoreCase = true) }
        }

        /** Titles that actually denote an ad block, not a track. */
        private val AD_WORDS = listOf(
            "reklama", "reklamy", "reklamа",
            "spot reklamowy", "blok reklamowy",
            "advertisement", "advert", "commercial", "ad break", "ads"
        )

        /** A comparison resilient to diacritics, letter case, and the word "radio". */
        private fun similar(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase()
                .replace("ł", "l")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
                .replace("radio", "")
            val na = norm(a)
            val nb = norm(b)
            return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
        }
    }
}
