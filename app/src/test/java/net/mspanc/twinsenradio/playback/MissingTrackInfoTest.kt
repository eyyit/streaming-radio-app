package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingTrackInfoTest {
    @Test fun playingWithoutTrackMetadataShowsNoteAndDelayedMetadataClearsIt() {
        assertEquals(
            "No track info received",
            MissingTrackInfo.text(playing = true, hasTrackTitle = false, hasArtist = false)
        )
        assertNull(MissingTrackInfo.text(playing = true, hasTrackTitle = true, hasArtist = true))
    }

    @Test fun pausedOrBufferingWithoutMetadataDoesNotShowNote() {
        assertNull(MissingTrackInfo.text(playing = false, hasTrackTitle = false, hasArtist = false))
    }

    @Test fun titleOrArtistAloneCountsAsTrackInformation() {
        assertNull(MissingTrackInfo.text(playing = true, hasTrackTitle = true, hasArtist = false))
        assertNull(MissingTrackInfo.text(playing = true, hasTrackTitle = false, hasArtist = true))
    }
}
