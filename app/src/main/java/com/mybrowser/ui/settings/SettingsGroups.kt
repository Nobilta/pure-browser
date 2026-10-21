package com.mybrowser.ui.settings

import com.mybrowser.R

/**
 * One spelling of the settings-group ids shared by import preview, import results and export
 * warnings. Unknown ids return null so a newer file can still be displayed verbatim.
 */
internal fun settingsGroupTitle(id: String): Int? = when (id) {
    "browser" -> R.string.settings_group_browser
    "home" -> R.string.settings_group_home
    "search" -> R.string.settings_group_search
    "downloads" -> R.string.settings_group_downloads
    "filtering" -> R.string.settings_group_filtering
    "sites" -> R.string.settings_group_sites
    "bookmarks" -> R.string.settings_group_bookmarks
    "history" -> R.string.settings_group_history
    else -> null
}
