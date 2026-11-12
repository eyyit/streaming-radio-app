// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.LineSpec
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.ui.MainActivity

/**
 * Playback service that is also the browse tree source for Android Auto.
 *
 * Media3's MediaLibraryService exposes both the new session API and the old
 * MediaBrowserService, which Android Auto still uses - that's why the manifest
 * has two intent filters.
 */
@UnstableApi
class RadioService : MediaLibraryService() {
    /** Verbose station/track/controller details stay out of production logcat. */
    private fun debugInfo(tag: String, message: String): Int =
        if (net.mspanc.twinsenradio.BuildConfig.DEBUG) Log.i(tag, message) else 0

    private var stationArtworkJob: Job? = null
    private var stationArtworkPending = false

    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var player: ExoPlayer
    private val stationTraversal = StationTraversal()
    private lateinit var session: MediaLibrarySession
    private lateinit var reconnect: ReconnectController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastRawIcyTitle: String? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTick: Runnable? = null

    /** Armed while buffering; fires if the wait turns into a real connection attempt. */
    private var connectingAnnounce: Runnable? = null
    private var bufferingSinceMs = 0L

    /**
     * When playback was paused, on the monotonic clock; 0 when it is not paused,
     * or when whatever comes next will be a fresh connection anyway.
     */
    private var pausedAtMs = 0L

    /** Since when we have wanted to play and heard nothing; 0 when we hear something. */
    private var silentSinceMs = 0L

    /** Silent past [SILENCE_GRACE_MS] - "nie gra" until sound comes back. */
    private var lostAir = false
    private var silenceCheck: Runnable? = null

    /**
     * Joining a station: until when we wait for it to say what is on (0 when
     * not joining), whether it has, and - for a song - until when its cover
     * may still arrive. See [joinPhase].
     */
    private var joinUntilMs = 0L
    private var joinAnswered = true
    private var coverWaitUntilMs = 0L
    private var joinCheck: Runnable? = null
    private var lastIcyAtMs = 0L

    /** Polls [StationTitleApi] while a station that has one is playing. */
    private var titlePollJob: Job? = null

    /** The last StreamTitle the API gave - it only goes on when it changes. */
    private var lastApiTitle: String? = null

    /** The stream set against the API, for a station that has both - see [witness]. */
    private val streamWitness = StreamWitness()

    /**
     * The programme on air, for a station with a programme guide. It answers
     * from what it has and fetches the rest in the background; when the guide
     * or a presenter's photo lands, the dashboard is redrawn with it.
     */
    private val onAirGuide by lazy {
        OnAirGuide(applicationContext, scope) { refreshCurrentMetadata(force = true, why = "ramowka") }
    }

    /** The field names of the API's last answer - see [traceApiFields]. */
    private var lastApiFields: String? = null

    /** Title changes waiting out their hold - see [handleIcyTitleHeld]. */
    private val heldTitles = mutableListOf<Job>()

    /** Cover art found for the current track; null = we show the station logo. */
    @Volatile
    private var coverArtUrl: String? = null

    /** What the catalog knows about the current track - release, year, cover art. */
    @Volatile
    private var trackInfo: CoverArtLookup.TrackInfo? = null

    /** Increments on every track change - filters out stale search results. */
    private var coverGeneration = 0
    private var coverRevertJob: Job? = null
    private var coverLookupJob: Job? = null
    private var manualArtworkLookup = false
    private val streamArtwork = StreamArtworkState()

    /**
     * The track that the cover art held in [coverArtUrl] belongs to. Used to
     * detect the moment when the cover art stops matching what's playing.
     */
    private var coverTrackKey: String? = null
    private val coverAlternatives = mutableSetOf<String>()

    /** Format from the decoder and bitrate from the ICY header - together they give the quality description. */
    @Volatile
    private var audioFormat: androidx.media3.common.Format? = null

    @Volatile
    private var icyBitrateKbps = 0

    /** Waits after a control marker for whatever the station inserts next. */
    private var pendingMarkerJob: Job? = null

    /** Watches whether the track description has gone stale because the station announced nothing. */
    private var staleJob: Job? = null

    /**
     * When the current description stops being believable, on the monotonic
     * clock; 0 means nothing is armed.
     *
     * A deadline rather than a timer, because the way this app is actually used
     * has the phone asleep in a pocket with the radio on the head unit, and a
     * coroutine `delay` in Doze fires minutes late - measured at five to eight
     * on the 09-10.09.2026 traces. Being late does no harm as long as nobody is
     * reading: what matters is that the description is right at the moment
     * something starts reading it again, and every such moment already passes
     * through [refreshCurrentMetadata]. [staleJob] stays on as one more trigger,
     * for when nothing else wakes us.
     */
    private var staleAtMs = 0L

    /** What [staleAtMs] was worked out from - only the trace reads it. */
    private var staleBasis: String? = null

    /** The track [staleAtMs] belongs to, so a late check can't clear a newer one. */
    private var staleForRaw: String? = null

    /**
     * Whether we joined the current track partway through, with no way of
     * knowing how far.
     *
     * True right after a station change or a reconnect, because the server
     * dumps whatever `StreamTitle` is current the moment we attach - on
     * 09-10.09.2026 four of Smooth FM's five opening blocks arrived within
     * three seconds of connecting, mid-song every time. ICY carries no position
     * in the track, so until the station announces a change we have actually
     * witnessed, a catalog length says nothing about how much is left.
     */
    private var positionUnknown = true

    /**
     * When the current station was started, on the monotonic clock. Everything
     * the station does is measured from here in the trace.
     */
    private var stationAtMs = 0L

    /** Fingerprint of the last metadata dump - filters out repeats in the log. */
    private var lastDump: String? = null

    /** Fingerprint of the last playback-state snapshot - see [traceState]. */
    private var lastStateTrace: String? = null

    /** Last station slogan - shown when we don't know what's currently playing. */
    @Volatile
    private var lastSlogan: String? = null

    /**
     * When the advert now on air finishes, on the monotonic clock; 0 when none
     * is known to be running. Only stations that state a duration set it.
     */
    private var adEndsAtMs = 0L

    /**
     * How many head-unit controllers are attached. A count rather than a flag,
     * because Android Auto connects more than one and they come and go
     * independently - see [MetadataFactory.carAttached] for what hangs on it.
     */
    private var carControllers = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A setting changed halfway through a drive changes what the following
        // lines in the trace mean, so the change is a line of its own. Not the
        // app's own bookkeeping though - the play counter and the recent list
        // are written on every station change, and two lines saying so after
        // every switch is noise in a file meant to be read.
        if (key != null && !isBookkeeping(key)) Trace.write("cfg") { put("key", key) }
        when (key) {
            "active_browse_parent" -> refreshAutomotiveQueue()
            "random_station_order" -> {
                stationTraversal.invalidateOrder()
                saveStationTraversal()
                if (this@RadioService::session.isInitialized) {
                    session.setCustomLayout(customLayout())
                }
            }
            "online_artwork_enabled" -> {
                preloadStationArtwork()
                val now = PlaybackStatusBus.nowPlaying.value
                val trackKey = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
                updateCoverArt(now, trackKey, false)
                notifyBrowseNodesChanged(NODE_ALL, NODE_FAVOURITES, NODE_RECENT)
                refreshCurrentMetadata(force = true, why = "artwork-setting")
                if (this@RadioService::session.isInitialized) {
                    session.setCustomLayout(customLayout())
                }
            }
            "station_artwork_revision" -> {
                notifyBrowseNodesChanged(NODE_ALL, NODE_FAVOURITES, NODE_RECENT)
                refreshCurrentMetadata(force = true, why = "station-image-selection")
            }
            "preset_order" -> {
                notifyBrowseNodesChanged(NODE_FAVOURITES)
                refreshAutomotiveQueue()
            }
            Prefs.KEY_STYLE_BROWSABLE, Prefs.KEY_STYLE_PLAYABLE, Prefs.KEY_M3U -> {
                session.notifyChildrenChanged(NODE_ROOT, Int.MAX_VALUE, null)
                BROWSE_NODES.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
            }
            Prefs.KEY_TRACE -> if (prefs.traceEnabled) startTrace() else Trace.close()
            Prefs.KEY_DIAG -> {
                refreshCurrentMetadata(force = true, why = "diag")
                // Stripping ICY happens when the data source is created, so for
                // the toggle to take effect immediately the stream must be re-prepared.
                if (player.playWhenReady && player.currentMediaItem != null) {
                    player.prepare()
                }
            }
            Prefs.KEY_DIAG_API, Prefs.KEY_ARTWORK,
            Prefs.KEY_LINE_TOP, Prefs.KEY_LINE_MIDDLE, Prefs.KEY_LINE_BOTTOM,
            Prefs.KEY_LINE_TOP_IDLE, Prefs.KEY_LINE_MIDDLE_IDLE, Prefs.KEY_LINE_BOTTOM_IDLE,
            Prefs.KEY_ART_PLAYING, Prefs.KEY_ART_IDLE, Prefs.KEY_CLOCK_BG,
            Prefs.KEY_CLOCK_FG, Prefs.KEY_ENRICH_ALBUM ->
                refreshCurrentMetadata(force = true, why = "opcje")
            Prefs.KEY_BUFFER -> debugInfo(TAG, "Zmieniono bufor - zadziala po restarcie odtwarzania")
            // A quality change or a custom address change concerns a specific station.
            // The keys are prefixed with its identifier, so we check the beginning.
            else -> if (key?.startsWith("stream_") == true) reloadCurrentStation()
        }
    }

    private fun sessionActivityIntent(): Intent? {
        if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
            return Intent().setClassName(this, "net.mspanc.twinsenradio.phone.PhoneLibraryActivity")
        }
        val intent = Intent("android.car.intent.action.MEDIA_TEMPLATE")
            .putExtra("android.car.intent.extra.MEDIA_COMPONENT",
                ComponentName(this, RadioService::class.java).flattenToString())
        val host = packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY
        ).firstOrNull { it.activityInfo.exported }?.activityInfo ?: return null
        return intent.setClassName(host.packageName, host.name)
    }

    override fun onCreate() {
        super.onCreate()
        StationArtworkStore.beginServiceLaunch()
        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)
        restoreStationTraversal()
        if (prefs.traceEnabled) startTrace()

        player = buildPlayer()
        player.addListener(PlayerEvents())
        player.addAnalyticsListener(
            LoadDiagnostics { format ->
                audioFormat = format
                publishQuality()
            }
        )

        val sessionPlayer = KeepCurrentStreamPlayer(player).apply { stationNavigation = ::navigateStationOrder }
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .apply {
                sessionActivityIntent()?.let { intent ->
                    setSessionActivity(PendingIntent.getActivity(
                        this@RadioService, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
                }
            }
            .setCustomLayout(customLayout())
            .build()


        reconnect = ReconnectController(this, player) { status ->
            Trace.write("net") { put("status", status.name) }
            // A dropped stream means the next block will be a fresh server dump
            // of whatever is playing by then - mid-track again, just as at a
            // station change.
            if (status == ReconnectController.Status.RECONNECTING) positionUnknown = true
            PlaybackStatusBus.setNetworkStatus(
                when (status) {
                    ReconnectController.Status.RECONNECTING -> PlaybackStatusBus.Status.RECONNECTING
                    ReconnectController.Status.WAITING_FOR_NETWORK -> PlaybackStatusBus.Status.WAITING_FOR_NETWORK
                    ReconnectController.Status.STATION_UNREACHABLE -> PlaybackStatusBus.Status.STATION_UNREACHABLE
                    // Not PLAYING - the network is simply out of complaints, and
                    // what is actually happening is the player's to say.
                    ReconnectController.Status.OK -> null
                }
            )
        }
        reconnect.start()
        prefs.registerListener(prefsListener)
        scheduleClockTick()

        scope.launch {
            while (true) {
                repo.refreshUserLists()
                delay(60_000)
            }
        }
        scope.launch {
            repo.revisions.collect {
                preloadStationArtwork()
                notifyBrowseNodesChanged(NODE_ALL, NODE_FAVOURITES, NODE_RECENT, NODE_GENRES, NODE_PLAYLISTS)
                repo.playlists().forEach { notifyBrowseNodesChanged("/playlist/${it.id}") }
                session.notifyChildrenChanged(
                    NODE_ROOT,
                    NativeSessionControls.rootIds(
                        net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE,
                        repo.favourites().isNotEmpty()
                    ).size,
                    null
                )
                refreshAutomotiveQueue()
            }
        }

        // The dashboard learns nothing on its own - it only ever sees what we
        // push into the session, so a connection message that is not pushed
        // would first appear with the next track, which in practice means never.
        // We watch the message rather than the status: the states that say
        // nothing, and the ones that happen to say the same thing, then cost no
        // refresh at all.
        scope.launch {
            var last: String? = null
            PlaybackStatusBus.status.collect { status ->
                val line = StatusLine.textFor(this@RadioService, status)
                if (line == last) return@collect
                last = line
                refreshCurrentMetadata(force = true, why = "polaczenie")
            }
        }

        // A favourites change - wherever it came from - must immediately translate
        // into the star next to the player and into the lists that show it.
        scope.launch {
            Prefs.favouritesFlow.collect { favourites ->
                if (!this@RadioService::session.isInitialized) return@collect
                debugInfo(TAG, "ulubione zmienione (${favourites.size}) - odswiezam przyciski i wezly")
                session.setCustomLayout(customLayout())
                notifyBrowseNodesChanged(NODE_FAVOURITES, NODE_ALL, NODE_RECENT)
                session.notifyChildrenChanged(
                    NODE_ROOT,
                    NativeSessionControls.rootIds(
                        net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE,
                        favourites.isNotEmpty()
                    ).size,
                    null
                )
                refreshAutomotiveQueue()
            }
        }

        // A station added from the catalog should appear in the car without restarting the app.
        // A new genre may also show up, so we refresh their list as well.
        scope.launch {
            Prefs.discoveredFlow.collect { stations ->
                if (!this@RadioService::session.isInitialized) return@collect
                debugInfo(TAG, "stacje z sieci zmienione (${stations.size}) - odswiezam wezly")
                notifyBrowseNodesChanged(NODE_ALL, NODE_GENRES)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        PlaybackStatusBus.setActuallyPlaying(false)
        PlaybackStatusBus.resetMissingTrackInfo()
        clockTick?.let { clockHandler.removeCallbacks(it) }
        cancelConnecting()
        prefs.unregisterListener(prefsListener)
        reconnect.stop()
        session.release()
        player.release()
        scope.cancel()
        Trace.close()
        super.onDestroy()
    }

    /**
     * Keys the app writes to itself on every station change. A star pressed in
     * the car is a decision and stays in the trace; a play counter is not.
     */
    private fun isBookkeeping(key: String): Boolean =
        key.startsWith("plays_") || key == Prefs.KEY_RECENT

    /**
     * Opens the trace and writes down the settings it was recorded under.
     *
     * The layout is in the file because the same events read differently under
     * a different one: an empty middle line is a station saying nothing when
     * the line carries the artist, and it is the layout when the line is set
     * to "off". Reading that off the phone weeks later is not an option -
     * by then the settings have moved.
     */
    /**
     * One line's setting on one line of the trace: "CLOCK+DATE", or just
     * "CLOCK" when it carries nothing after the dot.
     */
    private fun opis(spec: LineSpec): String =
        if (spec.secondary == LineContent.EMPTY) {
            spec.primary.name
        } else {
            "${spec.primary.name}+${spec.secondary.name}"
        }

    private fun startTrace() {
        Trace.open(this)
        traceEnvironment()
        val p = prefs.presentation
        Trace.write("uklad") {
            // Both layouts, because which one a line came from is the first
            // question the trace gets asked once there are two of them.
            put("gora", opis(p.playing.top))
            put("srodek", opis(p.playing.middle))
            put("dol", opis(p.playing.bottom))
            put("goraBezUtworu", opis(p.idle.top))
            put("srodekBezUtworu", opis(p.idle.middle))
            put("dolBezUtworu", opis(p.idle.bottom))
            put("grafika", p.artPlaying.name)
            put("grafikaBezUtworu", p.artIdle.name)
            put("okladka", prefs.artworkMode)
            put("album", prefs.enrichWithYear)
            put("diag", prefs.diagnosticMode)
            put("bufor", prefs.bufferProfile)
        }
    }

    // --- player construction ----------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val profile = BufferProfile.at(prefs.bufferProfile)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.forPlaybackMs,
                profile.afterRebufferMs
            )
            // For a live stream we care about time, not buffer size.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(StationRepository.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(false)

        val filtered = IcyFilteringDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory),
            onIcyTiming = { interval, bitrate ->
                clockHandler.post { PlaybackStatusBus.setIcyMetadataTiming(interval, bitrate) }
            },
            onIcyBitrate = { br ->
                // Loader thread; the quality is the main thread's.
                clockHandler.post {
                    if (br != icyBitrateKbps) {
                        icyBitrateKbps = br
                        publishQuality()
                    }
                }
            }
        ) { prefs.diagnosticMode && prefs.stripIcyInDiagnostic }

        val fallbackSourceFactory = DefaultMediaSourceFactory(filtered)
            .setLoadErrorHandlingPolicy(InfiniteLoadErrorHandlingPolicy())
        val hlsSourceFactory = HlsMediaSource.Factory(filtered)
            .setMetadataType(HlsMediaSource.METADATA_TYPE_ID3)
            .setExtractorFactory(HlsTimedId3ExtractorFactory())
            .setLoadErrorHandlingPolicy(InfiniteLoadErrorHandlingPolicy())
        val mediaSourceFactory = object : MediaSource.Factory by fallbackSourceFactory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val local = mediaItem.localConfiguration
                return if (local != null && Util.inferContentTypeForUriAndMimeType(
                        local.uri, local.mimeType) == C.CONTENT_TYPE_HLS) {
                    hlsSourceFactory.createMediaSource(mediaItem)
                } else fallbackSourceFactory.createMediaSource(mediaItem)
            }
        }

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                // Keep station skip commands available at either end, including one-item scopes.
                repeatMode = Player.REPEAT_MODE_ALL
            }
    }

    // --- reactions to player events --------------------------------------------

    private inner class PlayerEvents : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A station change starts everything from scratch. Without this, the cover art
            // from the previous station would stay in the `coverArtUrl` field and come back
            // on screen at the next metadata refresh - after switching from RMF to RNS
            // the RMF cover art would briefly show instead of the Nowy Swiat logo.
            resetStationState()
            val id = mediaItem?.mediaId?.let { Station.idFromMediaId(it) }
            id?.let { stationTraversal.observe(it); saveStationTraversal() }
            // Also prune a former outside item after an automatic stream transition.
            if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) refreshAutomotiveQueue()
            PlaybackStatusBus.setStation(id)

            Trace.station(id)
            Trace.write("stacja") {
                put("nazwa", id?.let { repo.byId(it)?.name })
                put("url", mediaItem?.localConfiguration?.uri?.toString())
                put("powod", reason)
            }

            // The new station starts from the state it would be in with no track:
            // whatever the layout says belongs on screen when nothing is playing.
            // Sending it right here means the previous station's title cannot
            // survive on the dashboard until the new one happens to say something -
            // and a station that says nothing at all leaves this standing, which
            // is exactly what it should show.
            startJoin()
            refreshCurrentMetadata(force = true, now = null, why = "zmiana-stacji")
            syncTitlePolling()

            id?.let {
                prefs.pushRecent(it)
                // Play count - the only sensible source for a "most listened"
                // ordering, which after a few weeks of driving arranges the list
                // better than anything we could come up with upfront.
                prefs.bumpPlayCount(it)
            }

            // The star belongs to a specific station, so on change the button
            // layout needs to be rebuilt. Without this, entering a station showed
            // the previous one's state and the first tap looked like it did nothing.
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            traceState("stan")
            publishPlayerStatus()
            if (state == Player.STATE_BUFFERING) {
                bufferingSinceMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "Buffering started bufferedMs=${player.totalBufferedDuration}")
                armConnecting()
            } else {
                if (bufferingSinceMs != 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - bufferingSinceMs
                    Log.i(TAG, "Buffering ended elapsedMs=$elapsed state=$state bufferedMs=${player.totalBufferedDuration}")
                    bufferingSinceMs = 0L
                }
                cancelConnecting()
            }
            updateSilence()
        }

        /**
         * Both the state and the wish feed this. A pause leaves the player READY,
         * so reading only the state kept "Odtwarzanie" on screen over a paused
         * player (seen 30.10.2026).
         */
        private fun publishPlayerStatus() {
            PlaybackStatusBus.setPlayerStatus(
                when {
                    !player.playWhenReady -> PlaybackStatusBus.Status.IDLE
                    player.playbackState == Player.STATE_BUFFERING -> PlaybackStatusBus.Status.BUFFERING
                    player.playbackState == Player.STATE_READY -> PlaybackStatusBus.Status.PLAYING
                    else -> PlaybackStatusBus.Status.IDLE
                }
            )
        }
        /**
         * On live radio a pause is not a pause - it is falling behind, and
         * staying there.
         *
         * Measured 21.09.2026 on RMF FM: paused for 2 min 37 s, resumed, and two
         * seconds later the app announced a track that had been on air two and a
         * half minutes earlier. Four minutes after that it was still exactly that
         * far behind, with no error and no catching up. The lag is not the buffer
         * draining - Icecast holds the socket, TCP back-pressure stalls the
         * sender while nobody is reading, and on resume the server simply carries
         * on from where it had got to. **The lag equals the length of the pause
         * and it never closes by itself**; what eventually clears it is the
         * connection breaking for some unrelated reason, which is why it looks
         * like it "jumps to live after a while".
         *
         * In the car this is the trip-to-the-shop case: the power goes, the head
         * unit drops, `handleAudioBecomingNoisy` pauses us, and twenty minutes
         * later we resume twenty minutes behind - the wrong news, the wrong
         * traffic report, and a dashboard describing a track that ended long ago.
         *
         * So a resume that has been away long enough drops the stream and joins
         * the live one instead. [STALE_RESUME_MS] is a judgement, not a
         * measurement: under it the lag is shorter than the silence a reconnect
         * costs, so carrying on is the better of the two.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE && !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                // A clean EOF keeps this station paused. Explicit Play uses the existing IDLE revival.
                player.stop()
                player.seekToDefaultPosition()
            }
            traceState("gra", playWhenReadyReason(reason))
            PlaybackStatusBus.setWantsToPlay(playWhenReady)
            publishPlayerStatus()
            if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) armConnecting()
            else cancelConnecting()
            updateSilence()
            if (!playWhenReady) {
                pausedAtMs = SystemClock.elapsedRealtime()
                syncTitlePolling()
                // "Nie gra" has a look of its own - see MetadataFactory.forStopped
                refreshCurrentMetadata(force = true, why = "pauza")
                return
            }
            val away = if (pausedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - pausedAtMs
            pausedAtMs = 0L
            if (away < STALE_RESUME_MS || player.currentMediaItem == null) {
                syncTitlePolling()
                refreshCurrentMetadata(force = true, why = "wznowienie")
                return
            }

            Trace.write("dogonienie") { put("pauzaMs", away) }
            debugInfo(TAG, "pauza ${away}ms - porzucam opozniony strumien i lacze sie od nowa")
            // The same reset a station change does. What was queued up to play is
            // minutes old, and so is everything we had worked out about it - the
            // description, the cover art, the deadline it was to expire at.
            resetStationState()
            player.stop()
            player.seekToDefaultPosition()
            player.prepare()
            // Without this the dashboard would keep the track from before the
            // pause until the station happens to announce the next one.
            startJoin()
            refreshCurrentMetadata(force = true, now = null, why = "dogonienie")
            syncTitlePolling()
        }

        /**
         * The only callback that means "sound is actually coming out".
         *
         * `playWhenReady` is a wish and `STATE_READY` is a readiness; neither of
         * them promises audio. The combination that produces the head unit going
         * quiet with everything apparently running is exactly the one these two
         * hide: ready, wanting to play, and suppressed.
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackStatusBus.setActuallyPlaying(isPlaying)
            traceState("slychac")
        }

        /**
         * Why the player is not making a sound although it wants to - no audio
         * focus, or an output the player will not play to.
         *
         * This is the first thing to look at when the Passat goes silent with
         * Android Auto up and the app apparently playing. Nothing recorded it
         * before, which is why that fault has never had an answer.
         */
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            traceState("tlumienie")
        }

        /**
         * Only for the record - the rescue itself is [ReconnectController]'s
         * job. An error that coincides with a station change is worth being
         * able to see afterwards, and logcat will not have kept it.
         */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Trace.write("blad") {
                put("kod", error.errorCodeName)
                put("opis", DiagnosticUrls.failure(error))
            }
        }

        override fun onMetadata(meta: Metadata) {
            // Media3 delivers ID3 here at presentation time, not when a segment downloads.
            HlsStreamsMetadataParser.parse(meta)?.let { update ->
                handleStreamsMetadata(update, update.title, null, StreamWitness.Source.HLS)
            }
            for (i in 0 until meta.length()) {
                when (val entry = meta.get(i)) {
                    is IcyHeaders -> logIcyHeaders(entry)
                    is IcyInfo -> {
                        val raw = logIcyInfo(entry)
                        handleStreamsMetadata(IcyArtworkParser.parse(raw), entry.title, raw,
                            StreamWitness.Source.ICY)
                    }
                    // Decoded above as one snapshot; never log artwork URLs from frame.toString().
                    is TextInformationFrame, is PrivFrame -> Unit
                    else -> debugInfo(TAG_ICY, "inny typ metadanych: ${entry.javaClass.simpleName} | $entry")
                }
            }
        }
    }

    /** Apply each timed title/art pair before starting its cancellable image lookup. */
    private fun handleStreamsMetadata(update: IcyArtworkParser.Update, title: String?,
        rawBlock: String?, source: StreamWitness.Source) {
        val artworkChanged = streamArtwork.update(update)
        val artworkGeneration = coverGeneration
        if (titleApiSource() == null) {
            if (update.hasTitle) handleIcyTitle(title, rawBlock,
                clearEmptyTitle = source == StreamWitness.Source.HLS)
        } else {
            witness(source, title, rawBlock.orEmpty())
        }
        if (artworkChanged && artworkGeneration == coverGeneration) {
            val now = PlaybackStatusBus.nowPlaying.value
            // A clear may retire old artwork even when a blank ICY title is ignored.
            val clearsArtwork = update.hasArtwork && update.artworkUrl.isNullOrEmpty()
            // API titles may lag the stream: never attach its art to another description.
            if (clearsArtwork || streamArtwork.matches(now?.raw)) {
                val key = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
                updateCoverArt(now, key, false)
            }
        }
    }

    /**
     * New title from Icecast. We update the item's metadata, not the stream itself -
     * `replaceMediaItem` with the same source configuration doesn't interrupt playback.
     */
    /** icy-* headers from the HTTP response - constant for the whole stream. */
    private fun logIcyHeaders(h: IcyHeaders) {
        PlaybackStatusBus.setIcyMetadataTiming(h.metadataInterval, h.bitrate)
        debugInfo(TAG_ICY, "== naglowki ICY strumienia ==")
        h.name?.let { debugInfo(TAG_ICY, "icy-name = $it") }
        h.genre?.let { debugInfo(TAG_ICY, "icy-genre = $it") }
        h.url?.let { debugInfo(TAG_ICY, "icy-url = ${DiagnosticUrls.endpoint(it)}") }
        if (h.bitrate > 0) {
            debugInfo(TAG_ICY, "icy-br = ${h.bitrate}")
            // Fallback in case the decoder doesn't report a bitrate - with AAC
            // in ADTS this is the rule, since the stream itself carries none at all.
            icyBitrateKbps = h.bitrate
            publishQuality()
        }
        if (h.metadataInterval > 0) debugInfo(TAG_ICY, "icy-metaint = ${h.metadataInterval}")
        debugInfo(TAG_ICY, "icy-pub = ${h.isPublic}")
        // Connection headers describe ICY delivery, not a guarantee that this
        // station can never supply track information through any other route.
        Trace.write("naglowki") {
            put("name", h.name)
            put("genre", h.genre)
            put("br", h.bitrate)
            put("metaint", h.metadataInterval)
            put("poStacji", sinceStationMs())
        }
    }

    /**
     * The metadata block injected into the stream. ExoPlayer only parses
     * StreamTitle and StreamUrl out of it, but stations push in more key=value
     * pairs (RMF, for instance, marks ads via adw_ad and durationMilliseconds). So
     * alongside the recognized fields we also log the whole raw content.
     */
    private fun logIcyInfo(info: IcyInfo): String {
        val raw = runCatching { String(info.rawMetadata, Charsets.UTF_8).trim(Char(0), ' ') }
            .getOrDefault("")
        debugInfo(TAG_ICY, "surowy blok: ${DiagnosticUrls.text(raw)}")
        info.url?.let { debugInfo(TAG_ICY, "StreamUrl = ${DiagnosticUrls.endpoint(it)}") }

        // split all key='value' pairs so unusual fields are visible
        Regex("""(\w+)='([^']*)'""").findAll(raw).forEach { m ->
            debugInfo(TAG_ICY, "  ${m.groupValues[1]} = ${DiagnosticUrls.text(m.groupValues[2])}")
        }
        return raw
    }

    private fun handleIcyTitle(rawTitle: String?, rawBlock: String? = null,
        clearEmptyTitle: Boolean = false) {
        val title = rawTitle?.trim().orEmpty()
        // The title alone isn't enough to detect a repeat: ads have an empty
        // title, and different insertions only differ in their adId fields.
        val fingerprint = title + "|" + rawBlock.orEmpty()
        if (fingerprint == lastRawIcyTitle) {
            // A block identical to the last one changes nothing on screen, but
            // it is not nothing: the station is alive and still naming this
            // track. Smooth FM re-sent "Paula Abdul - Straight Up" 242s after
            // the first copy on 10.09.2026. Until now this returned before
            // Trace.write, so repeats were invisible in the file and only ever
            // showed up in logcat - which is why nobody could tell whether they
            // mean "still playing" or "nothing new to say". Recorded, not acted
            // on: whether a repeat should push the stale deadline out depends on
            // whether stations also repeat during ad blocks, and that is a
            // question for the trace, not for a guess here.
            Trace.write("icy-powtorka") {
                put("blok", rawBlock)
                put("poStacji", sinceStationMs())
                put("poPoprzednim", if (lastIcyAtMs == 0L) null else System.currentTimeMillis() - lastIcyAtMs)
            }
            return
        }
        lastRawIcyTitle = fingerprint

        // The gap between ICY blocks is something we don't know about stations:
        // whether metadata arrives once per track or periodically. We log it so
        // it can be measured from the outside.
        val nowMs = System.currentTimeMillis()
        val gapMs = if (lastIcyAtMs == 0L) null else nowMs - lastIcyAtMs
        val sinceLast = gapMs?.div(1000) ?: -1
        lastIcyAtMs = nowMs
        debugInfo(TAG_ICY, "po ${sinceLast}s | StreamTitle='$title'")

        // The first block of a station (or of a fresh connection after a drop)
        // is the server telling us what is already playing; every block after
        // it is a change we saw happen. That difference is the whole basis of
        // the stale deadline - see [positionUnknown].
        if (gapMs != null) positionUnknown = false

        val stationName = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it)?.name }
        val now = NowPlaying.parse(title, stationName, rawBlock)
        debugInfo(
            TAG_ICY,
            "  -> artist='${now?.artist}' title='${now?.songTitle}' " +
                "slogan='${now?.slogan}' reklama=${now?.isAd} " +
                "znacznik=${now?.isControlMarker} utwor=${now?.isRealSong}"
        )
        // Both halves on one line: the block exactly as it arrived, and what we
        // made of it. Reading them apart is how a parser fault gets blamed on a
        // station, and a station's oddity on the parser.
        Trace.write("icy") {
            put("blok", rawBlock)
            put("tytul", title)
            put("poStacji", sinceStationMs())
            put("poPoprzednim", gapMs)
            put("wykonawca", now?.artist)
            put("utwor", now?.songTitle)
            put("slogan", now?.slogan)
            put("reklama", now?.isAd)
            put("znacznik", now?.isControlMarker)
            put("prawdziwy", now?.isRealSong)
            put("pominiete", now == null)
        }

        // An empty StreamTitle with no ad marker is the only thing NowPlaying.parse
        // returns null for, and it means the station had nothing to say - not that
        // nothing is playing. Treating the two as one wiped a description in the
        // middle of a song: on 10.09.2026 Smooth FM sent an empty block 55s into
        // "Boyzone - A Picture Of You" (3:26 long) and the dashboard sat on the
        // station name for three minutes until the next track was announced.
        //
        // So we record it and leave the screen and the deadline exactly as they
        // are. Deciding that a track is over is the stale deadline's job, and it
        // works from how long the track runs rather than from a station's silence.
        // A station that really has stopped playing music says so - RMF marks its
        // ads, and an empty title arrives there alongside adw_ad, which parses to
        // an ad and not to null.
        if (now == null) {
            // HLS snapshots explicitly replace the title, including an empty one.
            if (clearEmptyTitle) publish(null)
            else debugInfo(TAG_ICY, "pusty blok bez znacznika reklamy - zostawiam opis bez zmian")
            return
        }
        publish(now)
    }

    private fun titleApiSource(): StationTitleApi.Source? =
        StationTitleApi.forStation(player.currentMediaItem?.mediaId?.let { Station.idFromMediaId(it) })

    /**
     * Starts or stops the title poll to match what the player is doing: on
     * while it wants to play a station that has an API, off otherwise - a
     * paused radio has nothing to describe.
     *
     * Only a change is considered, and every change goes on - for such a
     * station the API alone describes the track, see [StreamWitness]. It passes
     * through [handleIcyTitle] as if the stream had sent it, so its first
     * answer counts as joining mid-track and every later one as a change we
     * saw happen - the same as ICY.
     */
    private fun syncTitlePolling() {
        val source = titleApiSource()
        if (source == null || !player.playWhenReady) {
            titlePollJob?.cancel()
            titlePollJob = null
            return
        }
        if (titlePollJob?.isActive == true) return
        titlePollJob = scope.launch {
            while (isActive) {
                val body = StationTitleApi.fetch(source)
                val title = body?.let(source.toStreamTitle)
                body?.let(::traceApiFields)
                if (body == null) {
                    debugInfo(TAG_ICY, "API tytulow nie odpowiedzialo")
                } else if (title != null && title != lastApiTitle) {
                    lastApiTitle = title
                    debugInfo(TAG_ICY, "API tytulow: ${DiagnosticUrls.text(body)}")
                    val raw = "api: $body"
                    witness(StreamWitness.Source.API, title, raw)
                    handleIcyTitleHeld(title, raw, prefs.apiHoldSeconds * 1_000L)
                }
                delay(StationTitleApi.POLL_MS)
            }
        }
    }

    /**
     * Writes down what [source] said next to what the other one last said, for
     * a station whose screen only the API decides - see [StreamWitness]. The
     * stream is kept in the trace because whether it agrees again, and how
     * far behind the API it says things, is something only a live station can
     * tell us.
     */
    private fun witness(source: StreamWitness.Source, title: String?, rawBlock: String) {
        val stationName = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it)?.name }
        val now = NowPlaying.parse(title, stationName, rawBlock)
        val atMs = SystemClock.elapsedRealtime()
        val result = when (source) {
            StreamWitness.Source.ICY, StreamWitness.Source.HLS -> streamWitness.fromIcy(now, atMs)
            // The poll hands over only what parsed - an API answer that says
            // nothing never reaches here.
            StreamWitness.Source.API -> now?.let { streamWitness.fromApi(it, atMs) } ?: return
        }
        debugInfo(TAG_ICY, "zrodla: $source '$title' zgodne=${result.agrees}")
        Trace.write("zgodnosc") {
            put("zrodlo", source.name)
            put("tytul", title)
            put("zgodne", result.agrees)
            put("roznicaMs", result.apartMs)
            put("poStacji", sinceStationMs())
            put("blok", rawBlock)
        }
    }

    /**
     * [handleIcyTitle], but a title *change* waits [holdMs] first - see
     * [StationTitleApi] for why a station needs it.
     *
     * Nothing in the first [JOIN_UNHELD_MS] of a station is held: whatever
     * arrives then describes what is already playing, and holding it would only
     * leave the screen empty for longer. Held titles keep their order, since
     * they all wait the same time.
     */
    private fun handleIcyTitleHeld(title: String?, rawBlock: String, holdMs: Long) {
        val joining = sinceStationMs()?.let { it < JOIN_UNHELD_MS } != false
        if (holdMs <= 0 || lastIcyAtMs == 0L || joining) {
            handleIcyTitle(title, rawBlock)
            return
        }
        debugInfo(TAG_ICY, "zmiana tytulu czeka ${holdMs}ms: '$title'")
        Trace.write("odroczenie") {
            put("tytul", title)
            put("ms", holdMs)
        }
        heldTitles.removeAll { it.isCompleted }
        heldTitles += scope.launch {
            delay(holdMs)
            handleIcyTitle(title, rawBlock)
        }
    }

    /**
     * Records the API's fields whenever their set changes. Their web player
     * reads a `schedule_img` from the same answer, which looks like what it
     * sends while a programme rather than a track is on - the one signal of
     * "talking heads" in sight. Not seen yet (27.10.2026), so for now it is
     * only written down, to be measured before anything relies on it.
     */
    private fun traceApiFields(body: String) {
        val keys = runCatching {
            org.json.JSONObject(body).optJSONObject("data")?.keys()?.asSequence()?.sorted()?.joinToString(",")
        }.getOrNull() ?: return
        if (keys == lastApiFields) return
        lastApiFields = keys
        debugInfo(TAG_ICY, "API tytulow - pola: $keys")
        Trace.write("api-pola") {
            put("pola", keys)
            put("blok", body)
        }
    }

    /**
     * Puts a description up at once. The first one of a station used to wait
     * 1.5 s so it wouldn't arrive on top of the "no track" state a change put
     * up first - which left that state, and the programme's photo with it, on
     * screen over a song already playing. A change now puts up nothing to
     * collide with (see [joinPhase]), so there is nothing to wait for.
     */
    private fun publish(now: NowPlaying?) {
        publishNow(now)
    }

    /**
     * Whether this controller is a head unit rather than a screen of the phone.
     *
     * Android Auto connects as com.google.android.projection.gearhead, and so
     * does the Desktop Head Unit used for testing - which is what makes this
     * checkable from a desk. Everything else (our own activities, the system's
     * media panel, a watch) is a phone-side reader and must not be sent a
     * dashboard layout.
     */
    private fun isCar(controller: MediaSession.ControllerInfo): Boolean =
        net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE || controller.packageName == PKG_ANDROID_AUTO

    /**
     * Applies the attached/detached state and re-sends the description if it
     * changed, so the artwork swaps at the moment the cable goes in or out
     * rather than at the next track.
     *
     * Posted rather than run inline: onConnect has not yet returned its
     * connection result, and handing the player a new media item before it does
     * is asking for trouble.
     */
    private fun noteCarAttached() {
        val attached = carControllers > 0
        if (attached == metadata.carAttached) return
        clockHandler.post {
            if (attached == metadata.carAttached) return@post
            metadata.carAttached = attached
            debugInfo(TAG, if (attached) "glowica podlaczona - wlaczam uklad deski" else "glowica odlaczona - wracam do okladek")
            Trace.write("glowica") { put("podlaczona", attached) }
            refreshCurrentMetadata(force = true, why = "glowica")
        }
    }

    /**
     * What the system currently thinks of this app, written once per run.
     *
     * This is the nearest thing there is to an answer for "sometimes the app is
     * not in the Android Auto list at all". We cannot log that directly - the
     * list is built from the package manager without ever starting us, so when
     * we are missing there is no process to write anything down. What we can do
     * is record, the next time we do run, the state that would have caused it.
     *
     * `kubelek` is the app standby bucket: at RESTRICTED Android holds back
     * background work and bindings, which is exactly what Android Auto needs to
     * do to reach us. `bezOszczedzania` says whether we are exempt from battery
     * optimisation. Either one being wrong is a cause; both being fine moves the
     * suspicion off the phone and onto Android Auto or the head unit.
     */
    private fun traceEnvironment() {
        Trace.write("srodowisko") {
            put(
                "kubelek",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching {
                        when (val b = getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
                            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "aktywny"
                            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "roboczy"
                            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "czesty"
                            UsageStatsManager.STANDBY_BUCKET_RARE -> "rzadki"
                            45 -> "ograniczony" // STANDBY_BUCKET_RESTRICTED, API 30+
                            else -> "inny($b)"
                        }
                    }.getOrDefault("?")
                } else {
                    "n/d"
                }
            )
            put(
                "bezOszczedzania",
                runCatching {
                    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                }.getOrNull()
            )
            put("wyjscia", audioOutputs())
        }
    }

    /**
     * The whole playback state in one line, whichever part of it just moved.
     *
     * Four callbacks can change it and each one sees only its own field, so
     * writing what each reports separately gave a trace where no single line
     * ever said whether sound was coming out. A snapshot instead: `powod` names
     * the callback that fired, the rest is the full picture at that instant.
     *
     * `slychac` is the one that matters. `gra` is a wish and `stan` is a
     * readiness - neither promises audio, and the fault where the Passat goes
     * quiet with everything apparently running lives precisely in the gap
     * between them.
     */
    private fun traceState(why: String, detail: String? = null) {
        val stan = when (player.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buforuje"
            Player.STATE_READY -> "gotowy"
            else -> "koniec"
        }
        val gra = player.playWhenReady
        val slychac = player.isPlaying
        val tlumienie = suppressionName(player.playbackSuppressionReason)
        // Which outputs the system is offering. When the car is silent, the
        // question "was the car even an available output" comes first, and it
        // cannot be answered after the fact from anywhere else.
        val wyjscia = audioOutputs()

        // One move usually trips two callbacks - play() reaches here as both
        // "gra" and "slychac" - and the second line would repeat the first down
        // to the last field. Which callback fired is not the interesting part;
        // the state is. A line carrying `skad` is never dropped, though: that is
        // the only place the reason for a pause is written down.
        val fingerprint = "$stan|$gra|$slychac|$tlumienie|$wyjscia"
        if (detail == null && fingerprint == lastStateTrace) return
        lastStateTrace = fingerprint

        Trace.write("stan") {
            put("powod", why)
            detail?.let { put("skad", it) }
            put("stan", stan)
            put("gra", gra)
            put("slychac", slychac)
            put("tlumienie", tlumienie)
            put("wyjscia", wyjscia)
        }
    }

    private fun suppressionName(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "brak"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "utrata-focusu"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE -> "zle-wyjscie"
        else -> "inne($reason)"
    }

    /** Why `playWhenReady` moved - who paused us, in other words. */
    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "uzytkownik"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "utrata-focusu"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "odlaczono-wyjscie"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "zdalnie"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "koniec-utworu"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "tlumione-za-dlugo"
        else -> "inne($reason)"
    }

    /**
     * Output devices the system currently offers, by type, deduplicated. Short
     * names on purpose - this rides along on every state line.
     */
    private fun audioOutputs(): String = runCatching {
        val am = getSystemService(AudioManager::class.java) ?: return@runCatching "?"
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "glosnik"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "sluchawka"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "jack"
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
                    AudioDeviceInfo.TYPE_BUS -> "bus"
                    AudioDeviceInfo.TYPE_TELEPHONY -> "telefonia"
                    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
                    AudioDeviceInfo.TYPE_DOCK -> "dok"
                    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "submix"
                    else -> "typ${d.type}"
                }
            }
            .distinct()
            .joinToString("+")
    }.getOrDefault("?")

    /** How long the current station has been playing, for the trace. */
    private fun sinceStationMs(): Long? =
        if (stationAtMs == 0L) null else SystemClock.elapsedRealtime() - stationAtMs

    /**
     * Decides whether an event from ICY should immediately change what's shown on screen.
     *
     * A control marker (e.g. STOP_AD_BREAK) by itself doesn't mean the music is
     * back - RMF can insert another ad right after it. If we switched back to the
     * station view immediately, the screen would flicker between "Ad" and the
     * station name on every insertion. So the marker only arms a timer: if
     * something concrete arrives within [MARKER_GRACE_MS] - a track or another
     * ad - it wins, and if nothing arrives, only then do we fall back to just
     * the station.
     */
    private fun publishNow(now: NowPlaying?) {
        pendingMarkerJob?.cancel()

        if (now?.isControlMarker == true && now.isAd != true) {
            debugInfo(TAG_ICY, "znacznik '${now.raw}' - czekam ${MARKER_GRACE_MS}ms na to, co dalej")
            pendingMarkerJob = scope.launch {
                delay(MARKER_GRACE_MS)
                debugInfo(TAG_ICY, "po znaczniku nic nie przyszlo - zostawiam sama stacje")
                apply(null)
            }
            return
        }

        apply(now)
    }

    private fun apply(now: NowPlaying?) {
        now?.slogan?.let { lastSlogan = it }

        // An insertion that states its own length tells us when the music can
        // resume - and a track announced before that moment has not started
        // yet. See scheduleStaleCheck for what the figure is used for.
        if (now?.isAd == true && now.adDurationMs > 0) {
            adEndsAtMs = SystemClock.elapsedRealtime() + now.adDurationMs
        }

        // Some stations re-announce the very same song's ICY metadata mid-track
        // (a periodic StreamTitle repeat, not an actual track change) - the raw
        // block differs just enough to dodge the fingerprint check in
        // handleIcyTitle, so we still land here. Comparing against the track the
        // cover art already belongs to catches that case and skips the
        // clear-and-relookup cycle, which is what showed up as the station logo
        // flashing back on for an instant in the middle of a song.
        val key = if (now?.isRealSong == true) "${now.artist}|${now.songTitle}" else null
        val sameTrack = key != null && key == coverTrackKey

        if (!sameTrack) {
            // Catalog data belongs to the PREVIOUS track, so we clear it before drawing
            // anything. Otherwise the new artist briefly shows up glued to the old
            // record - "Taylor Swift - Black Gold: The Best of Soul Asylum [1992]".
            // The cover art is cleared by updateCoverArt, also immediately.
            trackInfo = null
            PlaybackStatusBus.setTrackInfo(null)
        }

        PlaybackStatusBus.setNowPlaying(now)
        // Kept for the screen before play, which has no stream to hear it from.
        // Not the news: "FAKTY" is what was on, not what the station calls itself.
        val slogan = now?.slogan
        if (slogan != null && !now.isNews) {
            PlaybackStatusBus.stationId.value?.let { prefs.rememberSlogan(it, slogan) }
        }

        // Order matters: we clear the cover art first, only then send the
        // metadata. The other way around, the car would get a new description
        // with the old artwork - after the song, the studio would come on and
        // "Pion i poziom!" would still show the previous track's cover.
        updateCoverArt(now, key, sameTrack)
        joinAnswered(now)
        refreshCurrentMetadata(force = true, now = now, why = "icy")
        scheduleStaleCheck(now)
    }

    /**
     * Arms the deadline after which a track whose end was never announced stops
     * being shown.
     *
     * RMF can enter an ad block without any ICY event - the screen would then
     * keep a title from several minutes ago, because we had no signal that
     * anything had changed. How long to wait depends on what we actually know,
     * and the three cases below used to be treated as one, which is how a
     * station name ended up over the middle of a song.
     */
    private fun scheduleStaleCheck(now: NowPlaying?) {
        staleJob?.cancel()
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null
        if (now?.isRealSong != true) return

        val catalogMs = trackInfo?.durationMs ?: 0
        val (budget, basis) = when {
            // We joined this track partway through and the stream won't say how
            // far, so its length tells us nothing about what is left. Whatever
            // we pick here is a guess; we make it a generous one, because taking
            // a correct description off the screen is worse than leaving a
            // finished one up a while longer.
            positionUnknown -> FALLBACK_TRACK_MS to "pozycja-nieznana"
            // A length below any radio edit means the catalog matched a
            // different release, not that the song is short. "Stayin' Alive"
            // came back as a 1:33 soundtrack cut on 09.09.2026 and wiped the
            // description two minutes into a song that was still playing.
            catalogMs in 1 until MIN_TRACK_MS -> MIN_TRACK_MS to "katalog-za-krotki"
            catalogMs > 0 -> catalogMs to "katalog"
            else -> FALLBACK_TRACK_MS to "bez-dlugosci"
        }

        // Stations announce a track before they play it: the metadata arrives
        // while an ad block is still running, and the song itself starts when
        // the block ends. Counting from the announcement then runs the
        // description out early by however long the ads had left.
        //
        // RMF says how long its insertion is (durationMilliseconds), so for it
        // this is arithmetic rather than a guess: the track cannot have started
        // before the ad finished, so neither may its clock. Stations that don't
        // mark their ads - Smooth FM, Jacaranda - give us nothing to key off,
        // and for them the drift stays unmeasured. See FINDINGS.md, section 7.
        val adLeft = (adEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)

        val timeout = budget + STALE_GRACE_MS + adLeft
        staleAtMs = SystemClock.elapsedRealtime() + timeout
        staleBasis = basis
        staleForRaw = now.raw
        // Which of the three cases we landed in, and on what number. Without it
        // the expiry showed up in the trace as an ordinary "icy" push, and the
        // only way to tell them apart was to correlate timestamps by hand.
        Trace.write("termin") {
            put("utwor", now.raw)
            put("podstawa", basis)
            put("dlugoscKatalog", catalogMs)
            put("zaMs", timeout)
            // Non-zero means the track was announced over an ad that was still
            // running, and the clock was pushed out by what remained of it.
            put("reklamaZostalo", adLeft)
        }
        staleJob = scope.launch {
            delay(timeout)
            checkStale("licznik")
        }
    }

    /**
     * Starts joining a station - a new one, or the live stream again after a
     * long pause or a silence.
     *
     * Whatever was decided about the moment of joining used to go up at once,
     * before the station had said a word: the gap's programme and presenter's
     * photo, the faded "nie gra" of the instant before play(), a bare logo - and
     * a second later the song that had been playing all along (measured
     * 30.10.2026: four different pictures in two seconds on RNS, the API having
     * named the song before the programme even went up). So nothing is decided
     * until the station answers: see [joinPhase].
     */
    private fun startJoin() {
        joinUntilMs = SystemClock.elapsedRealtime() + JOIN_WAIT_MS
        // after a silence the API may have answered before the sound came back
        joinAnswered = PlaybackStatusBus.nowPlaying.value != null
        coverWaitUntilMs = 0L
        scheduleJoinCheck(JOIN_WAIT_MS)
    }

    private fun scheduleJoinCheck(delayMs: Long) {
        joinCheck?.let { clockHandler.removeCallbacks(it) }
        val check = Runnable { refreshCurrentMetadata(force = true, why = "dolaczanie") }
        joinCheck = check
        clockHandler.postDelayed(check, delayMs)
    }

    /** Where joining a station has got to - see [joinPhase]. */
    private enum class JoinPhase {
        /** Not joining: everything as the rules say. */
        NONE,

        /** The station hasn't said what is on: its name, and an empty picture slot. */
        WAITING,

        /** A song is known and its text is up; its cover is still being looked up. */
        COVER
    }

    /**
     * Where joining a station has got to.
     *
     * The text follows what is known; the picture goes up once, when it is
     * decided. That is the point: after play the slot must not show a logo
     * that a cover replaces a moment later, nor the gap's presenter's photo
     * over a song - so it stays empty for the short time the decision takes,
     * and then shows what belongs there.
     *
     * [JoinPhase.WAITING] lasts until the station says what is on - the API's
     * first answer, or the first ICY block - or [JOIN_WAIT_MS] pass in silence,
     * which then is the answer. A song moves to [JoinPhase.COVER] with its text
     * up at once - we joined it mid-way, so it is already on air - and the
     * picture follows when the cover comes, or as the logo after
     * [JOIN_COVER_WAIT_MS] if the catalogue has none. Anything else (a slogan,
     * an ad, nothing) ends joining at once: the gap layout and its picture.
     *
     * Changes while listening are not joining; they go as they always have.
     */
    private fun joinPhase(): JoinPhase {
        if (joinUntilMs == 0L) return JoinPhase.NONE
        val t = SystemClock.elapsedRealtime()
        val phase = when {
            !joinAnswered -> if (t < joinUntilMs) JoinPhase.WAITING else JoinPhase.NONE
            prefs.onlineArtworkEnabled && coverWaitUntilMs != 0L && t < coverWaitUntilMs && coverArtUrl == null &&
                PlaybackStatusBus.nowPlaying.value?.isRealSong == true -> JoinPhase.COVER
            else -> JoinPhase.NONE
        }
        if (phase == JoinPhase.NONE) {
            Trace.write("dolaczono") {
                put("odpowiedz", joinAnswered)
                put("okladka", coverArtUrl != null)
                put("poStacji", sinceStationMs())
            }
            joinUntilMs = 0L
            coverWaitUntilMs = 0L
            joinCheck?.let { clockHandler.removeCallbacks(it) }
            joinCheck = null
        }
        return phase
    }

    /** The station has said what is on - see [joinPhase]. */
    private fun joinAnswered(now: NowPlaying?) {
        if (joinUntilMs == 0L || joinAnswered) return
        joinAnswered = true
        if (now?.isRealSong == true) {
            coverWaitUntilMs = SystemClock.elapsedRealtime() + JOIN_COVER_WAIT_MS
            scheduleJoinCheck(JOIN_COVER_WAIT_MS)
        }
    }

    /**
     * Keeps the silence clock in step with the player: started when we want to
     * play and the player isn't ready, stopped the moment it is.
     *
     * Counted from the silence, not from the signal: a live stream arrives as
     * fast as it plays, so the buffer holds only what the server sent on
     * connect - measured 30.10.2026 in airplane mode, RNS went quiet 8 s after
     * the network did, not 45.
     */
    private fun updateSilence() {
        val silent = player.playWhenReady && player.playbackState != Player.STATE_READY &&
            player.currentMediaItem != null
        if (!silent) {
            silenceCheck?.let { clockHandler.removeCallbacks(it) }
            silenceCheck = null
            silentSinceMs = 0L
            if (lostAir && player.playbackState == Player.STATE_READY) {
                lostAir = false
                PlaybackStatusBus.setLostAir(false)
                Trace.write("cisza-koniec") {}
                startJoin()
                refreshCurrentMetadata(force = true, why = "powrot-dzwieku")
            } else if (lostAir) {
                // Paused while silent: that is "nie gra" for a reason of its
                // own now, and play starts a fresh count.
                lostAir = false
                PlaybackStatusBus.setLostAir(false)
            }
            return
        }
        if (silentSinceMs != 0L) return
        silentSinceMs = SystemClock.elapsedRealtime()
        val check = Runnable { checkSilence("licznik") }
        silenceCheck = check
        clockHandler.postDelayed(check, SILENCE_GRACE_MS)
    }

    /**
     * Turns a long silence into "nie gra", if it is time.
     *
     * The first [SILENCE_GRACE_MS] change nothing: a tunnel or a garage is
     * over before anyone reads the dashboard, and flipping it back and forth
     * would be the louder fault. Past that, what is on screen is a guess about a
     * broadcast we haven't heard for a minute - the track ended, the programme
     * may have - so the station's state goes, exactly as on a station change,
     * and the layout shows the station and its slogan, faded, with the reason in
     * the status line. Whatever arrives first after the sound returns (ICY, the
     * API) describes the broadcast as it is then; nothing from before comes back.
     *
     * @return whether it fired - the caller's description has just been replaced.
     */
    private fun checkSilence(trigger: String): Boolean {
        if (lostAir || silentSinceMs == 0L) return false
        val silentFor = SystemClock.elapsedRealtime() - silentSinceMs
        if (silentFor < SILENCE_GRACE_MS) return false
        debugInfo(TAG, "cisza ${silentFor}ms - pokazuje 'nie gra' ($trigger)")
        Trace.write("cisza") {
            put("ms", silentFor)
            put("wyzwalacz", trigger)
        }
        lostAir = true
        PlaybackStatusBus.setLostAir(true)
        // The stream is the same one, and the decoder won't announce its format
        // again when it resumes - kept, or "MP3 · stereo" vanished for good
        // after the first tunnel (seen 30.10.2026).
        val format = audioFormat
        val bitrate = icyBitrateKbps
        resetStationState()
        audioFormat = format
        icyBitrateKbps = bitrate
        publishQuality()
        // the reset stopped the poll; it has to be running when the network is back
        syncTitlePolling()
        refreshCurrentMetadata(force = true, now = null, why = "cisza")
        return true
    }

    /**
     * Drops a description whose track must be over by now, if it is time.
     *
     * Called from [refreshCurrentMetadata] - that is, from every point where
     * something is about to read what we show - rather than from the timer
     * alone. See [staleAtMs] for why a timer on its own isn't enough.
     *
     * @return whether it fired. A caller that gets `true` has nothing left to
     *   send: [apply] has already pushed a complete fresh set.
     */
    private fun checkStale(trigger: String): Boolean {
        val deadline = staleAtMs
        if (deadline == 0L) return false
        val late = SystemClock.elapsedRealtime() - deadline
        if (late < 0) return false

        val expired = staleForRaw
        // Something newer is already on screen - the deadline outlived what it
        // was armed for and has nothing left to clear.
        if (PlaybackStatusBus.nowPlaying.value?.raw != expired) {
            staleAtMs = 0L
            return false
        }

        debugInfo(TAG_ICY, "utwor '$expired' powinien byc juz po - czyszcze opis ($trigger)")
        Trace.write("przeterminowanie") {
            put("utwor", expired)
            put("podstawa", staleBasis)
            put("wyzwalacz", trigger)
            // How far past the deadline we actually got. On a sleeping phone
            // this runs into minutes, and that number is the whole reason the
            // check doesn't live in the timer.
            put("spoznienie", late)
        }
        // Cleared before apply(), which refreshes the metadata and so re-enters
        // this function: the second call sees no deadline and returns at once.
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null

        // If the station has ever given its slogan, it's better to show it
        // than an empty line - RNS has "Pion i poziom!", RMF "FAKTY" during the news.
        val slogan = lastSlogan
        if (slogan != null) {
            apply(
                NowPlaying(
                    raw = slogan,
                    artist = null,
                    songTitle = slogan,
                    isStationSelfTitle = true
                )
            )
        } else {
            apply(null)
        }
        return true
    }

    /**
     * Clears everything we knew about the previous station. Called on a station
     * change, where no trace of the previous one is allowed to remain - not the
     * cover art, not the description, and not a timer armed by something the
     * previous station said. A marker left running was the worst of them: a
     * STOP_AD_BREAK from the station we just left would fire fifteen seconds
     * into the new one and wipe a description that had just arrived.
     */
    private fun resetStationState() {
        PlaybackStatusBus.resetMissingTrackInfo()
        streamArtwork.clear()
        coverGeneration++
        coverLookupJob?.cancel()
        manualArtworkLookup = false
        PlaybackStatusBus.setTrackArtworkLoading(false)
        // Whatever we are about to open is a fresh connection, so the pause
        // before it has nothing left to say about how far behind we are.
        pausedAtMs = 0L
        coverRevertJob?.cancel()
        staleJob?.cancel()
        staleAtMs = 0L
        staleBasis = null
        staleForRaw = null
        // Whatever the new station says first describes a track already in
        // progress, exactly as it did for the one we are leaving.
        positionUnknown = true
        // An advert belongs to the station we are leaving.
        adEndsAtMs = 0L
        pendingMarkerJob?.cancel()
        stationAtMs = SystemClock.elapsedRealtime()
        coverTrackKey = null
        coverArtUrl = null
        trackInfo = null
        lastRawIcyTitle = null
        // The poll that was running asked about the station we are leaving, and
        // whatever the new connection is told first must go on screen again.
        titlePollJob?.cancel()
        titlePollJob = null
        lastApiTitle = null
        streamWitness.reset()
        onAirGuide.reset()
        // A held title belongs to the station we are leaving.
        heldTitles.forEach { it.cancel() }
        heldTitles.clear()
        lastSlogan = null
        // The gap between ICY blocks is measured within one station; carried
        // across a change it would report the time since the previous one.
        lastIcyAtMs = 0L
        // A new station gets its own first dump, even if the description happens
        // to read the same as the one we just left (two stations, both showing
        // just the clock).
        lastDump = null
        // A new station gets its opening state written out even if it happens to
        // read the same as the state the previous one ended in.
        lastStateTrace = null
        audioFormat = null
        icyBitrateKbps = 0
        PlaybackStatusBus.setCoverArt(null)
        PlaybackStatusBus.setTrackInfo(null)
        PlaybackStatusBus.setNowPlaying(null)
        PlaybackStatusBus.setQuality(null)
    }

    /**
     * Swaps the cover art on a track change.
     *
     * There's one rule: **the cover art never outlives the track it belongs to**.
     * Previously it was the opposite - the old artwork stayed until a new one was
     * found, so the station logo wouldn't flicker between tracks. In practice that
     * gave a worse result than a flicker: for a fraction of a second (and after
     * the description expired, even for [COVER_GRACE_MS]) the previous record's
     * cover hung next to the new artist's name, which simply looks like a bug.
     *
     * So on every track change we immediately fall back to the station logo, and
     * only show the cover art once the catalog actually finds it. When a station
     * sends metadata right at connection time, the lookup usually takes
     * ~200 ms and the logo barely has time to appear.
     */
    private fun updateCoverArt(now: NowPlaying?, key: String?, sameTrack: Boolean, alternative: Boolean = false) {
        // Same track re-announced - we already have its art (or a lookup for it
        // already in flight), nothing to clear or re-fetch.
        if (sameTrack && !alternative) return

        val generation = ++coverGeneration
        coverRevertJob?.cancel()
        coverLookupJob?.cancel()
        if (!alternative) manualArtworkLookup = false
        PlaybackStatusBus.setTrackArtworkLoading(false)

        fun applyIfCurrent(info: CoverArtLookup.TrackInfo?) {
            if (generation != coverGeneration) return
            val url = info?.artworkUrl
            if (coverArtUrl == url && trackInfo == info) return
            coverArtUrl = url
            trackInfo = info
            PlaybackStatusBus.setCoverArt(url)
            PlaybackStatusBus.setTrackInfo(info)
            refreshCurrentMetadata(force = true, now = PlaybackStatusBus.nowPlaying.value, why = "katalog")
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
            // We now know the track length - recompute the moment the description goes stale
            scheduleStaleCheck(PlaybackStatusBus.nowPlaying.value)
        }

        if (!alternative) coverAlternatives.clear()
        coverTrackKey = key
        if (!alternative && (coverArtUrl != null || trackInfo != null)) {
            coverArtUrl = null
            trackInfo = null
            PlaybackStatusBus.setCoverArt(null)
            PlaybackStatusBus.setTrackInfo(null)
        }

        // An ad or the station's own slogan - nothing to look up in the catalog.
        if (key == null || now == null || !prefs.onlineArtworkEnabled) {
            if (this@RadioService::session.isInitialized) {
                session.setCustomLayout(customLayout())
            }
            return
        }

        PlaybackStatusBus.setTrackArtworkLoading(true)
        refreshCurrentMetadata(force = true, why = "artwork-loading")
        if (this@RadioService::session.isInitialized) {
            session.setCustomLayout(customLayout())
        }
        coverLookupJob = scope.launch {
            try {
            val startedAt = SystemClock.elapsedRealtime()
            val broadcasterArtwork = if (!alternative) streamArtwork.urlFor(now?.raw)?.let { url ->
                try { TrackArtworkStore.fetch(this@RadioService, url, currentTrack = true,
                    allowedHosts = StreamArtworkPolicy.allowedHosts)?.toString() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            } else null
            if (generation != coverGeneration) return@launch
            val lookup = if (broadcasterArtwork != null) {
                CoverArtLookup.CatalogLookupResult.Found(
                    CoverArtLookup.TrackInfo(artworkUrl = broadcasterArtwork,
                        album = null, year = null, isSingle = false))
            } else if (alternative) CoverArtLookup.findAlternativeDetailed(now!!.artist, now.songTitle, coverAlternatives.toSet())
                else CoverArtLookup.findDetailed(now!!.artist, now.songTitle)
            val info = (lookup as? CoverArtLookup.CatalogLookupResult.Found)?.info
            if (generation != coverGeneration) return@launch
            info?.artworkUrl?.let { coverAlternatives.add(it) }
            val cachedArtwork = broadcasterArtwork ?: info?.artworkUrl?.let { url ->
                try { TrackArtworkStore.fetch(this@RadioService, url, currentTrack = true)?.toString() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            // The answer, and the decision taken on it. The swapped flag is here
            // because the station's own order cannot be judged without it -
            // Jacaranda sends title first, and that only shows against a catalogue.
            Trace.write("katalog") {
                put("pytanieWykonawca", now.artist)
                put("pytanieUtwor", now.songTitle)
                put("ms", SystemClock.elapsedRealtime() - startedAt)
                put("znaleziono", info != null)
                put("lookupOutcome", when (lookup) {
                    is CoverArtLookup.CatalogLookupResult.Found -> if (cachedArtwork != null) "artwork-ready" else "image-unavailable"
                    CoverArtLookup.CatalogLookupResult.NotFound -> "no-match"
                    is CoverArtLookup.CatalogLookupResult.TemporaryFailure -> "service-unavailable"
                })
                put("album", info?.album)
                put("rok", info?.year)
                put("dlugoscMs", info?.durationMs)
                put("katalogUtwor", info?.trackName)
                put("katalogWykonawca", info?.artistName)
                put("zamiana", info?.looksSwapped(now.artist, now.songTitle))
                put("aktualne", generation == coverGeneration)
            }
            if (generation != coverGeneration) return@launch
            if (alternative && cachedArtwork == null) {
                val message = when {
                    lookup is CoverArtLookup.CatalogLookupResult.TemporaryFailure -> "Artwork service is temporarily unavailable. Try again later."
                    info?.artworkUrl != null -> "Artwork download failed. Current image kept."
                    else -> "No different matching artwork was found. Current image kept."
                }
                android.widget.Toast.makeText(this@RadioService, message, android.widget.Toast.LENGTH_SHORT).show()
            } else applyIfCurrent(info?.copy(artworkUrl = cachedArtwork))
            } finally {
                if (generation == coverGeneration) {
                    manualArtworkLookup = false
                    PlaybackStatusBus.setTrackArtworkLoading(false)
                    refreshCurrentMetadata(force = true, why = "artwork-complete")
                    if (this@RadioService::session.isInitialized) {
                        session.setCustomLayout(customLayout())
                    }
                }
            }
        }
    }

    /**
     * Reloads the current station at a new address.
     *
     * Used after a quality variant change. We go straight to ExoPlayer, bypassing
     * [KeepCurrentStreamPlayer] - it deliberately ignores setting the same
     * station so that tapping the currently playing item doesn't drop the
     * connection, but here dropping it is exactly the point.
     */
    private fun reloadCurrentStation() {
        val id = PlaybackStatusBus.stationId.value ?: return
        val station = repo.byId(id) ?: return
        if (station.stream == player.currentMediaItem?.localConfiguration?.uri?.toString()) return
        debugInfo(TAG, "zmieniono strumien stacji ${station.name} na ${DiagnosticUrls.endpoint(station.stream)}")
        val wasPlaying = player.playWhenReady
        player.setMediaItem(playableItem(station))
        player.prepare()
        if (wasPlaying) player.play()
    }

    /** Builds the quality description from the decoder format and the icy-br header. */
    private fun publishQuality() {
        val format = audioFormat
        if (format == null) {
            PlaybackStatusBus.setQuality(null)
            return
        }
        val quality = StreamQuality.of(format, icyBitrateKbps)
        Trace.write("jakosc") {
            put("opis", quality.label().ifBlank { null })
            put("kbps", quality.bitrateKbps.takeIf { it > 0 })
            put("codec", format.sampleMimeType)
            put("hz", format.sampleRate.takeIf { it > 0 })
            put("poStacji", sinceStationMs())
        }
        PlaybackStatusBus.setQuality(quality)
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
            refreshCurrentMetadata(force = true, why = "stream-quality")
    }

    /**
     * Refreshes metadata exactly on the minute boundary, not every 60s from
     * start - otherwise the clock in the subtitle would drift against the car's clock.
     */
    private fun scheduleClockTick() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val delayToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        val runnable = Runnable {
            // We only refresh the clock when there's somewhere to show it - always
            // in diagnostic mode, otherwise only in layouts that have a clock.
            val needed = prefs.diagnosticMode || prefs.presentation.needsClock
            if (needed) refreshCurrentMetadata(force = true, why = "zegar")
            scheduleClockTick()
        }
        clockTick = runnable
        clockHandler.postDelayed(runnable, delayToNextMinute + 100)
    }

    /**
     * Arms the "connecting" message for the dashboard.
     *
     * Buffering by itself is not worth announcing - a second of refilling
     * happens on any road, and a line that flashes a warning and takes it back
     * reads worse than a line that says nothing. Buffering that outlasts the
     * startup its own profile asks for is a different thing: at that point we
     * are not filling a buffer, we are waiting on a station that has not
     * answered. That is what the driver is owed an explanation for.
     *
     * The grace is read from the chosen [BufferProfile] rather than fixed,
     * because "too long" means something different at a 1 s start than at a 4 s
     * one. It stays well inside [ReconnectController]'s own 12 s patience, so
     * the dashboard says "connecting" before anything says "reconnecting".
     */
    private fun armConnecting() {
        cancelConnecting()
        val runnable = Runnable {
            connectingAnnounce = null
            // Both conditions have to hold at the moment it fires, not when it
            // was armed: a station starts buffering before playWhenReady is set,
            // so testing it up front would skip the very case this is for.
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@Runnable
            PlaybackStatusBus.setPlayerStatus(PlaybackStatusBus.Status.CONNECTING)
        }
        connectingAnnounce = runnable
        clockHandler.postDelayed(runnable, BufferProfile.at(prefs.bufferProfile).forPlaybackMs + CONNECTING_MARGIN_MS)
    }

    private fun cancelConnecting() {
        connectingAnnounce?.let { clockHandler.removeCallbacks(it) }
        connectingAnnounce = null
    }

    /**
     * @param why what prompted the push. Only the trace reads it, and it is the
     *   difference between "the description changed" and "the minute did".
     */
    private fun refreshCurrentMetadata(
        force: Boolean,
        now: NowPlaying? = PlaybackStatusBus.nowPlaying.value,
        why: String = "?"
    ) {
        // Every refresh is something about to read what we show, so it is also
        // the moment to ask whether it is still true. Returning here is not an
        // optimisation: `now` was captured before this line, so carrying on
        // would push the description checkStale has just retired.
        if (checkStale(why)) return
        // Same reasoning as checkStale: on a sleeping phone the silence timer
        // runs late, and this is the moment something is about to read us.
        if (checkSilence(why)) return

        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val station = repo.byMediaId(item.mediaId) ?: return
        if (!force && prefs.diagnosticMode) return
        // Joining outranks a paused player: the instant between setMediaItem and
        // play() is not a pause. A silence outranks joining - it has a reason
        // to show.
        val phase = if (prefs.diagnosticMode || lostAir) JoinPhase.NONE else joinPhase()
        PlaybackStatusBus.setJoin(
            when (phase) {
                JoinPhase.NONE -> PlaybackStatusBus.Join.NONE
                JoinPhase.WAITING -> PlaybackStatusBus.Join.WAITING
                JoinPhase.COVER -> PlaybackStatusBus.Join.COVER
            }
        )
        val stopped = phase == JoinPhase.NONE && (!player.playWhenReady || lostAir)
        val onAir = if (stopped || phase != JoinPhase.NONE) null else onAirFor(station, now)
        val standby = if (stopped && prefs.onlineArtworkEnabled) {
            StandbyArt.file(this, station, scope) { refreshCurrentMetadata(force = true, why = "grafika-postoju") }
        } else {
            null
        }
        val fresh = if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
            metadata.forPlayback(station, now, coverArtUrl, trackInfo, onAir, stopped, standby)
        } else when (phase) {
            JoinPhase.WAITING -> metadata.forJoining(station)
            JoinPhase.COVER -> metadata.withBlankArt(metadata.forPlayback(station, now, null, trackInfo))
            JoinPhase.NONE -> metadata.forPlayback(station, now, coverArtUrl, trackInfo, onAir, stopped, standby)
        }
        PlaybackStatusBus.setOnAir(onAir)
        if (item.mediaMetadata != fresh) {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(fresh).build())
        }
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE &&
            item.mediaMetadata.subtitle?.toString() != fresh.subtitle?.toString()) {
            notifyBrowseNodesChanged(NODE_ALL, NODE_FAVOURITES, NODE_RECENT,
                NODE_GENRE_PREFIX + station.genre)
        }
        traceMetadata(station, fresh, why)
        dumpMetadata(station, fresh)
    }

    /**
     * The programme on air for [station], when the layout shows any of it - a
     * layout that doesn't is no reason to fetch a programme guide.
     */
    private fun onAirFor(station: Station, now: NowPlaying?): OnAir? {
        if (prefs.diagnosticMode || !prefs.presentation.usesProgramme || !prefs.onlineArtworkEnabled) return null
        return onAirGuide.current(station.id, java.time.LocalDateTime.now(), now?.isRealSong == true, prefs.newsMinutes)
    }

    /**
     * The set as it goes to the session, on one line.
     *
     * Every push is written down, including one that changes nothing - unlike
     * [dumpMetadata], which drops repeats to keep the preview window readable.
     * Here a repeat is a fact: it says the car was handed the same thing again,
     * which is exactly the sort of thing worth being able to count afterwards.
     */
    private fun traceMetadata(station: Station, m: MediaMetadata, why: String) {
        Trace.write("wyslano") {
            put("powod", why)
            put("stacjaNazwa", station.name)
            put("poStacji", sinceStationMs())
            put("subtitle", m.subtitle?.toString())
            put("description", m.description?.toString())
            put("displayTitle", m.displayTitle?.toString())
            put("title", m.title?.toString())
            put("artist", m.artist?.toString())
            put("albumTitle", m.albumTitle?.toString())
            put("station", m.station?.toString())
            put("genre", m.genre?.toString())
            put(
                "grafika",
                when {
                    m.artworkData != null -> "bajty:${m.artworkData?.size}"
                    m.artworkUri != null -> m.artworkUri.toString()
                    else -> null
                }
            )
        }
    }

    /**
     * Prints out the full set of fields sent to the session. Used by the Windows
     * preview window (tools/meta-watch.ps1), which reads it via `adb logcat -s MetaDump`.
     */
    private fun dumpMetadata(station: Station, m: MediaMetadata) {
        // A dump identical to the previous one carries no information, and it
        // can repeat every minute (the clock tick) or on every button refresh.
        // The preview window would otherwise flood with duplicates.
        val fingerprint = listOf(
            m.title, m.artist, m.albumTitle, m.displayTitle, m.subtitle,
            m.description, m.station, m.genre, m.artworkUri
        ).joinToString("|")
        if (fingerprint == lastDump) return
        lastDump = fingerprint

        debugInfo(TAG_DUMP, "--- ${station.name} @ ${MetadataFactory.clockText()} ---")
        listOf(
            "title" to m.title,
            "artist" to m.artist,
            "albumTitle" to m.albumTitle,
            "albumArtist" to m.albumArtist,
            "displayTitle" to m.displayTitle,
            "subtitle" to m.subtitle,
            "description" to m.description,
            "station" to m.station,
            "genre" to m.genre,
            "composer" to m.composer,
            "writer" to m.writer,
            "conductor" to m.conductor,
            "compilation" to m.compilation
        ).forEach { (name, value) ->
            if (value != null) debugInfo(TAG_DUMP, "$name = ${DiagnosticUrls.text(value.toString())}")
        }
        m.trackNumber?.let { debugInfo(TAG_DUMP, "trackNumber = $it") }
        m.recordingYear?.let { debugInfo(TAG_DUMP, "recordingYear = $it") }
        debugInfo(TAG_DUMP, "artworkUri = ${DiagnosticUrls.endpoint(m.artworkUri?.toString())}")
        m.artworkData?.let { debugInfo(TAG_DUMP, "artworkData = ${it.size} B (grafika w metadanych)") }
    }

    // --- browse tree for Android Auto -------------------------------------------

    /**
     * A button in the Android Auto player template. It's the only way to give
     * the user any custom control at all - AA doesn't allow drawing your own UI,
     * but it renders custom actions in its own layout (ReplaIO does the same
     * with its star and heart).
     *
     * Why this one specifically: it lets you toggle diagnostic mode right from
     * the car screen, without reaching for the phone while driving.
     */
    /**
     * Announces a change in browse node contents.
     *
     * The version without a specified recipient only reaches controllers
     * registered as subscribers on the Media3 side. Android Auto connects via
     * the old API and its subscriptions are tracked elsewhere, so with the
     * favourites list open it received nothing and the list stayed stale. So we
     * notify each connected controller by address instead.
     */
    private fun notifyBrowseNodesChanged(vararg nodes: String) {
        val controllers = session.connectedControllers
        // Media3 matches notifications to the parameters the controller
        // registered with. Sending with null missed the target - Android Auto
        // subscribes with content style parameters, so we pass the same ones.
        val params = contentStyleParams()
        for (node in nodes) {
            // The actual item count, not Int.MAX_VALUE - this way the HDU gets
            // a meaningful sense of how much the list changed.
            val count = if (node == NODE_PLAYLISTS) 2 + repo.playlists().size
                else childrenOf(node).size
            session.notifyChildrenChanged(node, count, params)
            session.notifyChildrenChanged(node, count, null)
            for (controller in controllers) {
                session.notifyChildrenChanged(controller, node, count, params)
                session.notifyChildrenChanged(controller, node, count, null)
            }
        }
        debugInfo(TAG, "powiadomiono ${controllers.size} kontroler(ow) o ${nodes.joinToString()}")
    }

    private fun collectionLabel(parent: String): String? = when (parent) {
        NODE_ALL -> getString(net.mspanc.twinsenradio.R.string.node_all)
        NODE_FAVOURITES -> getString(net.mspanc.twinsenradio.R.string.node_favourites)
        else -> repo.playlists().firstOrNull { "/playlist/${it.id}" == parent }?.name
    }

    /** Browse node content - shared between responses and notifications. */
    private fun childrenOf(parentId: String): List<Station> = when {
        parentId == NODE_FAVOURITES -> repo.favourites()
        parentId == NODE_ALL -> repo.all()
        parentId == NODE_RECENT -> repo.recent()
        parentId.startsWith("/playlist/") -> repo.sourceStations(parentId.removePrefix("/playlist/"))
        parentId.startsWith(NODE_GENRE_PREFIX) ->
            repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX))
        else -> emptyList()
    }

    /** Player commands are preferences; the native host chooses their placement. */
    private fun customLayout(): ImmutableList<CommandButton> = ImmutableList.copyOf(
        NativeSessionControls.playerActions(
            automotive = net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE,
            artworkEnabled = prefs.onlineArtworkEnabled,
            diagnosticMode = prefs.diagnosticMode
        ).map { action ->
            when (action) {
                NativeSessionControls.PlayerAction.FAVOURITE -> favouriteButton()
                NativeSessionControls.PlayerAction.SHUFFLE -> shuffleButton()
                NativeSessionControls.PlayerAction.TRACK_ART -> trackArtworkButton()
                NativeSessionControls.PlayerAction.DIAGNOSTIC -> diagnosticButton()
            }
        }
    )

    private fun shuffleButton(): CommandButton {
        val enabled = prefs.randomStationOrder
        val icon = if (enabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_SHUFFLE)
            .setDisplayName(if (enabled) "Shuffle stations on" else "Shuffle stations off")
            .setIconResId(
                if (enabled) net.mspanc.twinsenradio.R.drawable.ic_shuffle_on_aa
                else net.mspanc.twinsenradio.R.drawable.ic_shuffle_off_aa
            )
            .setExtras(
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT, icon)
                }
            )
            .build()
    }

    private fun trackArtworkButton(): CommandButton {
        val hasAlternative = coverAlternatives.isNotEmpty()
        val manualLookup = manualArtworkLookup && PlaybackStatusBus.trackArtworkLoading.value
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setSessionCommand(CMD_REFRESH_ARTWORK)
            .setDisplayName(
                when {
                    manualLookup -> "Finding track art…"
                    hasAlternative -> "Try another track image"
                    else -> "Find track art"
                }
            )
            .setIconResId(net.mspanc.twinsenradio.R.drawable.ic_artwork_refresh)
            .setExtras(
                Bundle().apply {
                    PlaybackStatusBus.stationId.value?.let { putString("stationId", it) }
                }
            )
            .build()
    }

    /**
     * The favorites heart. Without it, a station playing in the car couldn't
     * be added to favourites at all - and it's exactly in the car that a person
     * decides they want it within reach.
     */
    private fun favouriteButton(): CommandButton {
        val id = PlaybackStatusBus.stationId.value
        val isFav = id != null && id in prefs.favourites
        debugInfo(TAG, "buduje gwiazdke dla stacji '$id': ulubiona=$isFav")
        // The icon goes through TWO channels at once, and that's not redundancy.
        //
        // The proper channel is extras: Android's documentation for cars says
        // explicitly that if an icon matches one of the CommandButton.ICON_
        // constants, its value should be put under the
        // EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT key, because that "overrides the
        // icon resource passed to CustomAction.Builder and lets the system draw
        // the action consistently with the others". In other words, the head
        // unit then draws its OWN heart and doesn't look at our resources at all.
        //
        // The fallback channel is the resource id - for systems that don't know
        // that key. And only there does it matter that the vector has no
        // android:tint: a reference to @color/... would have to be resolved by
        // the head unit in our package while inflating in its own process, and
        // that's exactly where it used to fail.
        //
        // What NOT to do: pass the semantic constant to the
        // CommandButton.Builder(ICON_HEART_FILLED) constructor and assume that's
        // enough. Media3 then translates it for the old API into the resource id
        // of its own bundled resource, and the Desktop Head Unit rendered from
        // that a music note and the text "1.8X".
        val icon = if (isFav) {
            CommandButton.ICON_HEART_FILLED
        } else {
            CommandButton.ICON_HEART_UNFILLED
        }
        @Suppress("DEPRECATION")
        return CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_FAV)
            .setDisplayName(
                getString(
                    if (isFav) net.mspanc.twinsenradio.R.string.fav_remove
                    else net.mspanc.twinsenradio.R.string.fav_add
                )
            )
            .setIconResId(
                if (isFav) net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
                else net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            )
            .setExtras(
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT, icon)
                }
            )
            .build()
    }

    /** Same as the star - our own vector, resource id hardcoded. */
    private fun diagnosticButton(): CommandButton =
        @Suppress("DEPRECATION")
        CommandButton.Builder()
            .setSessionCommand(CMD_TOGGLE_DIAG)
            .setDisplayName(
                if (prefs.diagnosticMode) "Diagnostyka: WL" else "Diagnostyka: WYL"
            )
            .setIconResId(net.mspanc.twinsenradio.R.drawable.ic_diag_aa)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onMediaButtonEvent(session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON || !access(controllerInfo, verifiedLegacyMediaTrust(controllerInfo)).controlPlayback) return false
            val event = androidx.core.content.IntentCompat.getParcelableExtra(intent,
                Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java) ?: return false
            return (application as? net.mspanc.twinsenradio.App)?.handleRemoteMediaButton(event) == true
        }

        private fun access(controller: MediaSession.ControllerInfo, legacyTrust: Boolean = false) =
            MediaControllerAccessPolicy.access(controller.uid, controller.packageName,
                android.os.Process.myUid(), packageName, controller.isTrusted || legacyTrust, prefs.diagnosticMode)

        private fun verifiedLegacyMediaTrust(controller: MediaSession.ControllerInfo): Boolean {
            if (access(controller).controlPlayback || controller.uid < 0 ||
                controller.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) return false
            // Media3's legacy button adapter supplies trusted=false. Its public caller info has
            // no PID; verify the package/UID before asking the compat manager with unknown PID.
            return try {
                packageManager.getApplicationInfo(controller.packageName, 0).uid == controller.uid &&
                    androidx.media.MediaSessionManager.getSessionManager(this@RadioService)
                        .isTrustedForMediaControl(androidx.media.MediaSessionManager.RemoteUserInfo(
                            controller.packageName, -1, controller.uid))
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            } catch (error: RuntimeException) {
                android.util.Log.w("RadioService", "Legacy media-button trust unavailable", error)
                false
            }
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (net.mspanc.twinsenradio.BuildConfig.DEBUG) ConnectionLog.connected(
                this@RadioService,
                controller.packageName,
                controller.uid,
                controller.controllerVersion,
                controller.interfaceVersion,
                controller.connectionHints
            )
            // Which events happened with the car attached and which on the
            // phone alone is the first question any of this will be asked, and
            // the connecting package is the only thing that answers it -
            // com.google.android.projection.gearhead is the head unit.
            Trace.write("klient") {
                put("pakiet", controller.packageName)
                put("wersja", controller.controllerVersion)
            }
            if (isCar(controller)) carControllers++
            noteCarAttached()
            // A controller attaching is the head unit starting to read us again,
            // and after a stretch of Doze what it would read may be minutes out
            // of date. This is the most important of the stale checks: the phone
            // slept through the deadline precisely because nobody was looking,
            // and now somebody is.
            //
            // Posted, not launched: the scope runs on Main.immediate, which on
            // this thread would run the check inside onConnect and hand the
            // player a new media item before we have returned the controller its
            // connection result. The post puts it after that.
            clockHandler.post { checkStale("klient") }
            val access = access(controller)
            // Mirror Media3's controller-aware security defaults. The deprecated
            // session-only builder used here previously granted every caller
            // all playback commands, including untrusted third-party apps.
            val commands = (if (access.controlPlayback)
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            else MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS)
                .buildUpon()
                .apply {
                    // Keep the trusted host's existing turn-off shortcut ready
                    // if the user enables diagnostics after this connection.
                    // The invocation guard still forbids external enabling.
                    if (access.controlPlayback) add(CMD_TOGGLE_DIAG)
                    if (access.editFavorites) add(CMD_TOGGLE_FAV)
                    if (access.refreshArtwork) add(CMD_REFRESH_ARTWORK)
                    if (access.controlPlayback) add(CMD_TOGGLE_SHUFFLE)
                }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(if (access.controlPlayback)
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                else MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS)
                // A freshly connected controller must get the current star state
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Trace.write("klient-koniec") { put("pakiet", controller.packageName) }
            if (isCar(controller)) carControllers = (carControllers - 1).coerceAtLeast(0)
            noteCarAttached()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            // Recheck identity and current diagnostic state on invocation too:
            // legacy custom actions may enter through the shared platform session.
            val access = access(controller)
            val allowed = when (customCommand.customAction) {
                CMD_REFRESH_ARTWORK.customAction -> access.refreshArtwork
                CMD_TOGGLE_SHUFFLE.customAction -> access.controlPlayback
                CMD_TOGGLE_DIAG.customAction -> access.toggleDiagnostics
                CMD_TOGGLE_FAV.customAction, ACTION_FAVOURITE, ACTION_UNFAVOURITE -> access.editFavorites
                else -> false
            }
            if (!allowed) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            when (customCommand.customAction) {
                CMD_REFRESH_ARTWORK.customAction -> {
                    if (!prefs.onlineArtworkEnabled) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    val requestedStation = args.getString("stationId")
                    if (requestedStation != null &&
                        requestedStation != PlaybackStatusBus.stationId.value
                    ) {
                        return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                        )
                    }
                    val now = PlaybackStatusBus.nowPlaying.value
                    if (now?.isRealSong != true || now.artist.isNullOrBlank() ||
                        now.songTitle.isNullOrBlank()
                    ) {
                        android.widget.Toast.makeText(
                            this@RadioService,
                            "Track artist or title is not available.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        val result = Bundle().apply {
                            putString(
                                KEY_ACTION_RESULT_MESSAGE,
                                "Track artist or title is not available."
                            )
                        }
                        return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_NOT_SUPPORTED, result)
                        )
                    }
                    if (PlaybackStatusBus.trackArtworkLoading.value) {
                        android.widget.Toast.makeText(
                            this@RadioService,
                            "Artwork lookup is already running.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        manualArtworkLookup = true
                        updateCoverArt(
                            now,
                            "${now.artist}|${now.songTitle}",
                            sameTrack = true,
                            alternative = true
                        )
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_TOGGLE_SHUFFLE.customAction -> {
                    prefs.randomStationOrder = !prefs.randomStationOrder
                    session.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_TOGGLE_DIAG.customAction -> {
                    prefs.diagnosticMode = !prefs.diagnosticMode
                    debugInfo(TAG, "przelaczono tryb diagnostyczny na ${prefs.diagnosticMode}")
                    // prefsListener will take care of refreshing the metadata
                    session.setCustomLayout(customLayout())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // An action from the list item's menu - the head unit attaches the item id
                ACTION_FAVOURITE, ACTION_UNFAVOURITE -> {
                    val mediaId = args.getString(KEY_ACTION_MEDIA_ITEM_ID)
                    val station = mediaId?.let { repo.byMediaId(it) }
                    val result = Bundle()
                    if (station != null) {
                        val added = prefs.toggleFavourite(station.id)
                        debugInfo(TAG, "z listy: ${station.name} ${if (added) "dodana do" else "usunieta z"} ulubionych")
                        // Tell the head unit to refresh this item so the icon switches
                        result.putString(KEY_ACTION_RESULT_REFRESH_ITEM, mediaId)
                        result.putString(
                            KEY_ACTION_RESULT_MESSAGE,
                            getString(
                                if (added) net.mspanc.twinsenradio.R.string.fav_added_toast
                                else net.mspanc.twinsenradio.R.string.fav_removed_toast,
                                station.name
                            )
                        )
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, result)
                    )
                }
                CMD_TOGGLE_FAV.customAction -> {
                    val id = PlaybackStatusBus.stationId.value
                    if (id != null) {
                        val added = prefs.toggleFavourite(id)
                        debugInfo(TAG, "stacja $id ${if (added) "dodana do" else "usunieta z"} ulubionych")
                        session.setCustomLayout(customLayout())
                        // notifyChildrenChanged only exists on MediaLibrarySession,
                        // and here `session` has the wider MediaSession type
                        this@RadioService.session
                            .notifyChildrenChanged(NODE_FAVOURITES, Int.MAX_VALUE, null)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (net.mspanc.twinsenradio.BuildConfig.DEBUG) ConnectionLog.libraryRoot(this@RadioService, browser.packageName, params?.extras)
            debugInfo(
                TAG,
                "onGetLibraryRoot od ${browser.packageName} (uid=${browser.uid}), " +
                    "isRecent=${params?.isRecent}, isSuggested=${params?.isSuggested}, " +
                    "ostatnio sluchane=${repo.recent().size}"
            )
            // The system (and the head unit) asks separately for the "resume"
            // root - in that case it expects a short recently-played list, not the whole tree.
            if (params?.isRecent == true) {
                if (repo.recent().isEmpty()) {
                    return Futures.immediateFuture(
                        LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
                    )
                }
                val recentRoot = MediaItem.Builder()
                    .setMediaId(NODE_RECENT)
                    .setMediaMetadata(
                        metadata.forFolder(
                            getString(net.mspanc.twinsenradio.R.string.node_recent),
                            MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                        )
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(recentRoot, params))
            }

            val root = MediaItem.Builder()
                .setMediaId(NODE_ROOT)
                .setMediaMetadata(
                    metadata.forFolder(
                        getString(net.mspanc.twinsenradio.R.string.root_title),
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    )
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, contentStyleParams()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (page < 0 || pageSize <= 0) return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val children: List<MediaItem> = when {
                parentId == NODE_ROOT -> BrowsePage.map(
                    NativeSessionControls.rootIds(
                        net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE,
                        repo.favourites().isNotEmpty()
                    ).map { node ->
                        when (node) {
                            NODE_PLAYLISTS -> MediaItem.Builder().setMediaId(NODE_PLAYLISTS)
                                .setMediaMetadata(metadata.forFolder(getString(net.mspanc.twinsenradio.R.string.node_playlists),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)).build()
                            NODE_ALL -> folder(NODE_ALL, net.mspanc.twinsenradio.R.string.node_all)
                            NODE_FAVOURITES -> folder(
                                NODE_FAVOURITES,
                                net.mspanc.twinsenradio.R.string.node_favourites
                            )
                            NODE_RECENT -> folder(
                                NODE_RECENT,
                                net.mspanc.twinsenradio.R.string.node_recent
                            )
                            else -> folder(NODE_GENRES, net.mspanc.twinsenradio.R.string.node_genres)
                        }
                    },
                    page,
                    pageSize
                ) { it }

                parentId == NODE_PLAYLISTS -> {
                    val collections = listOf(
                        NODE_ALL to getString(net.mspanc.twinsenradio.R.string.node_all),
                        NODE_FAVOURITES to getString(net.mspanc.twinsenradio.R.string.node_favourites)
                    ) + repo.playlists().map { "/playlist/${it.id}" to it.name }
                    BrowsePage.map(collections, page, pageSize) { (parent, label) ->
                        collectionItem(parent, label)
                    }
                }
                parentId.startsWith("/playlist/") -> BrowsePage.map(childrenOf(parentId), page, pageSize) { browseItem(it,parentId) }

                parentId == NODE_FAVOURITES -> BrowsePage.map(repo.favourites(), page, pageSize) { browseItem(it, parentId) }
                parentId == NODE_ALL -> BrowsePage.map(repo.all(), page, pageSize) { browseItem(it, parentId) }
                parentId == NODE_RECENT -> BrowsePage.map(repo.recent(), page, pageSize) { browseItem(it, parentId) }

                parentId == NODE_GENRES -> BrowsePage.map(repo.genres(), page, pageSize) { genre ->
                    MediaItem.Builder()
                        .setMediaId("$NODE_GENRE_PREFIX$genre")
                        .setMediaMetadata(
                            metadata.forFolder(genre, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        )
                        .build()
                }

                parentId.startsWith(NODE_GENRE_PREFIX) ->
                    BrowsePage.map(repo.byGenre(parentId.removePrefix(NODE_GENRE_PREFIX)), page, pageSize) { browseItem(it, parentId) }

                else -> emptyList()
            }
            debugInfo(TAG, "onGetChildren($parentId) od ${browser.packageName} -> ${children.size} pozycji")
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), contentStyleParams())
            )
        }

        /**
         * Resuming playback after connecting to the car or from the system panel.
         *
         * The rule: **it must play exactly the same station as before** -
         * regardless of whether it was playing from the phone or via Android
         * Auto. There's one shared state (one service, one session), and the
         * history in [Prefs.recent] is updated on every item change, so both
         * paths record it the same way.
         *
         * Order: the station currently loaded in the player (if something was
         * already playing, it must not be swapped out), then the most recently
         * listened one from history, then the first favourite, and finally the
         * first from the list.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val current = player.currentMediaItem?.mediaId?.let { repo.byMediaId(it) }
            val station = current ?: repo.resumeCandidate()

            if (station == null) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("brak stacji do wznowienia")
                )
            }

            val skad = if (current != null) "juz zaladowana" else "z historii"
            debugInfo(
                TAG,
                "wznawiam po podlaczeniu: ${station.name} ($skad, zlecil ${controller.packageName})"
            )
            val queue = childrenOf(prefs.activeBrowseParent)
            val resumeQueue = CollectionQueue.withSelected(queue, station) { it.id }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    resumeQueue.map(::playableItem),
                    resumeQueue.indexOfFirst { it.id == station.id },
                    // Live radio - start position doesn't matter
                    C.TIME_UNSET
                )
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            debugInfo(TAG, "SUBSKRYPCJA $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String
        ): ListenableFuture<LibraryResult<Void>> {
            debugInfo(TAG, "KONIEC SUBSKRYPCJI $parentId od ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId.startsWith(COLLECTION_PREFIX)) {
                val parent = mediaId.removePrefix(COLLECTION_PREFIX)
                val label = collectionLabel(parent)
                    ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                return Futures.immediateFuture(LibraryResult.ofItem(collectionItem(parent, label), null))
            }
            val station = repo.byMediaId(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(
                browseItem(station, Station.parentFromMediaId(mediaId) ?: NODE_ALL), null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val hits = repo.search(query).size
            session.notifySearchResultChanged(browser, query, hits, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (page < 0 || pageSize <= 0) return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val allHits = repo.search(query)
            val hits = BrowsePage.map(allHits, page, pageSize) { browseItem(it) }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(hits), contentStyleParams())
            )
        }

        /**
         * Android Auto sends items with just a mediaId. Here we add the stream
         * address and full metadata - without this there'd be nothing to play.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = repo.byMediaIds(mediaItems.map { it.mediaId })
                .filterNotNull().map(::playableItem).toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Voice command "play X" arrives here via search. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val requested = mediaItems[0].mediaId
                if (requested.startsWith(COLLECTION_PREFIX)) {
                    val parent = requested.removePrefix(COLLECTION_PREFIX)
                    val label = collectionLabel(parent)
                    val stations = if (label != null) childrenOf(parent) else emptyList()
                    if (stations.isEmpty()) {
                        android.widget.Toast.makeText(this@RadioService,
                            if (label == null) "This playlist is no longer available."
                            else "$label has no stations. Current audio kept.",
                            android.widget.Toast.LENGTH_LONG).show()
                        return Futures.immediateFailedFuture(
                            UnsupportedOperationException("Selected collection has no stations"))
                    }
                    prefs.activeBrowseParent = parent
                    stationTraversal.invalidateOrder()
                    val current = Station.idFromMediaId(player.currentMediaItem?.mediaId.orEmpty())
                    val index = CollectionQueue.startIndex(stations.map { it.id }, current)!!
                    refreshCurrentMetadata(force = true, why = "collection")
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                        stations.map(::playableItem), index, C.TIME_UNSET))
                }
                val selected = repo.byMediaId(mediaItems[0].mediaId)
                    ?: mediaItems[0].requestMetadata.searchQuery?.let { repo.search(it).firstOrNull() }
                if (selected != null) {
                    val parent = CollectionQueue.stationScope(prefs.activeBrowseParent,
                        Station.parentFromMediaId(mediaItems[0].mediaId))
                    prefs.activeBrowseParent = parent
                    val queue = CollectionQueue.withSelected(childrenOf(parent), selected) { it.id }
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                        queue.map(::playableItem), queue.indexOfFirst { it.id == selected.id }, C.TIME_UNSET))
                }
            }
            val stations = repo.byMediaIds(mediaItems.map { it.mediaId })
            val resolved = mediaItems.mapIndexedNotNull { index, item ->
                stations[index] ?: item.requestMetadata.searchQuery?.let { repo.search(it).firstOrNull() }
            }.map(::playableItem).toMutableList()

            val safeIndex = if (startIndex in resolved.indices) startIndex else 0
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs)
            )
        }
    }

    private fun collectionItem(parent: String, label: String): MediaItem =
        MediaItem.Builder().setMediaId(COLLECTION_PREFIX + parent)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(label).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build())
            .build()

    private fun folder(id: String, titleRes: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                metadata.forFolder(getString(titleRes), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            )
            .build()

    /**
     * A list item with a "favourite" action available directly from the context
     * menu in Android Auto. Without this, a station could only be added to
     * favourites while it was already playing - but marking it while browsing
     * the list is the natural thing to do.
     */
    private fun browseItem(station: Station, parentId: String = NODE_ALL): MediaItem {
        val isFav = station.id in prefs.favourites
        val extras = Bundle().apply {
            if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
                // Prefer named list rows; artwork-only tiles make radio sources hard to identify.
                putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", 1)
            }
            putStringArrayList(
                KEY_ACTION_ID_LIST,
                arrayListOf(if (isFav) ACTION_UNFAVOURITE else ACTION_FAVOURITE)
            )
        }
        return MediaItem.Builder()
            .setMediaId(if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
                Station.contextualMediaId(station.mediaId, parentId) else station.mediaId)
            .setMediaMetadata(
                metadata.forBrowseItem(station).buildUpon().apply {
                    if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE &&
                        Station.idFromMediaId(player.currentMediaItem?.mediaId.orEmpty()) == station.id) {
                        setSubtitle(player.mediaMetadata.subtitle)
                    }
                }.setExtras(extras).build()
            )
            .build()
    }

    /** Update neighbors without disconnecting the station currently playing. */
    private fun refreshAutomotiveQueue() {
        if (player.currentMediaItem == null) return
        val id = Station.idFromMediaId(player.currentMediaItem!!.mediaId) ?: return
        val desired = childrenOf(prefs.activeBrowseParent)
        val desiredIndex = desired.indexOfFirst { it.id == id }
        val queue = if (desiredIndex >= 0) desired else listOf(repo.byId(id) ?: return) + desired
        if (player.mediaItemCount == queue.size && queue.indices.all {
                player.getMediaItemAt(it).mediaId == queue[it].mediaId
            }) return
        val oldIndex = player.currentMediaItemIndex
        player.removeMediaItems(oldIndex + 1, player.mediaItemCount)
        player.removeMediaItems(0, oldIndex)
        val selectedIndex = if (desiredIndex >= 0) desiredIndex else 0
        player.addMediaItems(0, queue.take(selectedIndex).map(::playableItem))
        player.addMediaItems(queue.drop(selectedIndex + 1).map(::playableItem))
    }

    /** Definitions of actions the head unit shows on list items. */
    private fun browseActionsRootList(): ArrayList<Bundle> {
        // Here the icon can only be given as a URI, so instead of
        // android.resource:// with a resource id (unstable across versions
        // and cached by head units), we use a stable address from LogoProvider.
        fun action(id: String, labelRes: Int, iconName: String, iconRes: Int) = Bundle().apply {
            putString(KEY_ACTION_ID, id)
            putString(KEY_ACTION_LABEL, getString(labelRes))
            putString(
                KEY_ACTION_ICON_URI,
                LogoProvider.iconUri(this@RadioService, iconName, iconRes).toString()
            )
        }
        return arrayListOf(
            action(
                ACTION_FAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_add,
                "star_outline",
                net.mspanc.twinsenradio.R.drawable.ic_star_outline_aa
            ),
            action(
                ACTION_UNFAVOURITE,
                net.mspanc.twinsenradio.R.string.fav_remove,
                "star_filled",
                net.mspanc.twinsenradio.R.drawable.ic_star_filled_aa
            )
        )
    }

    /**
     * The item play starts with. Its metadata is what every reader sees before
     * our first push replaces it, so it is the joining state - the station's
     * name and an empty picture - not the gap layout with its logo, which the
     * notification and the head unit drew for an instant after play.
     */
    private fun restoreStationTraversal() {
        runCatching {
            val raw=getSharedPreferences("station_navigation",MODE_PRIVATE).getString("state",null) ?: return
            val state=org.json.JSONObject(raw)
            fun values(name: String): List<String> {
                val array=state.optJSONArray(name) ?: return emptyList()
                return (0 until minOf(array.length(),512)).map { array.getString(it) }
            }
            stationTraversal.restore(StationTraversal.Snapshot(values("history"),state.optInt("cursor",-1),values("remaining"),values("pool")))
        }
    }

    private fun saveStationTraversal() {
        val state=stationTraversal.snapshot()
        val json=org.json.JSONObject().put("history",org.json.JSONArray(state.history))
            .put("cursor",state.cursor).put("remaining",org.json.JSONArray(state.remaining)).put("pool",org.json.JSONArray(state.pool))
        getSharedPreferences("station_navigation",MODE_PRIVATE).edit().putString("state",json.toString()).commit()
    }

    private fun navigateStationOrder(delta: Int): Boolean {
        val shuffled = prefs.randomStationOrder
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE && !shuffled) return false
        val current=Station.idFromMediaId(player.currentMediaItem?.mediaId.orEmpty())
        val allowed = childrenOf(prefs.activeBrowseParent).map { it.id }
        val target = if (shuffled) {
            if (delta < 0) stationTraversal.previous(allowed.toSet(), current)
            else stationTraversal.next(allowed, current)
        } else {
            val index = allowed.indexOf(current)
            when {
                allowed.isEmpty() -> null
                index < 0 -> if (delta < 0) allowed.last() else allowed.first()
                else -> allowed[Math.floorMod(index + delta, allowed.size)]
            }
        }
        saveStationTraversal()
        if(target==null || target==current) return true
        val wasPlaying=player.playWhenReady
        val index=(0 until player.mediaItemCount).firstOrNull { Station.idFromMediaId(player.getMediaItemAt(it).mediaId)==target }
        if(index!=null) player.seekToDefaultPosition(index)
        else {
            val station=repo.byId(target) ?: return true
            val queue=listOf(station)+childrenOf(prefs.activeBrowseParent).filterNot { it.id==target }
            player.setMediaItems(queue.map(::playableItem),0,C.TIME_UNSET)
        }
        // An outside station is retained only while current, never as a recurring neighbor.
        if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) refreshAutomotiveQueue()
        player.prepare(); player.playWhenReady=wasPlaying
        return true
    }
    private fun preloadStationArtwork() {
        if (!prefs.onlineArtworkEnabled) {
            stationArtworkJob?.cancel()
            return
        }
        if (stationArtworkJob?.isActive == true) { stationArtworkPending=true; return }
        stationArtworkJob = scope.launch {
          try {
            val currentId = player.currentMediaItem?.mediaId?.let(Station::idFromMediaId)
            val priority = listOfNotNull(
                currentId?.let(repo::byId),
                repo.resumeCandidate()
            ).distinctBy { it.id }
            val stations = priority + (repo.all()+repo.favourites()).distinctBy { it.id }.filterNot { station -> priority.any { it.id == station.id } }
            StationArtworkStore.preload(this@RadioService, stations) { changedStation ->
                notifyBrowseNodesChanged(NODE_ALL, NODE_FAVOURITES, NODE_RECENT)
                if (player.currentMediaItem?.mediaId?.let(Station::idFromMediaId) == changedStation.id) {
                    refreshCurrentMetadata(force = true, why = "station-artwork")
                }
            }
          } finally {
            stationArtworkJob=null
            if(stationArtworkPending) {
                stationArtworkPending=false
                scope.launch { preloadStationArtwork() }
            }
          }
        }
    }

    private fun playableItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setUri(station.stream)
            .setMediaMetadata(metadata.forJoining(station))
            .build()

    /**
     * The presentation scheme chosen in Options. These are the four layouts
     * Android Auto actually offers media apps.
     */
    private fun contentStyleParams(): MediaLibraryService.LibraryParams {
        val extras = Bundle().apply {
            putBoolean(ContentStyle.EXTRA_SUPPORTED, true)
            putInt(ContentStyle.EXTRA_BROWSABLE_HINT, prefs.browsableStyle)
            putInt(ContentStyle.EXTRA_PLAYABLE_HINT, prefs.playableStyle)
            putParcelableArrayList(KEY_ACTION_ROOT_LIST, browseActionsRootList())
        }
        return MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
    }

    companion object {
        private const val COLLECTION_PREFIX = "/collection"
        private const val TAG = "RadioService"
        private const val TAG_ICY = "IcyMeta"
        private const val TAG_DUMP = "MetaDump"

        val CMD_REFRESH_ARTWORK = SessionCommand("com.impostorlabs.streamingradio.REFRESH_ARTWORK", Bundle.EMPTY)

        private val CMD_TOGGLE_DIAG =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_DIAG", Bundle.EMPTY)
        private val CMD_TOGGLE_FAV =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_FAV", Bundle.EMPTY)
        private val CMD_TOGGLE_SHUFFLE =
            SessionCommand("net.mspanc.twinsenradio.TOGGLE_SHUFFLE", Bundle.EMPTY)

        // List item actions in Android Auto. The keys come from
        // androidx.media.utils.MediaConstants - hardcoded here because Media3
        // doesn't expose them in its own MediaConstants.
        private const val ACTION_FAVOURITE = "net.mspanc.twinsenradio.FAVOURITE"
        private const val ACTION_UNFAVOURITE = "net.mspanc.twinsenradio.UNFAVOURITE"

        private const val KEY_ACTION_ROOT_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ROOT_LIST"
        private const val KEY_ACTION_ID_LIST =
            "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ID_LIST"
        private const val KEY_ACTION_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ID"
        private const val KEY_ACTION_LABEL =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_LABEL"
        private const val KEY_ACTION_ICON_URI =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_ICON_URI"
        private const val KEY_ACTION_MEDIA_ITEM_ID =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID"
        private const val KEY_ACTION_RESULT_REFRESH_ITEM =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM"
        private const val KEY_ACTION_RESULT_MESSAGE =
            "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE"

        /**
         * How long we wait after a control marker before deciding the station
         * has nothing more to tell us. Insertions in RMF follow one another a
         * few seconds apart, so 15s comfortably covers them.
         */
        private const val MARKER_GRACE_MS = 15_000L

        /**
         * How long a new station's description waits before it goes on screen.
         *
         * Long enough for the head unit to draw the "no track" state as a state
         * of its own, short enough that nobody reads it as the station being
         * slow. Measured from the station change, not from the metadata - a
         * station that answers instantly and one that takes a second both land
         * at the same moment.
         */
        /** How long a joined station may stay silent before its silence is the answer - see joinPhase. */
        private const val JOIN_WAIT_MS = 3_000L

        /** How long a song found on joining waits for its cover, to go up whole. */
        private const val JOIN_COVER_WAIT_MS = 1_000L

        /**
         * How long after a station change titles go on without their hold -
         * see [handleIcyTitleHeld]. Both of RNS's sources answer within a
         * second of connecting (27-28.10.2026); five leaves room for a slow
         * network without letting a real track change slip through unheld.
         */
        private const val JOIN_UNHELD_MS = 5_000L

        /**
         * Margin on top of the buffer profile's own startup before buffering
         * counts as connecting - see [armConnecting]. It covers a slow DNS
         * lookup and TLS handshake, which the profile knows nothing about.
         */
        private const val CONNECTING_MARGIN_MS = 2_000L

        /**
         * How long a pause has to last before resuming means rejoining the live
         * stream rather than carrying on from where we stopped - see
         * [PlayerEvents.onPlayWhenReadyChanged].
         *
         * A judgement, not a measurement. The lag after a pause is exactly the
         * length of that pause, and undoing it costs the 1-2 s a reconnect takes
         * (measured the same day: RNS 1.2 s, RMF ~2 s). Ten seconds is where the
         * lag stops being cheaper than the silence that would remove it; below
         * that, nobody can tell they are behind, and a red light should not cost
         * a gap.
         */
        private const val STALE_RESUME_MS = 10_000L

        /** Android Auto, and the Desktop Head Unit that stands in for it. */
        private const val PKG_ANDROID_AUTO = "com.google.android.projection.gearhead"

        /** Assumed track length when the catalog doesn't know it. */
        private const val FALLBACK_TRACK_MS = 5 * 60_000L

        /**
         * Shortest catalog length we're willing to believe.
         *
         * Below this the number is evidence that the lookup matched a different
         * release, not that a short song is playing - radio simply doesn't play
         * anything this brief. "Stayin' Alive" matched a 1:33 soundtrack cut,
         * and the description came off the screen two minutes into a song that
         * had another two and a half to run.
         */
        private const val MIN_TRACK_MS = 150_000L

        /**
         * Margin added to the track length before we consider the description stale.
         *
         * Stations shorten tracks, talk over endings, and play radio edits
         * shorter than the catalog version, so waiting long past the nominal
         * time gains nothing. Half a minute covers the natural drift while not
         * leaving a stale title on screen for too long.
         */
        private const val STALE_GRACE_MS = 30_000L

        /**
         * How long silence (no signal, a station not answering) may last before
         * the dashboard stops describing what we last heard. The user's pick,
         * 30.10.2026: long enough for a tunnel, short enough that a description
         * nobody is hearing doesn't stay up.
         */
        private const val SILENCE_GRACE_MS = 60_000L

        const val NODE_ROOT = "/"
        const val NODE_FAVOURITES = "/fav"
        const val NODE_ALL = "/all"
        const val NODE_PLAYLISTS = "/playlists"
        const val NODE_RECENT = "/recent"
        const val NODE_GENRES = "/genres"
        const val NODE_GENRE_PREFIX = "/genre/"

        private val BROWSE_NODES = listOf(NODE_FAVOURITES, NODE_ALL, NODE_RECENT, NODE_GENRES)
    }
}
