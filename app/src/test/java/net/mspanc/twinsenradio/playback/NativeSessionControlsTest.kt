package net.mspanc.twinsenradio.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSessionControlsTest {
    @Test
    fun automotiveBrowseContainsOnlyPlaylists() {
        assertEquals(
            listOf("/playlists"),
            NativeSessionControls.rootIds(automotive = true, hasFavourites = false)
        )
        assertEquals(
            listOf("/playlists"),
            NativeSessionControls.rootIds(automotive = true, hasFavourites = true)
        )
    }

    @Test
    fun phoneBrowseTreeRemainsUnchanged() {
        assertEquals(
            listOf("/fav", "/all", "/recent", "/genres"),
            NativeSessionControls.rootIds(automotive = false, hasFavourites = true)
        )
    }

    @Test
    fun automotivePlayerActionsIncludeShuffleAndRespectArtworkPreference() {
        assertEquals(
            listOf(
                NativeSessionControls.PlayerAction.FAVOURITE,
                NativeSessionControls.PlayerAction.SHUFFLE
            ),
            NativeSessionControls.playerActions(
                automotive = true,
                artworkEnabled = false,
                diagnosticMode = false
            )
        )
        assertEquals(
            listOf(
                NativeSessionControls.PlayerAction.FAVOURITE,
                NativeSessionControls.PlayerAction.SHUFFLE,
                NativeSessionControls.PlayerAction.TRACK_ART,
                NativeSessionControls.PlayerAction.DIAGNOSTIC
            ),
            NativeSessionControls.playerActions(
                automotive = true,
                artworkEnabled = true,
                diagnosticMode = true
            )
        )
    }

    @Test
    fun phoneActionsIncludeShuffleAndOmitAutomotiveArtworkControl() {
        assertEquals(
            listOf(NativeSessionControls.PlayerAction.FAVOURITE,
                NativeSessionControls.PlayerAction.SHUFFLE,
                NativeSessionControls.PlayerAction.DIAGNOSTIC),
            NativeSessionControls.playerActions(
                automotive = false,
                artworkEnabled = true,
                diagnosticMode = true
            )
        )
    }
}
