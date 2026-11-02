package com.mybrowser.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only the result summary belongs in saved state; the backup itself can be several MiB. */
internal data class SettingsExportSummary(
    val omittedBookmarks: Int,
    val omittedHistory: Int,
    val skippedGroups: List<String>,
)

internal class SettingsExportFeedback(activity: ComponentActivity) {
    var result by mutableStateOf(
        activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)
            ?.takeIf { it.containsKey("bookmarks") }?.let {
                SettingsExportSummary(
                    it.getInt("bookmarks"), it.getInt("history"),
                    it.getStringArrayList("groups").orEmpty(),
                )
            },
    )
        private set

    init {
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply {
                result?.let {
                    putInt("bookmarks", it.omittedBookmarks)
                    putInt("history", it.omittedHistory)
                    putStringArrayList("groups", ArrayList(it.skippedGroups))
                }
            }
        }
    }

    fun show(export: ExportResult) {
        result = SettingsExportSummary(export.omittedBookmarks, export.omittedHistory, export.skippedGroups.toList())
    }

    fun dismiss() { result = null }

    private companion object {
        const val STATE_KEY = "settings_export_result"
    }
}
