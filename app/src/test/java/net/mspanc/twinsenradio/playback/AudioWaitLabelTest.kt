package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class AudioWaitLabelTest {
    private val label = AudioWaitLabel()
    private fun text(time: Long, station: String? = "a", wants: Boolean = true,
                     playing: Boolean = false, suppressed: Boolean = false,
                     status: PlaybackStatusBus.Status = PlaybackStatusBus.Status.BUFFERING) =
        label.text(station, wants, playing, suppressed, status, time)

    @Test fun startupDebouncesThenClearsImmediatelyWhenAudioPlays() {
        assertNull(text(0))
        assertNull(text(399))
        assertEquals("Starting audio…", text(400))
        assertNull(text(401, playing = true))
    }

    @Test fun rebufferHasLongerEntryGraceAndPauseCancelsIt() {
        assertNull(text(0, playing = true))
        assertNull(text(100))
        assertNull(text(799))
        assertEquals("Buffering audio…", text(800))
        assertNull(text(801, wants = false))
        assertNull(text(900))
        assertEquals("Starting audio…", text(1300))
    }

    @Test fun newStationStartsFreshInsteadOfInheritingOldStationHistory() {
        text(0, playing = true)
        assertNull(text(100, station = "b"))
        assertEquals("Starting audio…", text(500, station = "b"))
        assertNull(text(501, station = null))
    }

    @Test fun onlyConfirmedNetworkStateNamesNetworkAndPlayingWins() {
        text(0)
        assertEquals("Waiting for network…", text(400, status = PlaybackStatusBus.Status.WAITING_FOR_NETWORK))
        assertEquals("Reconnecting…", text(401, status = PlaybackStatusBus.Status.RECONNECTING))
        assertNull(text(402, playing = true, status = PlaybackStatusBus.Status.WAITING_FOR_NETWORK))
    }

    @Test fun suppressionIsNotReportedAsBufferingAndRestartsGrace() {
        text(0)
        assertNull(text(1000, suppressed = true))
        assertNull(text(1001))
        assertEquals("Starting audio…", text(1401))
    }

    @Test fun terminalFailureIsNotAnEndlessLoadingMessage() {
        assertEquals("Station unavailable", text(0, status = PlaybackStatusBus.Status.STATION_UNREACHABLE))
        assertNull(text(1, wants = false, status = PlaybackStatusBus.Status.STATION_UNREACHABLE))
    }
}
