package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID
import net.mspanc.twinsenradio.BuildConfig
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MetadataFactoryLogoDisplayUriTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun rejectedStoreImageUsesSuppliedUrlOnlyOnPhone() {
        val station = station()
        val file = writeStationImage(station, 563, 450)

        try {
            assertFalse(StationArtworkStore.hasDownloaded(context, station))
            val expected = if (BuildConfig.IS_AUTOMOTIVE) {
                StationArtworkStore.uri(context, station)
            } else {
                Uri.parse(checkNotNull(station.logoUrl))
            }

            assertEquals(expected, MetadataFactory(context, Prefs(context))
                .logoDisplayUri(station))
        } finally {
            file.delete()
        }
    }

    @Test
    fun disabledOnlineArtworkKeepsTheLocalStoreUri() {
        val station = station()
        val prefs = Prefs(context)
        prefs.onlineArtworkEnabled = false

        try {
            assertEquals(StationArtworkStore.uri(context, station),
                MetadataFactory(context, prefs).logoDisplayUri(station))
        } finally {
            prefs.onlineArtworkEnabled = true
        }
    }

    @Test
    fun suppliedNonNetworkLogoUrisNeverReachPhoneLoader() {
        val station = station()
        val expected = StationArtworkStore.uri(context, station)
        val factory = MetadataFactory(context, Prefs(context))

        listOf(
            "file:///data/user/0/app/private.png",
            "content://provider/private-image"
        ).forEach { logoUrl ->
            assertEquals(logoUrl, expected,
                factory.logoDisplayUri(station.copy(logoUrl = logoUrl)))
        }
    }

    @Test
    fun explicitGenericChoiceKeepsLocalStoreUri() {
        val station = station()
        val genericKey = "generic-${M3uParser.stableKey(station.id)}"
        val saved = context.getSharedPreferences("station_artwork_checks", 0)
            .edit().putBoolean(genericKey, true).commit()
        assertTrue(saved)

        assertEquals(StationArtworkStore.uri(context, station),
            MetadataFactory(context, Prefs(context)).logoDisplayUri(station))
    }

    @Test
    fun acceptedStoreImageKeepsPriorityOverSuppliedUrl() {
        val station = station()
        val file = writeStationImage(station, 563, 512)

        try {
            assertTrue(StationArtworkStore.hasDownloaded(context, station))
            assertEquals(StationArtworkStore.uri(context, station),
                MetadataFactory(context, Prefs(context)).logoDisplayUri(station))
        } finally {
            file.delete()
        }
    }

    @Test
    fun customLocalLogoKeepsPriorityOverAcceptedStoreImage() {
        val station = station()
        val downloaded = writeStationImage(station, 563, 512)
        val custom = File.createTempFile("custom-logo-", ".png", context.cacheDir)
        custom.writeBytes(byteArrayOf(1))
        val prefs = Prefs(context)
        prefs.setCustomLogo(station.id, custom.absolutePath)

        try {
            assertEquals(Uri.fromFile(custom),
                MetadataFactory(context, prefs).logoDisplayUri(station))
        } finally {
            prefs.setCustomLogo(station.id, null)
            downloaded.delete()
            custom.delete()
        }
    }

    private fun station() = Station(
        id = "logo-display-${UUID.randomUUID()}",
        name = "Imported station",
        genre = "Playlist",
        stream = "https://radio.example/live",
        logoUrl = "https://logos.example/station.jpg",
        source = Station.Source.USER_M3U
    )

    private fun writeStationImage(
        station: Station,
        width: Int,
        height: Int
    ): File {
        val directory = File(context.filesDir, "station-art").apply {
            mkdirs()
        }
        val file = File(directory, "${M3uParser.stableKey(station.id)}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
