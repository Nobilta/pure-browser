package com.mybrowser.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mybrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DownloadOpenResult {
    data class Ready(val intent: Intent) : DownloadOpenResult
    data object Unavailable : DownloadOpenResult
    data object NotCompleted : DownloadOpenResult
}

internal object DownloadFiles {
    fun inspect(context: Context, uri: Uri, filename: String): DownloadOpenResult {
        if (uri.scheme != "content") return DownloadOpenResult.Unavailable
        // Download history can outlive the file or the user's SAF permission grant.
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: return DownloadOpenResult.Unavailable
        } catch (_: Exception) { return DownloadOpenResult.Unavailable }
        // Let Android resolve the provider's MIME type and choose the viewer/installer.
        return DownloadOpenResult.Ready(Intent(Intent.ACTION_VIEW, uri).apply {
            clipData = ClipData.newRawUri(filename, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

/** A completed-row tap hands the readable file to Android without type-specific browser UI. */
class DownloadFileOpener(
    private val activity: ComponentActivity,
    private val handler: () -> DownloadHandler,
    private val message: (String) -> Unit,
) {
    private var opening = false

    fun open(id: Long) {
        if (opening) return
        opening = true
        activity.lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { handler().fileToOpen(id) }) {
                    DownloadOpenResult.Unavailable -> message(activity.getString(R.string.download_file_unavailable))
                    DownloadOpenResult.NotCompleted -> message(activity.getString(R.string.download_not_complete))
                    is DownloadOpenResult.Ready -> try {
                        activity.startActivity(result.intent)
                    } catch (_: ActivityNotFoundException) {
                        message(activity.getString(R.string.ui_no_app_can_open_this_file))
                    } catch (_: SecurityException) {
                        message(activity.getString(R.string.download_file_unavailable))
                    }
                }
            } finally { opening = false }
        }
    }
}
