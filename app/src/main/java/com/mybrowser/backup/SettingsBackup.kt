package com.mybrowser.backup

/**
 * Explicit whitelist DTO for the backup file. Every group is optional: a null group
 * means "keep the current device data" on import; within a group a null field means
 * the same. Exported files always contain every group.
 *
 * Besides settings the file carries the browsing library the user collected: bookmarks
 * (with their folders) and history. Both library groups merge on import — they can add
 * and update, but they can never delete what the target device already has.
 *
 * Anything outside these classes — cookies and sessions, site permissions, download
 * files and their records, installed user scripts, SAF grants — is deliberately not
 * representable, so a crafted file cannot smuggle it in.
 */
data class SettingsBackup(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val settings: BackupSettings,
) {
    companion object {
        const val FORMAT_ID = "pure-browser-settings"
        const val SCHEMA_VERSION = 1
    }
}

data class BackupSettings(
    val browser: BackupBrowser? = null,
    val home: BackupHome? = null,
    val search: BackupSearch? = null,
    val downloads: BackupDownloads? = null,
    val filtering: BackupFiltering? = null,
    /** Null = keep current sites; an empty list clears only migratable site preferences. */
    val sites: List<BackupSite>? = null,
    /** Null = leave the target's bookmarks alone; a list merges by URL. */
    val bookmarks: BackupBookmarks? = null,
    /** Null = leave the target's history alone; a list merges by URL. */
    val history: List<BackupHistoryEntry>? = null,
)

/** Folder paths are listed separately so empty folders survive a round trip. */
data class BackupBookmarks(
    val folders: List<List<String>> = emptyList(),
    val entries: List<BackupBookmark> = emptyList(),
)

data class BackupBookmark(
    val title: String,
    val url: String,
    val folderPath: List<String> = emptyList(),
)

data class BackupHistoryEntry(
    val title: String,
    val url: String,
    val visitTime: Long,
    val visitCount: Int,
)

data class BackupBrowser(
    val theme: String? = null,
    val bottomAddressBar: Boolean? = null,
    val swipeTabs: Boolean? = null,
    val autoCheckUpdates: Boolean? = null,
    val incognitoEnabled: Boolean? = null,
    val searchSuggestionsEnabled: Boolean? = null,
    val privateSearchSuggestionsEnabled: Boolean? = null,
    val browserFullscreenEnabled: Boolean? = null,
    val video: BackupVideo? = null,
)

data class BackupVideo(
    val automaticPip: Boolean? = null,
    val backgroundPlayback: Boolean? = null,
    val enhancedControls: Boolean? = null,
    val verticalGestures: Boolean? = null,
    val horizontalSeek: Boolean? = null,
    val holdToBoost: Boolean? = null,
    val boostRate: Float? = null,
    val landscapeFullscreen: Boolean? = null,
    val rememberSpeed: Boolean? = null,
    val preferredSpeed: Float? = null,
)

data class BackupHome(
    val mode: String? = null,
    val fixedUrl: String? = null,
    val restoreLastSession: Boolean? = null,
)

data class BackupSearch(
    val currentEngineId: String? = null,
    /** Null = keep current custom engines; a list (even empty) replaces them all. */
    val customEngines: List<BackupCustomEngine>? = null,
)

data class BackupCustomEngine(
    val id: String,
    val name: String,
    val template: String,
    val suggestUrlTemplate: String? = null,
)

data class BackupDownloads(
    val threadCount: Int? = null,
    val unmeteredOnly: Boolean? = null,
    /** "SYSTEM_DOWNLOADS" or "CUSTOM_DIRECTORY"; a display hint, never an URI. */
    val directoryModeHint: String? = null,
)

data class BackupFiltering(
    val enabled: Boolean? = null,
    val autoUpdate: Boolean? = null,
    val builtIns: List<BackupBuiltInSubscription>? = null,
    /** Null = keep current custom subscriptions; a list replaces them all. */
    val customSubscriptions: List<BackupCustomSubscription>? = null,
)

data class BackupBuiltInSubscription(val id: String, val enabled: Boolean)

data class BackupCustomSubscription(val name: String, val url: String, val enabled: Boolean)

data class BackupSite(val origin: String, val preferences: BackupSitePreferences)

/**
 * Tri-state wrapper for a nullable site preference: [Absent] keeps the current value,
 * [Present] with a null value restores the "follow the browser default" state, and
 * [Present] with a value overrides it. A plain `T?` cannot tell "not in the file"
 * apart from "explicitly null in the file", and the difference is user-visible.
 */
sealed interface BackupOptional<out T> {
    data object Absent : BackupOptional<Nothing>
    data class Present<T>(val value: T) : BackupOptional<T>
}

/**
 * The migratable subset of [com.mybrowser.site.SiteSettings]. Permission decisions
 * (camera, microphone, location, protected media, external apps) are intentionally
 * absent and can neither be exported nor imported.
 */
data class BackupSitePreferences(
    val filtering: Boolean? = null,
    val javaScript: Boolean? = null,
    val images: Boolean? = null,
    val thirdPartyCookies: Boolean? = null,
    val desktop: Boolean? = null,
    val textZoom: Int? = null,
    val webDarkening: Boolean? = null,
    val desktopWidth: Int? = null,
    /** Null means "inherit the browser default" and is itself a migratable choice. */
    val enhancedPlayback: BackupOptional<Boolean?> = BackupOptional.Absent,
    /** Same tri-state: an unset picture shape is a choice the file may carry back. */
    val videoMirror: BackupOptional<Boolean?> = BackupOptional.Absent,
    val videoFit: BackupOptional<String?> = BackupOptional.Absent,
)

/** Anything wrong with a backup file; shown to the user without stack traces. */
class SettingsBackupException(message: String) : Exception(message)
