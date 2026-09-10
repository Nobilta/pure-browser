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
import com.mybrowser.privacy.PrivacyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val certificateWarnings = com.mybrowser.security.CertificateWarnings()

    lateinit var webViewPool: WebViewPool
        private set

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

    lateinit var privacyMode: PrivacyMode
        private set

    /** Process-scoped so Activity recreation never cancels an in-flight download. */
    lateinit var downloadHandler: DownloadHandler
        private set
    lateinit var castController: com.mybrowser.dlna.CastController
        private set
    // One writer per persisted store, including during Activity recreation.
    val siteSettings by lazy { com.mybrowser.site.SiteSettingsRepository(this) }
    val readingList by lazy { com.mybrowser.reading.ReadingList(this) }

    /** Outlives every Activity; only used for work that must not be cancelled by rotation. */
    private val appScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (BuildFlags.DEBUG_STRICT_MODE) {
            enableStrictMode()
        }

        // Remote debugging from desktop Chrome via chrome://inspect. Debug builds only:
        // leaving this on in release exposes the page context of every tab to any app
        // that can reach the debugging socket.
        if (BuildFlags.DEBUG_STRICT_MODE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webViewPool = WebViewPool(applicationContext)
        privacyMode = PrivacyMode(applicationContext)
        downloadHandler = DownloadHandler(applicationContext)
        castController = com.mybrowser.dlna.CastController(applicationContext, appScope)

        // If the process died while incognito was active, the profile survived on disk with
        // its cookies intact. Deleting it before anything can attach is what makes the
        // "leaves nothing behind" promise hold across a crash rather than only a clean exit.
        IncognitoProfile.deleteStaleProfile()

        filterController = FilterController(applicationContext)
        // Parsing happens off the main thread; shouldBlock() is a no-op until it lands.
        filterSubscriptions = FilterSubscriptions(applicationContext, filterController)
        appScope.launch { filterSubscriptions.initialize() }
        FilterUpdateJob.schedule(this, filterSubscriptions.autoUpdate.value)
        userScripts = UserScriptStore(applicationContext)
        appScope.launch { userScripts.initialize() }

        // Pays Chromium's several-hundred-millisecond first-instance cost here instead
        // of on the user's first navigation. Safe before any Activity exists because the
        // pool constructs against the Application context inside a MutableContextWrapper.
        webViewPool.preWarm()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Only two levels are still live at minSdk 34. Verified against API 37's
        // android.jar: TRIM_MEMORY_RUNNING_{MODERATE,LOW,CRITICAL} and
        // TRIM_MEMORY_{MODERATE,COMPLETE} all carry @Deprecated, and the RUNNING_* ones
        // have not been delivered to apps since API 34. That leaves UI_HIDDEN (20) and
        // BACKGROUND (40), both of which mean "we are no longer in front of the user"
        // rather than "the device is short on memory".
        //
        // Consequence for the pool: there is no foreground memory-pressure signal to
        // react to any more, so staying inside a memory budget has to come from bounding
        // the pool up front (WebViewPool.maxSize) rather than from trimming on demand.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
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
