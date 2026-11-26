package com.mybrowser.tabs

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mybrowser.update.UpdateRepository
import androidx.lifecycle.AndroidViewModel
import com.mybrowser.App
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.site.SiteSettingsRepository
import com.mybrowser.text.LocalHtmlDocument
import com.mybrowser.text.LocalHtmlResources
import android.net.Uri
import java.io.IOException

/** Retains normal/private tabs across Activity recreation; private state never enters a Bundle. */
class BrowserSessionState(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val privacy = app.privacyMode
    var pendingPrivateStart = false
    val normalTabs = TabManager()
    val privateTabs = TabManager()
    var privateSites: SiteSettingsRepository? = null
    var initialized = false
    var pendingLocalHtmlDirectory: com.mybrowser.text.LocalHtmlDocument? = null
    val fullscreenPreference = com.mybrowser.data.FullscreenPreferenceState()

    private var startupUpdateStarted = false
    private val updateOffer = MutableStateFlow<UpdateRepository.Release?>(null)
    val startupUpdateOffer = updateOffer.asStateFlow()

    /** Once per browser session; survives recreation and resets when the window is closed. */
    fun checkForStartupUpdate(enabled: Boolean) {
        if (!enabled || startupUpdateStarted) return
        startupUpdateStarted = true
        viewModelScope.launch {
            try {
                updateOffer.value = UpdateRepository(app).check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("App", "Startup update check failed", error)
            }
        }
    }

    fun dismissStartupUpdateOffer() { updateOffer.value = null }

    internal data class LocalHtmlOpenResult(val owner: TabManager, val document: LocalHtmlDocument?)
    private val htmlOpenResults = MutableStateFlow<List<LocalHtmlOpenResult>>(emptyList())
    internal val localHtmlOpenResults = htmlOpenResults.asStateFlow()

    /** Keep slow provider reads and their results across Activity recreation. */
    internal fun openLocalHtml(uri: Uri) {
        val isPrivate = privacy.isIncognito
        val epoch = privacy.sessionEpoch
        val owner = if (isPrivate) privateTabs else normalTabs
        viewModelScope.launch {
            val document = try {
                withContext(Dispatchers.IO) { LocalHtmlDocument.open(app, uri) }
            } catch (_: IOException) { null }
            catch (_: SecurityException) { null }
            if (privacy.isIncognito != isPrivate || (isPrivate && privacy.sessionEpoch != epoch)) return@launch
            htmlOpenResults.value += LocalHtmlOpenResult(owner, document)
        }
    }

    internal fun consumeLocalHtmlOpenResults() { htmlOpenResults.value = emptyList() }

    internal data class LocalHtmlDirectoryResult(val document: LocalHtmlDocument, val saved: Boolean)
    private val htmlDirectoryResult = MutableStateFlow<LocalHtmlDirectoryResult?>(null)
    internal val localHtmlDirectoryResult = htmlDirectoryResult.asStateFlow()

    /** Saving and applying the grant belong to the retained session, including during rotation. */
    internal fun saveLocalHtmlDirectory(document: LocalHtmlDocument, tree: Uri) {
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { LocalHtmlResources.remember(app, document.uri, tree) }
                true
            } catch (_: IOException) { false }
            catch (_: SecurityException) { false }
            if (normalTabs.tabs.none { it.localHtml === document } && privateTabs.tabs.none { it.localHtml === document }) return@launch
            if (saved) {
                document.resourceTree = tree
                (normalTabs.tabs + privateTabs.tabs)
                    .filter { it.localHtml === document && document.matches(it.url) }
                    .forEach { it.reloadOnSelect = true }
            }
            htmlDirectoryResult.value = LocalHtmlDirectoryResult(document, saved)
        }
    }

    internal fun consumeLocalHtmlDirectoryResult() { htmlDirectoryResult.value = null }

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
        app.mediaAccessSession.clear()
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
        pendingLocalHtmlDirectory = null
        htmlDirectoryResult.value = null
        htmlOpenResults.value = htmlOpenResults.value.filterNot { it.owner === privateTabs }
        if (privacy.isIncognito) app.mediaAccessSession.clear()
        // PrivacyMode includes cache files in its asynchronous cleanup barrier. Delivered shares
        // stay in their separate area until the next launch.
        if (privacy.isIncognito) privacy.exit(wipeSharedStorage = !privacy.hasRealIsolation)
        app.downloadHandler.endPrivateScope()
        app.userScripts.endPrivateSession()
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
