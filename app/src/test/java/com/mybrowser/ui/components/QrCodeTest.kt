package com.mybrowser.ui.components

import android.app.Application
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrCodeTest {
    @Test
    fun `a page address survives a round trip through the encoded image`() {
        val url = "https://example.test/some/path?q=pure-browser&lang=zh"

        val bitmap = requireNotNull(QrCode.encode(url, sizePx = 480))

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
        )
        assertEquals(url, decoded.text)
    }

    @Test
    fun `an address that cannot be encoded is reported instead of crashing`() {
        // zxing refuses text beyond the QR capacity; the dialog shows the failure message then.
        assertNull(QrCode.encode("https://example.test/" + "a".repeat(8_000)))
    }
}
