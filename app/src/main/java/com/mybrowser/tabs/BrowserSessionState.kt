package com.mybrowser.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mybrowser.App
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.privacy.PrivacyMode
import com.mybrowser.site.SiteSettingsRepository

/** Retains normal/private tabs across Activity recreation; private state never enters a Bundle. */
class BrowserSessionState(application: Application) : AndroidViewModel(application) {
    val privacy = PrivacyMode(application)
    val normalTabs = TabManager()
    val privateTabs = TabManager()
    var privateSites: SiteSettingsRepository? = null
    var initialized = false
    val fullscreenPreference = com.mybrowser.data.FullscreenPreferenceState()

    /**
     * Download confirmations outlive an Activity recreation (rotation) but no longer:
     * they hold cookies and sign-in context of a live session, so they are dropped with
     * the ViewModel instead of being saved anywhere.
     */
    val downloadRequests = DownloadRequestCoordinator((application as App).downloadHandler)

    override fun onCleared() {
        downloadRequests.resetTransientState()
        // The last session's private suggestions die with it: a later launch in the
        // same process must start from an empty cache, not the previous session's terms.
        (getApplication<App>()).searchSuggestionProvider.rotatePrivateSession()
        normalTabs.cleanup()
        privateTabs.cleanup()
        if (privacy.isIncognito) privacy.exit(wipeSharedStorage = !privacy.hasRealIsolation)
    }
}
