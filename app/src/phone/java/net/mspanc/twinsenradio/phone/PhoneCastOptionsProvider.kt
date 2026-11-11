package net.mspanc.twinsenradio.phone

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Uses Google's hosted Default Media Receiver; it needs no app registration. */
class PhoneCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Android system volume needs the selected Cast routing session.
            .setSessionTransferEnabled(true)
            // Some system switchers move visually without changing receiver volume.
            // Use the receiver-backed dialog for Cast icon volume control.
            .setShowSystemOutputSwitcherOnCastIconClick(false)
            .setRemoteToLocalEnabled(false)
            .setCastMediaOptions(CastMediaOptions.Builder()
                // The app supplies device volume; MR2's aggregate may report zero.
                .setMediaSessionEnabled(false)
                .setNotificationOptions(null)
                .build())
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
