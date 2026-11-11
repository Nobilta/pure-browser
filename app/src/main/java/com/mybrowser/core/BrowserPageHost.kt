package com.mybrowser.core

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.devtools.DevToolsController
import com.mybrowser.download.PageFileDownload
import com.mybrowser.filter.CosmeticFilterRuntime
import com.mybrowser.filter.ElementPicker
import com.mybrowser.filter.FilterController
import com.mybrowser.media.MediaPlaybackTracker
import com.mybrowser.media.MediaTrackerRegistry
import com.mybrowser.site.SiteSettings
import com.mybrowser.site.SiteSettingsRepository
import com.mybrowser.tabs.RecentTabStore
import com.mybrowser.userscript.UserScriptRuntime
import com.mybrowser.userscript.UserScriptStore
import kotlinx.coroutines.CoroutineScope

/** Owns one window's WebViews and their document channels. All lifecycle calls run on main. */
internal class BrowserPageHost(
    activity: Activity,
    private val onReleased: (WebView) -> Unit,
) : AutoCloseable {
    private val pool = WebViewPool(activity)
    var current: WebView? by mutableStateOf(null)
    var ownerTabId: String? = null
    var readyTabId: String? = null
    val media = MediaTrackerRegistry()
    val devTools = DevToolsController()

    data class ParkedPage(val view: WebView, val settings: SiteSettings, val filtering: Boolean)
    val parked = RecentTabStore<ParkedPage> { discard(it.view) }

    // Slots are assigned before installation, so a failed setup closes every channel it created.
    private class Runtime {
        var scripts: UserScriptRuntime? = null
        var cosmetic: CosmeticFilterRuntime? = null
        var picker: ElementPicker? = null
        var files: PageFileDownload? = null
    }
    private val runtimes = mutableMapOf<WebView, Runtime>()
    private val popupTransfers = mutableMapOf<WebView, AutoCloseable>()

    fun acquire(configure: (WebView) -> Unit, evict: () -> Boolean): WebView = acquireWithMemoryRecovery(
        create = pool::acquireFresh,
        configure = { view -> runtimes[view] = Runtime(); configure(view) },
        discard = ::discard,
        evict = evict,
    )

    /** Attach every channel before the first document or Chromium popup transport arrives. */
    fun install(
        view: WebView,
        scope: CoroutineScope,
        scripts: UserScriptStore,
        filter: FilterController,
        sites: SiteSettingsRepository,
        isPrivate: () -> Boolean,
        excludedOrigins: Set<String>,
        fileListener: PageFileDownload.Listener,
        onMediaSignal: (WebView, MediaPlaybackTracker, MediaPlaybackTracker.Signal) -> Unit,
    ) {
        val runtime = runtimes.getValue(view)
        CosmeticFilterRuntime(view, filter, sites).also {
            runtime.cosmetic = it
            it.excludedOrigins = excludedOrigins
            it.install()
        }
        media.install(view, onMediaSignal)
        ElementPicker(view).also { runtime.picker = it; it.install() }
        PageFileDownload(view).also {
            runtime.files = it
            it.listener = fileListener
            it.install()
            it.startScript()
        }
        UserScriptRuntime(view, scripts, scope, isPrivate, isCurrent = { current === view }).also {
            runtime.scripts = it
            it.install()
        }
        devTools.attach(view)
    }

    fun scriptsFor(view: WebView) = runtimes[view]?.scripts
    fun cosmeticFor(view: WebView) = runtimes[view]?.cosmetic
    fun pickerFor(view: WebView) = runtimes[view]?.picker
    fun filesFor(view: WebView) = runtimes[view]?.files
    fun fileOwner(url: String) = runtimes.values.firstNotNullOfOrNull { it.files?.takeIf { file -> file.owns(url) } }

    fun setExcludedOrigins(origins: Set<String>) {
        runtimes.values.forEach { it.cosmetic?.excludedOrigins = origins }
    }

    fun onDocumentChanged(view: WebView, url: String) {
        filesFor(view)?.onDocumentChanged()
        scriptsFor(view)?.onDocumentChanged(url)
        cosmeticFor(view)?.onDocumentChanged(url)
    }

    fun onPageFinished(view: WebView, url: String) {
        scriptsFor(view)?.onPageFinished(url)
        cosmeticFor(view)?.refresh(url)
    }

    fun prepareForPopup(view: WebView) {
        media.trackerOf(view)?.prepareForPopup()
        scriptsFor(view)?.prepareForPopup()
        cosmeticFor(view)?.prepareForPopup()
        filesFor(view)?.prepareForPopup()
    }

    fun holdForPopup(opener: WebView, popup: WebView) {
        popupTransfers[popup] = pool.holdForPopup(opener, popup)
    }

    fun onPopupContentsAttached(view: WebView, attach: Boolean) {
        try {
            if (attach && pool.owns(view)) {
                media.trackerOf(view)?.onPopupContentsAttached()
                scriptsFor(view)?.onPopupContentsAttached()
                cosmeticFor(view)?.onPopupContentsAttached()
                filesFor(view)?.onPopupContentsAttached()
            }
        } finally {
            // A destroyed Activity still receives transport completion; native teardown waits for it.
            popupTransfers.remove(view)?.close()
        }
    }

    fun suspend(view: WebView, onRendererGone: (WebView) -> Unit) {
        (view as? BrowserWebView)?.keepMediaOnWindowHidden = false
        media.trackerOf(view)?.setSuspended(true)
        view.onPause()
        view.webViewClient = ParkedWebViewClient(onRendererGone)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Detach channels before wiping a private profile or destroying its native contents. */
    fun detach(view: WebView) {
        val runtime = runtimes.remove(view) ?: return
        onReleased(view)
        devTools.detach(view)
        runtime.scripts?.close()
        runtime.cosmetic?.close()
        media.remove(view)?.close()
        runtime.picker?.dispose()
        runtime.files?.close()
    }

    fun discard(view: WebView) {
        detach(view)
        pool.discard(view)
    }

    override fun close() {
        parked.clear()
        runtimes.keys.toList().forEach(::detach)
        devTools.close()
        pool.close()
        current = null
        ownerTabId = null
        readyTabId = null
    }
}
