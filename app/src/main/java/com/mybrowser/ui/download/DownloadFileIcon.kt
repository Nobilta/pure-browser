package com.mybrowser.ui.download

import androidx.annotation.DrawableRes
import com.mybrowser.R

/** Uses the saved name first: many download servers report only application/octet-stream. */
@DrawableRes
internal fun downloadFileIcon(filename: String, mimeType: String): Int {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val extension = filename.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "apk", "apks", "xapk" -> R.drawable.ic_ui_apk
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> R.drawable.ic_ui_archive
        "jpg", "jpeg", "png", "webp", "gif", "svg", "bmp", "avif", "heic" -> R.drawable.ic_ui_image
        "mp4", "mkv", "webm", "avi", "mov", "ts", "m4v", "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus" -> R.drawable.ic_ui_media
        else -> when (normalizedMime) {
            "application/vnd.android.package-archive" -> R.drawable.ic_ui_apk
            "application/zip", "application/x-rar-compressed", "application/vnd.rar",
            "application/x-7z-compressed", "application/x-tar", "application/gzip",
            "application/x-xz", "application/x-bzip2" -> R.drawable.ic_ui_archive
            else -> when (normalizedMime.substringBefore('/')) {
                "image" -> R.drawable.ic_ui_image
                "audio", "video" -> R.drawable.ic_ui_media
                else -> R.drawable.ic_ui_file
            }
        }
    }
}
