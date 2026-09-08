package com.mybrowser.home

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

object ShortcutIconLoader {
    /** Decode the selected document directly to icon size, including EXIF orientation. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Expected a selected document" }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val edge = maxOf(info.size.width, info.size.height)
            require(edge > 0) { "Invalid image dimensions" }
            val scale = minOf(1.0, 96.0 / edge)
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
