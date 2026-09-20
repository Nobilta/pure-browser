package com.mybrowser.download

import android.content.Context
import android.widget.Toast
import com.mybrowser.R
import kotlinx.coroutines.flow.StateFlow

/**
 * The user-facing half of downloads: what a finished operation turns into on screen, and the one
 * place a page starts a transfer. Keeping it here leaves the Activity to own entry points and
 * routing instead of download phrasing.
 */
class DownloadPresenter(
    private val context: Context,
    private val handler: DownloadHandler,
    private val opener: DownloadFileOpener,
) {
    val downloads: StateFlow<List<DownloadItem>> get() = handler.downloads

    fun openFile(id: Long) = opener.open(id)
    fun cancel(id: Long) = handler.cancel(id)
    fun pause(id: Long) = handler.pause(id)

    fun retry(id: Long) {
        if (handler.retry(id) == null) toast(context.getString(R.string.ui_unable_to_retry_this_download))
    }

    fun delete(id: Long, deleteFile: Boolean) {
        handler.delete(id, deleteFile) { result ->
            when {
                result.failedFileCount > 0 -> toast(
                    context.getString(R.string.ui_unable_to_delete_the_local_file_the_download),
                )
                result.removedCount > 0 && deleteFile -> toast(
                    context.getString(R.string.ui_download_record_and_local_file_deleted),
                )
                result.removedCount > 0 -> toast(context.getString(R.string.ui_download_record_deleted))
            }
        }
    }

    fun clearCompleted(deleteFiles: Boolean) {
        handler.clearCompleted(deleteFiles) { result ->
            when {
                result.failedFileCount > 0 -> toast(
                    context.getString(R.string.downloads_cleared_partial, result.removedCount, result.failedFileCount),
                )
                result.removedCount > 0 && deleteFiles -> toast(
                    context.getString(R.string.ui_cleared_records_and_files, result.removedCount),
                )
                result.removedCount > 0 -> toast(
                    context.getString(R.string.ui_cleared_records, result.removedCount),
                )
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
