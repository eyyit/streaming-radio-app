package net.mspanc.twinsenradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.Id3Decoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HlsEmptyTitleServiceTest {
    @Test fun emptyHlsSnapshotClearsTitleAndCoverAndSameTitleCanReturn() = withService { service, _, events ->
        events.onMetadata(id3("Artist - Song"))
        assertEquals("Artist - Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        seedCover(service)
        events.onMetadata(id3(""))
        assertNull("an explicit empty HLS title must clear the published track", PlaybackStatusBus.nowPlaying.value)
        assertNull(field(service, "coverArtUrl"))
        events.onMetadata(id3(""))
        assertNull(PlaybackStatusBus.nowPlaying.value)
        events.onMetadata(id3("Artist - Song"))
        assertEquals("Artist - Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        events.onMetadata(id3(""))
        assertNull(PlaybackStatusBus.nowPlaying.value)
    }

    @Test fun blankIcyTitleKeepsItsLegacyIgnoreBehavior() = withService { service, _, events ->
        events.onMetadata(id3("Artist - Song"))
        seedCover(service)
        val raw = "StreamTitle='';".toByteArray()
        events.onMetadata(Metadata(IcyInfo(raw, "", null)))
        assertEquals("Artist - Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        assertEquals("content://fixture/cover", field(service, "coverArtUrl"))
    }

    @Test fun blankIcyTitleWithExplicitArtworkClearKeepsTitleButClearsCover() = withService { service, _, events ->
        events.onMetadata(id3("Artist - Song"))
        seedCover(service)
        val raw = "StreamTitle='';StreamArtwork='';".toByteArray()
        events.onMetadata(Metadata(IcyInfo(raw, "", null)))
        assertEquals("Artist - Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        assertNull("an explicit artwork clear must not depend on the ignored ICY title", field(service, "coverArtUrl"))
    }

    @Test fun titleApiStillOwnsDescriptionWhenHlsTitleClears() = withService { service, player, events ->
        player.setMediaItem(MediaItem.Builder().setMediaId("st:rns")
            .setUri("https://audio.invalid/silence").build())
        PlaybackStatusBus.setNowPlaying(NowPlaying.parse("API Artist - API Song"))
        seedCover(service)
        events.onMetadata(id3("", includeArtwork = false))
        assertEquals("API Artist - API Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        assertEquals("content://fixture/cover", field(service, "coverArtUrl"))
    }

    @Test fun explicitEmptyArtworkAfterAbsentArtworkClearsOnceUnderApiAuthority() = withService { service, player, events ->
        player.setMediaItem(MediaItem.Builder().setMediaId("st:rns")
            .setUri("https://audio.invalid/silence").build())
        PlaybackStatusBus.setNowPlaying(NowPlaying.parse("API Artist - API Song"))
        seedCover(service)
        events.onMetadata(id3("", includeArtwork = false))
        assertEquals("content://fixture/cover", field(service, "coverArtUrl"))
        events.onMetadata(id3(""))
        assertEquals("API Artist - API Song", PlaybackStatusBus.nowPlaying.value!!.raw)
        assertNull("the first explicit clear must differ from absent artwork", field(service, "coverArtUrl"))
        val generation = field(service, "coverGeneration")
        events.onMetadata(id3(""))
        events.onMetadata(id3(""))
        assertEquals("repeated clears must not restart artwork lookup", generation, field(service, "coverGeneration"))
    }

    private fun withService(block: (RadioService, ExoPlayer, Player.Listener) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context).apply { onlineArtworkEnabled = false }
        // Attach the real service without onCreate: no session, playlist load, or playback.
        val service = Robolectric.buildService(RadioService::class.java).get()
        val player = ExoPlayer.Builder(context).build()
        setField(service, "prefs", prefs)
        setField(service, "player", player)
        setField(service, "repo", StationRepository.get(context))
        PlaybackStatusBus.setNowPlaying(null)
        PlaybackStatusBus.setCoverArt(null)
        val eventClass = RadioService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
        val events = eventClass.getDeclaredConstructor(RadioService::class.java)
            .apply { isAccessible = true }.newInstance(service) as Player.Listener
        try { block(service, player, events) } finally {
            (field(service, "scope") as CoroutineScope).cancel()
            player.release()
            PlaybackStatusBus.setNowPlaying(null)
            PlaybackStatusBus.setCoverArt(null)
        }
    }

    private fun seedCover(service: RadioService) {
        setField(service, "coverArtUrl", "content://fixture/cover")
        PlaybackStatusBus.setCoverArt("content://fixture/cover")
    }

    private fun field(service: RadioService, name: String): Any? =
        RadioService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: RadioService, name: String, value: Any?) {
        RadioService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun id3(title: String, includeArtwork: Boolean = true): Metadata {
        fun sync(size: Int) = byteArrayOf((size shr 21 and 127).toByte(),
            (size shr 14 and 127).toByte(), (size shr 7 and 127).toByte(), (size and 127).toByte())
        fun frame(id: String, text: String): ByteArray {
            val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
            return id.toByteArray() + sync(payload.size) + byteArrayOf(0, 0) + payload
        }
        val body = frame("TIT2", title) +
            if (includeArtwork) frame("TXXX", "StreamArtwork\u0000") else byteArrayOf()
        val bytes = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + sync(body.size) + body
        return Id3Decoder().decode(bytes, bytes.size)!!
    }
}
