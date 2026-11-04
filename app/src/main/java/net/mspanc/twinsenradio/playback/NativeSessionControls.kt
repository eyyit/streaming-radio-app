package net.mspanc.twinsenradio.playback

/** Native AAOS browse and player controls exposed by the media session. */
internal object NativeSessionControls {
    enum class PlayerAction { FAVOURITE, SHUFFLE, TRACK_ART, DIAGNOSTIC }

    fun rootIds(automotive: Boolean, hasFavourites: Boolean): List<String> =
        if (automotive) {
            if (hasFavourites) listOf("/fav", "/all")
            else listOf("/all", "/fav")
        } else {
            listOf("/fav", "/all", "/recent", "/genres")
        }

    fun playerActions(
        automotive: Boolean,
        artworkEnabled: Boolean,
        diagnosticMode: Boolean
    ): List<PlayerAction> = buildList {
        add(PlayerAction.FAVOURITE)
        if (automotive) add(PlayerAction.SHUFFLE)
        if (automotive && artworkEnabled) add(PlayerAction.TRACK_ART)
        if (diagnosticMode) add(PlayerAction.DIAGNOSTIC)
    }
}
