package com.mybrowser.qr

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mybrowser.core.UrlUtils
import kotlin.math.roundToInt

/** Offline QR decoding. Camera instances are confined to their capture worker. */
internal class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    fun luminance(bytes: ByteArray, width: Int, height: Int): String? = decode(
        PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false),
    )

    private fun decode(source: LuminanceSource): String? {
        for (candidate in listOf(source, source.invert())) {
            try { return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }
            catch (_: ReaderException) { /* A frame without a QR code is normal. */ }
            finally { reader.reset() }
        }
        return null
    }

    fun image(resolver: ContentResolver, uri: Uri): String? {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
        } finally { bitmap.recycle() }
    }
}

/** Never turn arbitrary QR text into a search, script, file access or app intent. */
internal fun qrWebUrlOrNull(content: String): String? {
    val text = content.trim()
    if (UrlUtils.isHttpUrl(text)) return text
    if (!UrlUtils.isNavigableInput(text)) return null
    return UrlUtils.normalizeOrSearch(text, "pure-text:%s").takeIf(UrlUtils::isHttpUrl)
}
