package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class StreamArtworkStateTest {
    private val state = StreamArtworkState()

    @Test fun newIcyCoverNeverBelongsToLaggingApiDescription() {
        state.update(IcyArtworkParser.Update("Artist - New song", "https://example.com/new.jpg", true, true))
        assertNull(state.urlFor("Artist - Old song"))
        assertEquals("https://example.com/new.jpg", state.urlFor("Artist - New song"))
    }

    @Test fun newTitleWithoutArtworkClearsPreviousCover() {
        state.update(IcyArtworkParser.Update("Old song", "https://example.com/old.jpg", true, true))
        state.update(IcyArtworkParser.Update("New song", null, true, false))
        assertNull(state.urlFor("Old song"))
        assertNull(state.urlFor("New song"))
    }

    @Test fun artworkOnlyChangeAndExplicitClearKeepTitleAssociation() {
        state.update(IcyArtworkParser.Update("Song", "https://example.com/one.jpg", true, true))
        state.update(IcyArtworkParser.Update(null, "https://example.com/two.jpg", false, true))
        assertEquals("https://example.com/two.jpg", state.urlFor("Song"))
        state.update(IcyArtworkParser.parse("StreamArtwork='';"))
        assertTrue(state.matches("Song"))
        assertNull(state.urlFor("Song"))
    }

    @Test fun explicitEmptyAfterAbsentChangesOnceAndTitleChangeResetsPresence() {
        assertTrue(state.update(IcyArtworkParser.Update("Song", null, true, false)))
        val clear = IcyArtworkParser.Update("Song", "", true, true)
        assertTrue(state.update(clear))
        assertFalse(state.update(clear))
        state.update(IcyArtworkParser.Update("Next song", null, true, false))
        assertTrue(state.update(IcyArtworkParser.Update("Next song", "", true, true)))
    }

    @Test fun stationResetAlsoResetsExplicitArtworkPresence() {
        val clear = IcyArtworkParser.Update(null, "", false, true)
        assertTrue(state.update(clear))
        assertFalse(state.update(clear))
        state.clear()
        assertTrue(state.update(clear))
    }

    @Test fun stationChangeClearsAssociation() {
        state.update(IcyArtworkParser.Update("Song", "https://example.com/one.jpg", true, true))
        state.clear()
        assertNull(state.urlFor("Song"))
        assertFalse(state.matches("Song"))
    }
}
