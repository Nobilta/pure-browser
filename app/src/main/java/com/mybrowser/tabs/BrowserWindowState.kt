package com.mybrowser.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mybrowser.App
import com.mybrowser.privacy.PrivacyMode
import com.mybrowser.site.SiteSettingsRepository

/** Survives Activity recreation without sharing document state with another window. */
class BrowserWindowState(application: Application) : AndroidViewModel(application) {
    val privacy = PrivacyMode(application)
    lateinit var normalTabs: TabManager
        private set
    val privateTabs = TabManager(rememberClosedTabs = false)
    var privateSites: SiteSettingsRepository? = null
    var initialized = false
    var clearBrowsingPage: ((String?) -> Unit)? = null
    private var key: String? = null
    fun attach(name: String) {
        if (key != null) return
        require(name == "main" || name == "secondary")
        key = name
        normalTabs = if (name == "main") TabManager() else TabManager(recentPreferenceName = "recently_closed_secondary")
        getApplication<App>().windows[name] = this
    }
    override fun onCleared() {
        clearBrowsingPage = null
        key?.let { getApplication<App>().windows.remove(it) }
        if (::normalTabs.isInitialized) normalTabs.cleanup()
        privateTabs.cleanup()
        if (privacy.isIncognito) privacy.exit(wipeSharedStorage = !privacy.hasRealIsolation)
    }
}
