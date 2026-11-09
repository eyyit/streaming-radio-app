package net.mspanc.twinsenradio.transfer

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingQrTest {
    @Test fun displayedQrRoundTripsFullPairingCapabilityWithoutRemoteQrService() {
        val url = "https://radio-transfer.example/#s=${"a".repeat(64)}&k=${"b".repeat(64)}"
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 560, 560)
        val pixels = IntArray(560 * 560) { i -> if (bits[i % 560, i / 560]) -16777216 else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(560, 560, pixels)))
        assertEquals(url, MultiFormatReader().decode(bitmap).text)
    }
}
