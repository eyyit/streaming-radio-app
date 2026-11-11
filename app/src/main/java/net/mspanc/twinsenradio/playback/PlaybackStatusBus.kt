// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A simple information channel between the playback service and the phone
 * UI. Both live in the same process, so there's no point routing this
 * through a Binder.
 */
object PlaybackStatusBus {

    private val missingTrackGrace = MissingTrackInfoGrace()
    private var actuallyPlaying = false

    fun resetMissingTrackInfo() { missingTrackGrace.reset() }

    fun setIcyMetadataTiming(intervalBytes: Int, bitrateBitsPerSecond: Int) {
        missingTrackGrace.setIcyHeaders(intervalBytes.toLong(), bitrateBitsPerSecond.toLong())
    }

    fun setActuallyPlaying(value: Boolean) {
        actuallyPlaying = value
        updateMissingTrackInfo()
    }

    fun missingTrackInfoText(nowMs: Long): String? = missingTrackGrace.text(nowMs)

    private fun updateMissingTrackInfo() {
        val now = _nowPlaying.value
        val content = now?.takeUnless { it.isStationSelfTitle || it.isAd || it.isControlMarker }
        missingTrackGrace.update(_stationId.value, actuallyPlaying,
            !content?.songTitle.isNullOrBlank(), !content?.artist.isNullOrBlank(),
            SystemClock.elapsedRealtime())
    }

    private val _stationId = MutableStateFlow<String?>(null)
    val stationId: StateFlow<String?> = _stationId

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    /**
     * The cover art of the current track, regardless of what was sent to
     * Android Auto. The phone should always show the cover art or the
     * station logo - the clock replacing the cover art is a feature meant
     * only for the screen in the car.
     */
    private val _trackArtworkLoading = MutableStateFlow(false)
    val trackArtworkLoading: StateFlow<Boolean> = _trackArtworkLoading
    fun setTrackArtworkLoading(value: Boolean) { _trackArtworkLoading.value = value }

    private val _coverArtUrl = MutableStateFlow<String?>(null)
    val coverArtUrl: StateFlow<String?> = _coverArtUrl

    fun setCoverArt(url: String?) {
        _coverArtUrl.value = url
    }

    /** What the catalog knows about the track - label and year, needed on the phone too. */
    private val _trackInfo = MutableStateFlow<CoverArtLookup.TrackInfo?>(null)
    val trackInfo: StateFlow<CoverArtLookup.TrackInfo?> = _trackInfo

    fun setTrackInfo(info: CoverArtLookup.TrackInfo?) {
        _trackInfo.value = info
    }

    /**
     * The programme the last metadata push was built with. Published rather than
     * asked for again, so the phone shows the programme the dashboard got -
     * asking the guide a second time could land on the other side of the hour.
     */
    private val _onAir = MutableStateFlow<OnAir?>(null)
    val onAir: StateFlow<OnAir?> = _onAir

    fun setOnAir(value: OnAir?) {
        _onAir.value = value
    }

    /**
     * What is actually coming through the stream - codec, bitrate, channels,
     * from the decoder. For the phone only; in the car there's no field to
     * display it in. Null until the first frame is decoded.
     */
    private val _quality = MutableStateFlow<StreamQuality?>(null)
    val quality: StateFlow<StreamQuality?> = _quality

    fun setQuality(value: StreamQuality?) {
        _quality.value = value
    }

    /**
     * Whether the player is meant to be playing - `playWhenReady`. False is
     * "nie gra": before play, or paused. Buffering and reconnecting are still
     * true; they are trying to play, and the dashboard says so in its own line.
     */
    private val _wantsToPlay = MutableStateFlow(false)
    val wantsToPlay: StateFlow<Boolean> = _wantsToPlay

    fun setWantsToPlay(value: Boolean) {
        _wantsToPlay.value = value
    }

    /**
     * Silent for longer than the grace a tunnel gets, although we want to play -
     * no signal, or a station that won't answer. Shown as "nie gra" too, with
     * the reason in the status; see RadioService.checkSilence.
     */
    private val _lostAir = MutableStateFlow(false)
    val lostAir: StateFlow<Boolean> = _lostAir

    fun setLostAir(value: Boolean) {
        _lostAir.value = value
    }

    /**
     * Where joining a station has got to - see RadioService.joinPhase. One
     * value, not two flags: the screens redraw on every change, and two flags
     * set one after the other showed the moment between them - the logo the
     * whole thing exists to keep off the screen (seen 30.10.2026 on the phone).
     */
    enum class Join {
        NONE,

        /** The station hasn't said what is on: only its name, and no picture. */
        WAITING,

        /** A song is known, its cover is not: its text, and still no picture. */
        COVER
    }

    private val _join = MutableStateFlow(Join.NONE)
    val join: StateFlow<Join> = _join

    fun setJoin(value: Join) {
        _join.value = value
    }

    /** Only the station's name - not the gap's programme, nor "nie gra". */
    fun joining(): Boolean = join.value == Join.WAITING

    /** The picture isn't decided yet: the slot stays empty until it is. */
    fun artPending(): Boolean = join.value != Join.NONE

    /** "Nie gra", the one look: before play, paused, or silent past the grace. */
    fun notPlaying(): Boolean =
        stationId.value == null || lostAir.value || (!wantsToPlay.value && !joining())

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    /**
     * Two things have an opinion about the status, and they answer different
     * questions: the player says what it is doing, [ReconnectController] says
     * whether there is a network to do it over. They are kept apart because one
     * field had them overwriting each other - a reconnect drives the player into
     * BUFFERING, and that arrived after RECONNECTING and erased it, so the only
     * state worth reporting was the one that never got shown.
     */
    private var fromPlayer = Status.IDLE

    /** null when the network has no complaint. */
    private var fromNetwork: Status? = null

    enum class Status {
        IDLE, CONNECTING, BUFFERING, PLAYING, RECONNECTING, WAITING_FOR_NETWORK,

        /** The network is up, but the station is silent - usually a decommissioned stream URL. */
        STATION_UNREACHABLE
    }

    fun setStation(id: String?) {
        if (_stationId.value != id) {
            // Before the station itself: anything that redraws on the new
            // station must already see that it is being joined, or it draws the
            // logo for the instant before the service says so.
            if (id != null) _join.value = Join.WAITING
            _stationId.value = id
            _nowPlaying.value = null
            _coverArtUrl.value = null
            _trackInfo.value = null
            _onAir.value = null
            // A new station means a new stream - the old quality stops being
            // valid immediately, and we'll only learn the new one after the
            // first frame from the decoder.
            _quality.value = null
            missingTrackGrace.reset()
            updateMissingTrackInfo()
        }
    }

    fun setNowPlaying(np: NowPlaying?) {
        _nowPlaying.value = np
        updateMissingTrackInfo()
    }

    fun setPlayerStatus(s: Status) {
        fromPlayer = s
        publish()
    }

    fun setNetworkStatus(s: Status?) {
        fromNetwork = s
        publish()
    }

    /**
     * Trouble outranks. Buffering during a reconnect is the symptom, not the
     * news - what the driver needs to read is why the music stopped.
     */
    private fun publish() {
        _status.value = fromNetwork ?: fromPlayer
    }
}
