package com.mybrowser.privacy

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Storage isolation for incognito mode.
 *
 * WebView has no built-in incognito. There are exactly two ways to approximate it, and the
 * difference between them matters:
 *
 *  - **Multi-profile** (WebView 114+, [WebViewFeature.MULTI_PROFILE]): a named profile owns
 *    its own cookie jar, cache, localStorage, and IndexedDB. Deleting the profile deletes
 *    all of it. This is true isolation — a normal-mode tab cannot see incognito cookies even
 *    while both are open.
 *
 *  - **Manual clearing** (the fallback): one shared storage, wiped on exit. Isolation is only
 *    in time, not space, so an incognito session shares cookies with normal mode while it is
 *    open and the wipe at the end takes normal-mode cookies with it.
 *
 * We prefer the first and fall back to the second, telling the user which they got — a
 * privacy feature that silently degrades is worse than one that admits its limits.
 */
@SuppressLint("RequiresFeature")
object IncognitoProfile {

    private const val TAG = "IncognitoProfile"

    // Loaded profiles cannot be deleted in this process, even after their WebViews die.
    // A reserved prefix allows the next process to discover and delete crashed sessions.
    private val sessions = IncognitoSessionRegistry(
        profileNames = { ProfileStore.getInstance().allProfileNames },
        deleteProfile = { ProfileStore.getInstance().deleteProfile(it) },
    )

    /** True when the platform gives us real per-profile isolation. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Attaches a fresh incognito profile to [webView].
     *
     * Must be called before the first [WebView.loadUrl]; WebView rejects a profile change
     * once the instance has state. Returns true when isolation is real.
     */
    fun attach(webView: WebView): Boolean {
        if (!isSupported) return false
        val name = sessions.activeName ?: return false
        return try {
            WebViewCompat.setProfile(webView, name)
            true
        } catch (e: IllegalStateException) {
            // Thrown if the WebView has already been used, or the profile is still attached
            // to a live WebView elsewhere.
            Log.w(TAG, "could not attach incognito profile", e)
            false
        }
    }

    /** Removes a previous crashed session before a new incognito profile is attached. */
    fun prepare() {
        if (isSupported) sessions.begin()
    }

    /**
     * Retires this session and requests deletion after its WebViews have been destroyed.
     *
     * A loaded profile can remain undeletable until process restart. PrivacyMode clears its
     * browsing data first, and future sessions always use a different name.
     */
    fun destroy(): Boolean {
        if (!isSupported) return true
        return sessions.end()
    }

    /**
     * Removes a profile left behind by a crash or a force-stop. Safe to call at startup.
     */
    fun deleteStaleProfile() {
        if (!isSupported) return
        if (!sessions.cleanupStale()) Log.w(TAG, "Some private profiles await a later process cleanup")
    }

    /** The incognito profile's cookie manager, or null when unsupported. */
    fun cookieManager(): android.webkit.CookieManager? {
        if (!isSupported) return null
        val name = sessions.activeName ?: return null
        return try {
            ProfileStore.getInstance().getProfile(name)?.cookieManager
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** The live profile, for callers needing its web storage. Null when unsupported. */
    fun profileOrNull(): Profile? {
        if (!isSupported) return null
        val name = sessions.activeName ?: return null
        return try {
            ProfileStore.getInstance().getProfile(name)
        } catch (e: IllegalStateException) {
            null
        }
    }
}
