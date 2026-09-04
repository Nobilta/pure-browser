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

    /**
     * Profile name. Fixed rather than random: a crash mid-session would otherwise orphan a
     * profile on disk with no name left to delete it by. A fixed name means the next launch
     * can always clean up. See [deleteStaleProfile].
     */
    private const val PROFILE_NAME = "incognito"

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
        return try {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
            WebViewCompat.setProfile(webView, PROFILE_NAME)
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
        if (isSupported) deleteStaleProfile()
    }

    /**
     * Deletes the incognito profile and everything it stored.
     *
     * Call only after every WebView using it has been destroyed — WebView refuses to delete
     * a profile that is still attached, and that refusal is how a session's data survives a
     * sloppy teardown.
     */
    fun destroy() {
        if (!isSupported) return
        try {
            ProfileStore.getInstance().deleteProfile(PROFILE_NAME)
        } catch (e: IllegalStateException) {
            // Still in use. The next launch's deleteStaleProfile catches it.
            Log.w(TAG, "incognito profile still in use, deferring deletion", e)
        } catch (e: IllegalArgumentException) {
            // Already gone, or never created. Nothing to do.
        }
    }

    /**
     * Removes a profile left behind by a crash or a force-stop. Safe to call at startup.
     */
    fun deleteStaleProfile() {
        if (!isSupported) return
        try {
            if (PROFILE_NAME in ProfileStore.getInstance().allProfileNames) {
                ProfileStore.getInstance().deleteProfile(PROFILE_NAME)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stale incognito profile in use", e)
        } catch (e: IllegalArgumentException) {
            // Not present.
        }
    }

    /** The incognito profile's cookie manager, or null when unsupported. */
    fun cookieManager(): android.webkit.CookieManager? {
        if (!isSupported) return null
        return try {
            ProfileStore.getInstance().getProfile(PROFILE_NAME)?.cookieManager
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** The live profile, for callers needing its web storage. Null when unsupported. */
    fun profileOrNull(): Profile? {
        if (!isSupported) return null
        return try {
            ProfileStore.getInstance().getProfile(PROFILE_NAME)
        } catch (e: IllegalStateException) {
            null
        }
    }
}
