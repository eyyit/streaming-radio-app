package net.mspanc.twinsenradio.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivityNowPlayingBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PhoneText
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.StandbyArt
import net.mspanc.twinsenradio.playback.RadioService
import net.mspanc.twinsenradio.playback.TextCase

/**
 * Full-screen player on the phone: large cover art, metadata, controls, and
 * a back arrow to the station list. Opens by tapping the playback bar on
 * the home screen.
 */
@UnstableApi
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var b: ActivityNowPlayingBinding
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var prefs: Prefs
    private var controller: MediaController? = null

    /** The station on screen - the one playing, or before play the one play would start. */
    private var shown: Station? = null
    private var artworkSlideDirection = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) {
            startActivity(android.content.Intent().setClassName(
                this, "net.mspanc.twinsenradio.phone.PhonePlayerActivity"))
            finish()
            return
        }
        b = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = StationRepository.get(this)
        prefs = Prefs(this)
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            // Before play the screen shows a station; play starts that one.
            val next = if (PlaybackStatusBus.stationId.value == null) shown else null
            when {
                next != null -> play(next)
                c.isPlaying -> c.pause()
                else -> c.play()
            }
        }
        b.prev.setOnClickListener { step(-1) }
        b.next.setOnClickListener { step(+1) }
        b.favourite.setOnClickListener {
            val id = shown?.id ?: return@setOnClickListener
            prefs.toggleFavourite(id)
            render()
        }

        listOf(
            PlaybackStatusBus.stationId,
            PlaybackStatusBus.nowPlaying,
            PlaybackStatusBus.status,
            PlaybackStatusBus.coverArtUrl,
            PlaybackStatusBus.trackInfo,
            PlaybackStatusBus.onAir,
            PlaybackStatusBus.wantsToPlay,
            PlaybackStatusBus.lostAir,
            PlaybackStatusBus.join,
            PlaybackStatusBus.quality,
            Prefs.favouritesFlow
        ).forEach { flow ->
            lifecycleScope.launch { flow.collect { render() } }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = render()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = render()
                    // without this, cover art found mid-track would never appear -
                    // metadata changes separately from playback position
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = render()
                })
            }
            render()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    /**
     * Quality button for the currently playing station.
     *
     * It's the same setting as in the station card - we save it in [Prefs], so
     * a change made here is visible there and vice versa. The service reloads
     * the stream on its own, since it observes this preference.
     *
     * Says what is coming through - codec, bitrate, channels, "MP3 · 128 kbit ·
     * stereo" - for every station. It used to show only a bitrate, and only
     * where the catalogue declared one, so a single-stream station said nothing
     * about itself at all. A tap opens the list only where there is one to
     * choose from (see [showQuality]); elsewhere it is information.
     *
     * The bitrate is the chosen variant's declared one where the catalogue has
     * it - that is what was picked - and otherwise what the stream reports.
     * Before the first frame is decoded only a declared bitrate is known, and
     * with none of that either the chip stays hidden rather than show a
     * placeholder.
     */
    private fun renderBitrate(station: Station?) {
        val variants = station?.variants().orEmpty()
        val chosen = station?.let { prefs.selectedStream(it.id) }
            ?: variants.maxByOrNull { it.kbps }?.url
        val current = variants.firstOrNull { it.url == chosen } ?: variants.firstOrNull()
        val declared = current?.kbps?.takeIf { it > 0 }
        val quality = PlaybackStatusBus.quality.value
        val label = when {
            quality != null -> quality.chipLabel(declared ?: quality.bitrateKbps).ifBlank { null }
            declared != null -> "$declared kbit"
            else -> null
        }
        if (station == null || label == null) {
            b.bitrate.visibility = android.view.View.GONE
            return
        }
        b.bitrate.showQuality(label, variants) {
            StreamPicker.show(this, station, prefs) { renderBitrate(station) }
        }
    }

    /** Jump to the neighbouring station in the same list, with wraparound. */
    private fun step(delta: Int) {
        val all = repo.all()
        if (all.isEmpty()) return
        val currentId = shown?.id
        val index = all.indexOfFirst { it.id == currentId }
        val target = if (index < 0) 0 else ((index + delta) % all.size + all.size) % all.size
        artworkSlideDirection = delta.compareTo(0)
        play(all[target])
    }

    private fun play(station: Station) {
        val c = controller ?: return
        c.setMediaItem(MediaItem.Builder().setMediaId(station.mediaId).build())
        c.prepare()
        c.play()
    }

    private fun render() {
        val loaded = PlaybackStatusBus.stationId.value?.let { repo.byId(it) }
        // Before play: the station play will start with - see renderMiniPlayer.
        val station = loaded ?: repo.resumeCandidate()
        shown = station
        // "Nie gra" - before play, or paused: one look, the car's too (see
        // renderMiniPlayer, and MetadataFactory.forStopped).
        val playing = loaded?.takeIf { !PlaybackStatusBus.notPlaying() }
        // Joining a station: only the station, as the car gets it - see RadioService.joinPhase.
        val joining = PlaybackStatusBus.joining()
        val now = if (joining) null else PlaybackStatusBus.nowPlaying.value

        b.stationName.text = station?.name ?: getString(R.string.nothing_playing)

        // The rules are the dashboard's, not a copy of them - see forPhone.
        val onAir = if (joining) null else PlaybackStatusBus.onAir.value
        val text = if (playing != null) {
            MetadataFactory.forPhone(
                now, PlaybackStatusBus.trackInfo.value, onAir, prefs.presentation, prefs.enrichWithYear
            )
        } else {
            PhoneText(station?.let { prefs.slogan(it.id) }.orEmpty(), "", "")
        }
        b.songTitle.text = text.title
        b.songArtist.text = text.artist
        b.songAlbum.text = text.album
        b.songAlbum.visibility = if (text.album.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        val isFav = station != null && station.id in prefs.favourites
        b.favourite.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
        b.favourite.contentDescription =
            getString(if (isFav) R.string.fav_remove else R.string.fav_add)

        b.diagnosticBanner.visibility =
            if (prefs.diagnosticMode) android.view.View.VISIBLE else android.view.View.GONE
        // From our own bus, not from session metadata: with no car attached the
        // session's picture is cut for the notification shade, which gets no
        // clock. This screen follows the layout's choice, clock included.
        // Not playing: the station's own picture for it, or its logo faded -
        // see renderMiniPlayer in MainActivity.
        val standby = if (playing == null && station != null && prefs.onlineArtworkEnabled) {
            StandbyArt.phoneUri(this, station, lifecycleScope) { render() }
        } else {
            null
        }
        ArtworkLoader.into(
            lifecycleScope,
            playing?.let { metadata.phoneArtwork(now?.isRealSong == true, PlaybackStatusBus.coverArtUrl.value.takeUnless { joining }, onAir) },
            standby ?: station?.let { metadata.logoDisplayUri(it) },
            station?.let { metadata.logoResId(it) } ?: R.drawable.logo_placeholder,
            b.art,
            // Nothing playing yet, nothing to put a clock in place of.
            playing?.let { metadata.phoneClock(now?.isRealSong == true)?.takeUnless { joining } },
            muted = playing == null && standby == null,
            blank = playing != null && PlaybackStatusBus.artPending(),
            animate = prefs.artworkTransitionsEnabled,
            slideDirection = artworkSlideDirection
        )
        val statusText = getString(
            when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.CONNECTING -> R.string.status_connecting
                PlaybackStatusBus.Status.BUFFERING -> R.string.status_buffering
                PlaybackStatusBus.Status.PLAYING -> R.string.status_playing
                PlaybackStatusBus.Status.RECONNECTING -> R.string.status_reconnecting
                PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> R.string.status_waiting_network
                PlaybackStatusBus.Status.STATION_UNREACHABLE -> R.string.status_unreachable
                PlaybackStatusBus.Status.IDLE -> R.string.status_idle
            }
        )
        // The codec used to be appended here; it lives in the quality chip now,
        // with the bitrate, so the status says only what the player is doing.
        b.status.text = statusText
        renderBitrate(station)
        b.playPause.setImageResource(
            if (controller?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }
}
