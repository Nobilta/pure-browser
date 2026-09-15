package com.mybrowser

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.StrictMode
import android.util.Log
import android.webkit.WebView
import com.mybrowser.core.WebViewPool
import com.mybrowser.download.DownloadHandler
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import com.mybrowser.filter.FilterUpdateJob
import com.mybrowser.userscript.UserScriptStore
import com.mybrowser.privacy.IncognitoProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val certificateWarnings = com.mybrowser.security.CertificateWarnings()
    val mediaSession by lazy { com.mybrowser.media.BrowserMediaSession(this) }

    val webViewPool by lazy { WebViewPool(applicationContext) }
    private var webEnginePrepared = false

    /**
     * Process-scoped: the rule set is expensive to parse and the blocked counter has to
     * survive Activity recreation, so neither can live on the Activity.
     */
    lateinit var filterController: FilterController
        private set
    lateinit var filterSubscriptions: FilterSubscriptions
        private set
    lateinit var userScripts: UserScriptStore
        private set


    /** Process-scoped so Activity recreation never cancels an in-flight download. */
    val downloadHandler by lazy { DownloadHandler(applicationContext) }
    lateinit var castController: com.mybrowser.dlna.CastController
        private set
    // One writer per persisted store, including during Activity recreation.
    val siteSettings by lazy { com.mybrowser.site.SiteSettingsRepository(this) }

    /** Outlives every Activity; only used for work that must not be cancelled by rotation. */
    private val appScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /**
     * The startup update check belongs to the process, not to an Activity. The claim makes it
     * once per process; the request itself runs in [appScope] and the offer is kept here, so a
     * configuration rebuild can neither cancel the check nor drop an offer the user has not
     * answered yet. The Activity only subscribes and displays.
     */
    private val startupUpdateClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupUpdateAnswered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupUpdateOfferState = kotlinx.coroutines.flow.MutableStateFlow<com.mybrowser.update.UpdateRepository.Release?>(null)

    /** Non-null while an unanswered update offer should be shown, for as long as the process lives. */
    val startupUpdateOffer: kotlinx.coroutines.flow.StateFlow<com.mybrowser.update.UpdateRepository.Release?> =
        startupUpdateOfferState

    /** Checks once per process when the user left the launch check on; failures stay silent. */
    fun checkForStartupUpdate() {
        val enabled = runCatching { com.mybrowser.data.BrowserPreferencesRepository(this).load().autoCheckUpdates }
            .getOrDefault(true)
        if (!enabled || !startupUpdateClaimed.compareAndSet(false, true)) return
        appScope.launch {
            val offered = try {
                com.mybrowser.update.UpdateRepository(this@App).check()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (offered != null && !startupUpdateAnswered.get()) startupUpdateOfferState.value = offered
        }
    }

    /** Remembers that the user answered the offer, so a rebuild does not show it again. */
    fun dismissStartupUpdateOffer() {
        startupUpdateAnswered.set(true)
        startupUpdateOfferState.value = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Drop the removed closed-tab archive when upgrading from an older release.
        deleteSharedPreferences("recently_closed_tabs")
        // The incognito screenshot guard is gone; drop the choice it stored.
        getSharedPreferences("browser_preferences", MODE_PRIVATE).edit().remove("protect_private_screens").apply()
        // Captures from a process that died have no surviving WebView consumer.
        java.io.File(cacheDir, "web-capture").listFiles()?.filter { it.isFile }?.forEach { it.delete() }

        if (BuildFlags.DEBUG_STRICT_MODE) {
            enableStrictMode()
        }

        castController = com.mybrowser.dlna.CastController(applicationContext, appScope)

        filterController = FilterController(applicationContext)
        // Parsing happens off the main thread; shouldBlock() is a no-op until it lands.
        filterSubscriptions = FilterSubscriptions(applicationContext, filterController)
        appScope.launch { filterSubscriptions.initialize() }
        FilterUpdateJob.schedule(this, filterSubscriptions.autoUpdate.value)
        userScripts = UserScriptStore(applicationContext)
        appScope.launch { userScripts.initialize() }

    }

    /** Main-thread only. Background download/filter jobs do not need to start Chromium. */
    fun prepareWebEngine() {
        if (webEnginePrepared) return
        if (BuildFlags.DEBUG_STRICT_MODE) WebView.setWebContentsDebuggingEnabled(true)
        // Clean an abandoned private profile before the first browser window attaches.
        IncognitoProfile.deleteStaleProfile()
        webEnginePrepared = true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Since API 34, only UI_HIDDEN and BACKGROUND are still delivered. In API 37's
        // android.jar: TRIM_MEMORY_RUNNING_{MODERATE,LOW,CRITICAL} and
        // TRIM_MEMORY_{MODERATE,COMPLETE} all carry @Deprecated, and the RUNNING_* ones
        // have not been delivered to apps since API 34. That leaves UI_HIDDEN (20) and
        // BACKGROUND (40), both of which mean "we are no longer in front of the user"
        // rather than "the device is short on memory".
        //
        // Consequence for the pool: there is no foreground memory-pressure signal to
        // react to any more, so staying inside a memory budget has to come from bounding
        // the pool up front (WebViewPool.maxSize) rather than from trimming on demand.
        if (webEnginePrepared && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Log.d(TAG, "onTrimMemory($level): backgrounded, releasing idle WebViews")
            webViewPool.trim()
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    companion object {
        private const val TAG = "App"

        lateinit var instance: App
            private set
    }
}

/**
 * Compile-time flags.
 *
 * buildConfig is disabled in the Gradle config (it generates a class we would otherwise
 * only use for this), so debug-only behaviour is keyed off the application's own
 * debuggable flag instead, read once here.
 */
object BuildFlags {
    val DEBUG_STRICT_MODE: Boolean
        get() = isDebuggable

    private val isDebuggable: Boolean by lazy {
        (App.instance.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
