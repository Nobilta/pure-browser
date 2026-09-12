package com.mybrowser.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mybrowser.privacy.PrivacyMode
import com.mybrowser.site.SiteSettingsRepository

/** Retains normal/private tabs across Activity recreation; private state never enters a Bundle. */
class BrowserSessionState(application: Application) : AndroidViewModel(application) {
    val privacy = PrivacyMode(application)
    val normalTabs = TabManager()
    val privateTabs = TabManager()
    var privateSites: SiteSettingsRepository? = null
    var initialized = false

    override fun onCleared() {
        normalTabs.cleanup()
        privateTabs.cleanup()
        if (privacy.isIncognito) privacy.exit(wipeSharedStorage = !privacy.hasRealIsolation)
    }
}
