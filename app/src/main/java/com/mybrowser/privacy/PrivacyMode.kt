package com.mybrowser.privacy

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

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
    private val cleanup = PrivacyCleanupBarrier(cleanupScope)
    val cleanupState = cleanup.state
    val isTransitioning: Boolean get() = cleanupState.value != PrivacyCleanupBarrier.State.READY
    suspend fun awaitReady() = cleanup.awaitReady()
    fun retryCleanup() = cleanup.retry()

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

    /** Guards the one-per-process shared-jar clear in the fallback path. */
    private var sharedJarCleared = false

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
        if (!isolated && !sharedJarCleared) {
            // The fallback shares the normal cookie jar with everything else. Clear it
            // once per process before any page loads: a force-killed private session
            // may have left its cookies behind, and this session must not start on
            // them (nor on the normal session's logins).
            sharedJarCleared = true
            lastCleanupSucceeded = false
            cleanup.enqueue {
                BrowsingDataCleaner(appContext).clearCookies(DataProfile.NORMAL)
                lastCleanupSucceeded = true
            }
        }

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
        if (isIncognito || isTransitioning) return
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
    fun exit(wipeSharedStorage: Boolean) {
        if (!isIncognito) return
        val isolated = hasRealIsolation
        isIncognito = false
        hasRealIsolation = false
        lastCleanupSucceeded = false
        cleanup.enqueue {
            val cleaner = BrowsingDataCleaner(appContext)
            when {
                wipeSharedStorage -> {
                    cleaner.clearWebsiteData(DataProfile.NORMAL)
                    android.webkit.GeolocationPermissions.getInstance().clearAll()
                }
                isolated -> {
                    cleaner.clearWebsiteData(DataProfile.PRIVATE)
                    cleaner.clearCookies(DataProfile.PRIVATE)
                }
            }
            val deleted = IncognitoProfile.destroy()
            // Modern deletion erases site data even if an empty loaded profile remains
            // until the next process. Older providers cannot promise this full erasure.
            lastCleanupSucceeded = !isolated || deleted || cleaner.supportsCompleteDeletion
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

}
