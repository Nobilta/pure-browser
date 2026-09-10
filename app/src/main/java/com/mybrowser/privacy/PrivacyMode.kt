package com.mybrowser.privacy

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns whether the browser is currently incognito, and enforces what that means.
 *
 * The mode is process-wide rather than per-tab. Per-tab incognito needs a second WebView
 * profile alive at the same time, and two profiles means two cookie jars that the pool would
 * have to keep straight for every acquire — a real source of "why am I logged out" bugs.
 * A single mode for the whole window is what X Browser and Via do, and it is honest about
 * what the user gets.
 */
class PrivacyMode(private val appContext: android.content.Context) {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var lastCleanupSucceeded: Boolean by mutableStateOf(true)
        private set

    /** True while incognito. Compose reads this to switch the theme accent and badge. */
    var isIncognito: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the current incognito session has real storage isolation. False means the
     * fallback: data is wiped on exit but shared while open. Meaningless when
     * [isIncognito] is false.
     */
    var hasRealIsolation: Boolean by mutableStateOf(false)
        private set

    /** Credentials always come from the profile that owns the requesting page. */
    fun cookiesFor(url: String): String? = runCatching {
        val manager = if (isIncognito && hasRealIsolation) IncognitoProfile.cookieManager()
            else CookieManager.getInstance()
        manager?.getCookie(url)
    }.getOrNull()

    /**
     * Configures [view] for the current mode. Call before the WebView loads anything.
     *
     * Returns true if real isolation was obtained. In incognito, this also turns off the
     * per-WebView HTTP cache that could otherwise write to disk regardless of profile.
     * WebView's old form-data switch was removed from Chromium and has been a no-op since
     * API 26, so current Android versions have nothing useful to toggle here.
     */
    fun applyTo(view: WebView): Boolean {
        if (!isIncognito) {
            view.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            return false
        }

        val isolated = IncognitoProfile.attach(view)
        hasRealIsolation = isolated

        view.settings.apply {
            // LOAD_NO_CACHE stops disk cache reads and writes for this WebView. With a
            // profile this is belt-and-braces; without one it is the only thing keeping
            // incognito pages out of the shared cache.
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }

        // Geolocation grants are stored per-origin on disk and outlive the session. An
        // incognito session that leaks "this user was at these coordinates" defeats the
        // point, so it starts from a clean slate every time.
        // WebsitePermissions always replies with retain=false; entering private mode
        // must not revoke the default profile's remembered location choices.

        return isolated
    }

    /**
     * Enters incognito. The caller must rebuild its WebView afterwards — a profile cannot be
     * attached to an instance that has already loaded a page.
     */
    fun enter() {
        IncognitoProfile.prepare()
        isIncognito = true
    }

    /**
     * Leaves incognito and destroys the session's data.
     *
     * [wipeSharedStorage] must be true only when there is no real isolation, because the
     * wipe is indiscriminate: it clears normal-mode cookies too. The caller knows which case
     * it is from [hasRealIsolation]; passing that value through is the intended use.
     */
    fun exit(wipeSharedStorage: Boolean, onComplete: () -> Unit = {}) {
        isIncognito = false
        lastCleanupSucceeded = IncognitoProfile.destroy()
        if (wipeSharedStorage) {
            wipeEverything(onComplete)
        } else {
            dispatchCompletion(onComplete)
        }
        hasRealIsolation = false
    }

    /**
     * Clears all browsing storage. This is the fallback path and also what the "clear data"
     * menu item calls in normal mode.
     *
     * Not exhaustive by construction — WebView has no single "clear everything" call, and
     * anything Chromium adds later will not be covered here.
     */
    fun wipeEverything(onComplete: () -> Unit = {}) {
        cleanupScope.launch {
            lastCleanupSucceeded = runCatching {
                BrowsingDataCleaner(appContext).clearWebsiteData(DataProfile.NORMAL)
                android.webkit.GeolocationPermissions.getInstance().clearAll()
            }.isSuccess
            dispatchCompletion(onComplete)
        }
    }

    /**
     * Per-WebView state that only the instance itself can clear. Call before destroying an
     * incognito WebView; [wipeEverything] cannot reach any of it.
     */
    fun wipeInstanceState(view: WebView) {
        view.clearHistory()
        view.clearCache(true)
        view.clearFormData()
        view.clearSslPreferences()
    }

    private fun dispatchCompletion(onComplete: () -> Unit) {
        Handler(Looper.getMainLooper()).post { runCatching { onComplete() } }
    }
}
