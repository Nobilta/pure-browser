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

    override fun onCreate() {
        super.onCreate()
        instance = this
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
