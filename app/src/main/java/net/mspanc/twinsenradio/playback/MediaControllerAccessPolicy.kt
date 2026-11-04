package net.mspanc.twinsenradio.playback

/** Decisions use Media3's verified trust/UID information, never package name alone. */
internal object MediaControllerAccessPolicy {
    data class Access(
        val controlPlayback: Boolean,
        val editFavorites: Boolean,
        val refreshArtwork: Boolean,
        val toggleDiagnostics: Boolean
    )

    fun access(uid: Int, packageName: String, appUid: Int, appPackageName: String,
        trusted: Boolean, diagnosticsEnabled: Boolean): Access {
        val own = uid >= 0 && uid == appUid && packageName == appPackageName
        val control = own || trusted
        return Access(
            controlPlayback = control,
            editFavorites = control,
            refreshArtwork = control,
            // Car/notification diagnostics is an existing turn-OFF shortcut.
            // Only the app may enable diagnostics and its additional logging.
            toggleDiagnostics = own || (trusted && diagnosticsEnabled)
        )
    }
}
