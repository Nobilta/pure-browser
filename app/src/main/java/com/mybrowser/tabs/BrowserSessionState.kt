package com.mybrowser.tabs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mybrowser.App
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.site.SiteSettingsRepository

/** Retains normal/private tabs across Activity recreation; private state never enters a Bundle. */
class BrowserSessionState(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val privacy = app.privacyMode
    var pendingPrivateStart = false
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

    /**
     * Every beginning of a private session goes through here, so the in-memory state that belongs to
     * one is dropped at the same moment the session is created rather than only when the user
     * toggles the mode by hand. A cold start that opens straight into incognito, an Activity
     * recreation, and the menu action all arrive at the same place.
     */
    fun beginPrivateSession() {
        check(!privacy.isTransitioning)
        if (privacy.isIncognito) return
        app.downloadHandler.rotatePrivateScope()
        // A script's GM values are the private session's: held in memory and dropped with it. The
        // store is process-scoped, so the session has to say when it begins; a session that starts
        // without this inherits whatever the last one left behind.
        app.userScripts.beginPrivateSession()
        privacy.enter()
    }

    /**
     * The other end: [onCleared] is the only place a session can end without the user toggling the
     * mode — closing the last browser window destroys the Activity and keeps the process — and it
     * has to drop the same values, or the next launch in this process reads them.
     */
    fun endPrivateSession() {
        app.downloadHandler.endPrivateScope()
        app.userScripts.endPrivateSession()
        if (privacy.isIncognito) privacy.exit(wipeSharedStorage = !privacy.hasRealIsolation)
    }

    override fun onCleared() {
        downloadRequests.resetTransientState()
        // The last session's private suggestions die with it: a later launch in the
        // same process must start from an empty cache, not the previous session's terms.
        (getApplication<App>()).searchSuggestionProvider.rotatePrivateSession()
        normalTabs.cleanup()
        privateTabs.cleanup()
        endPrivateSession()
    }
}
