package com.mybrowser.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

enum class DataProfile { NORMAL, PRIVATE }
enum class ClearScope {
    CURRENT, NORMAL, ALL;
    fun targets(privateMode: Boolean, isolated: Boolean): Set<DataProfile> = when (this) {
        CURRENT -> setOf(if (privateMode && isolated) DataProfile.PRIVATE else DataProfile.NORMAL)
        NORMAL -> setOf(DataProfile.NORMAL)
        ALL -> if (privateMode && isolated) setOf(DataProfile.NORMAL, DataProfile.PRIVATE) else setOf(DataProfile.NORMAL)
    }
}
enum class ClearDataType { WEBSITE_DATA, COOKIES, HISTORY, PERMISSIONS }
enum class HistoryPeriod(val durationMs: Long?) { HOUR(3_600_000), DAY(86_400_000), ALL(null) }
data class ClearDataRequest(val scope: ClearScope, val types: Set<ClearDataType>, val historyPeriod: HistoryPeriod)

/** Select the owning store explicitly. An absent private profile is an error, never a default-store fallback. */
@SuppressLint("RequiresFeature")
class BrowsingDataCleaner(private val context: Context) {
    val supportsCompleteDeletion: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    private fun storage(profile: DataProfile): WebStorage = when (profile) {
        DataProfile.NORMAL -> WebStorage.getInstance()
        DataProfile.PRIVATE -> checkNotNull(IncognitoProfile.profileOrNull()) { "Private profile unavailable" }.webStorage
    }
    private fun cookies(profile: DataProfile): CookieManager = when (profile) {
        DataProfile.NORMAL -> CookieManager.getInstance()
        DataProfile.PRIVATE -> checkNotNull(IncognitoProfile.cookieManager()) { "Private profile unavailable" }
    }

    suspend fun clearCookies(profile: DataProfile) = withTimeout(15_000) {
        val manager = cookies(profile)
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies {
                // false means no cookies existed, not that deletion failed.
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        manager.flush()
    }

    suspend fun clearWebsiteData(profile: DataProfile) {
        val store = storage(profile)
        if (supportsCompleteDeletion) {
            withTimeout(15_000) { suspendCancellableCoroutine { continuation ->
                WebStorageCompat.deleteBrowsingData(store) { if (continuation.isActive) continuation.resume(Unit) }
            } }
        } else {
            // Legacy APIs cannot promise complete IndexedDB/service-worker erasure. UI states the limitation.
            store.deleteAllData()
            clearCookies(profile)
            val instance = WebView(context)
            try {
                if (profile == DataProfile.PRIVATE) check(IncognitoProfile.attach(instance)) { "Private profile unavailable" }
                instance.clearCache(true)
            } finally { instance.destroy() }
        }
        if (profile == DataProfile.NORMAL) WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }

    /** Modern API clears the registrable site including subdomains, not just one origin. */
    suspend fun clearSite(profile: DataProfile, origin: String) {
        val store = storage(profile)
        if (supportsCompleteDeletion) {
            withTimeout(15_000) { suspendCancellableCoroutine { continuation ->
                WebStorageCompat.deleteBrowsingDataForSite(store, origin) { if (continuation.isActive) continuation.resume(Unit) }
            } }
        } else store.deleteOrigin(origin)
    }
}
