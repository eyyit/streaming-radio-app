package net.mspanc.twinsenradio.playback

/** A downloaded logo must supply real pixels on both axes; never upscale favicons. */
internal object StationLogoQuality {
    const val MIN_DIMENSION = 512
    fun accepts(width: Int, height: Int): Boolean =
        width >= MIN_DIMENSION && height >= MIN_DIMENSION
}
