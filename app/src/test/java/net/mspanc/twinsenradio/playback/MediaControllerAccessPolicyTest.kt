package net.mspanc.twinsenradio.playback

import org.junit.Assert.*
import org.junit.Test

class MediaControllerAccessPolicyTest {
    private fun access(uid: Int = 2000, name: String = "external.controller",
        trusted: Boolean = false, diagnostics: Boolean = false) =
        MediaControllerAccessPolicy.access(uid, name, 1000, "radio.app", trusted, diagnostics)

    @Test fun ownAppHasAllCapabilitiesEvenWithoutExternalTrustFlag() {
        val own = access(uid = 1000, name = "radio.app")
        assertTrue(own.controlPlayback)
        assertTrue(own.editFavorites)
        assertTrue(own.refreshArtwork)
        assertTrue(own.toggleDiagnostics)
    }

    @Test fun spoofedOwnPackageWithoutMatchingUidHasNoControl() {
        val spoofed = access(name = "radio.app", diagnostics = true)
        assertFalse(spoofed.controlPlayback)
        assertFalse(spoofed.editFavorites)
        assertFalse(spoofed.refreshArtwork)
        assertFalse(spoofed.toggleDiagnostics)
    }

    @Test fun trustedNativeHostCanPlayAndFavoriteButCannotRefreshOrEnableDiagnostics() {
        val host = access(trusted = true)
        assertTrue(host.controlPlayback)
        assertTrue(host.editFavorites)
        assertFalse(host.refreshArtwork)
        assertFalse(host.toggleDiagnostics)
        assertTrue(access(trusted = true, diagnostics = true).toggleDiagnostics)
    }

    @Test fun unknownLegacyIdentityStillRetainsTrustedHostControls() {
        val legacy = access(uid = -1, name = "android.media.session.MediaController", trusted = true)
        assertTrue(legacy.controlPlayback)
        assertTrue(legacy.editFavorites)
        assertFalse(legacy.refreshArtwork)
        assertFalse(legacy.toggleDiagnostics)
    }

    @Test fun untrustedUnknownControllerRemainsReadOnly() {
        val external = access(uid = -1, diagnostics = true)
        assertFalse(external.controlPlayback)
        assertFalse(external.editFavorites)
        assertFalse(external.refreshArtwork)
        assertFalse(external.toggleDiagnostics)
    }

    @Test fun uidAndPackageMustBothMatchForAppOnlyArtworkRefresh() {
        assertFalse(access(uid = 1000, name = "another.package", trusted = true).refreshArtwork)
        assertFalse(MediaControllerAccessPolicy.access(-1, "radio.app", -1, "radio.app", false, false).refreshArtwork)
    }
}
