package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class PlayerConnectionLabelTest {
    @Test fun briefRefillDoesNotFlash() {
        assertNull(PlayerConnectionLabel.text(PlaybackStatusBus.Status.BUFFERING, true, false))
    }
    @Test fun serviceAnnouncedConnectionIsVisible() {
        assertEquals("Connecting…", PlayerConnectionLabel.text(PlaybackStatusBus.Status.CONNECTING, true, false))
    }
    @Test fun pausedBufferDoesNotSayConnecting() {
        assertNull(PlayerConnectionLabel.text(PlaybackStatusBus.Status.CONNECTING, false, false))
    }
    @Test fun audiblePlaybackHasNoConnectionWarning() {
        assertNull(PlayerConnectionLabel.text(PlaybackStatusBus.Status.CONNECTING, true, true))
    }
    @Test fun networkRecoveryHasDistinctLabels() {
        assertEquals("Waiting for network…", PlayerConnectionLabel.text(PlaybackStatusBus.Status.WAITING_FOR_NETWORK, true, false))
        assertEquals("Reconnecting…", PlayerConnectionLabel.text(PlaybackStatusBus.Status.RECONNECTING, true, false))
    }
}

