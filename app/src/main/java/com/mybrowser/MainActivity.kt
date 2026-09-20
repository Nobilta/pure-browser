package com.mybrowser

import com.mybrowser.R
import com.mybrowser.site.*
import com.mybrowser.data.BookmarkDocuments
import com.mybrowser.ui.library.BookmarkImportDialog
import kotlin.coroutines.resume
import com.mybrowser.ui.settings.SiteSettingsSheet
import com.mybrowser.ui.settings.ManagedSitesSheet
import com.mybrowser.ui.settings.WebsitePermissionDialog
import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mybrowser.core.BrowserChromeClient
import com.mybrowser.core.BrowserWebViewClient
import com.mybrowser.core.ConsoleLogLevel
import com.mybrowser.core.ConsoleLogStore
import com.mybrowser.core.ExternalIntentHandler
import com.mybrowser.core.ExternalNavigationGate
import com.mybrowser.core.RendererRecovery
import com.mybrowser.core.PageFailure
import com.mybrowser.core.PageFailureKind
import com.mybrowser.ui.shell.ExternalAppDialog
import com.mybrowser.ui.shell.BackHistoryDialog
import com.mybrowser.ui.shell.BackHistoryEntry
import com.mybrowser.core.NetworkLogStore
import com.mybrowser.core.NavigationPolicy
import com.mybrowser.core.NavigationTarget
import com.mybrowser.core.UrlUtils
import com.mybrowser.core.classifyResourceType
import com.mybrowser.core.WebViewPool
import com.mybrowser.core.WebViewConfig
import com.mybrowser.core.DetachedWebViewClient
import com.mybrowser.core.DefaultBrowser
import com.mybrowser.core.PageContextTarget
import com.mybrowser.core.PageContextMenuController
import com.mybrowser.dlna.CastController
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import com.mybrowser.userscript.UserScriptStore
import com.mybrowser.userscript.UserScriptRuntime
import com.mybrowser.media.MediaCandidateStore
import com.mybrowser.media.MediaPlaybackTracker
import com.mybrowser.media.MediaTrackerRegistry
import com.mybrowser.media.FullscreenVideoView
import com.mybrowser.data.BrowserPreferences
import com.mybrowser.data.BrowserPreferencesRepository
import com.mybrowser.core.PlaybackSpeed
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchEngineManager
import com.mybrowser.search.UrlOrSearch
import com.mybrowser.media.MediaSniffer
import com.mybrowser.privacy.PrivacyMode
import com.mybrowser.privacy.BrowsingDataCleaner
import com.mybrowser.privacy.ClearDataRequest
import com.mybrowser.privacy.ClearDataType
import com.mybrowser.privacy.ClearScope
import com.mybrowser.privacy.DataProfile
import com.mybrowser.ui.shell.ClearBrowsingDataDialog
import com.mybrowser.home.HomeRepository
import com.mybrowser.home.HomeShortcut
import com.mybrowser.home.ShortcutIconChange
import com.mybrowser.home.ShortcutSaveResult
import com.mybrowser.home.HomepageMode
import com.mybrowser.security.CertificateDetails
import com.mybrowser.security.SecurityChecker
import com.mybrowser.tabs.TabManager
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import com.mybrowser.download.DownloadHandler
import com.mybrowser.download.DownloadSettings
import com.mybrowser.download.DownloadSettingsRepository
import com.mybrowser.data.Bookmark
import com.mybrowser.data.HistoryEntry
import com.mybrowser.ui.shell.BrowserScreen
import com.mybrowser.ui.shell.BrowserState
import com.mybrowser.ui.shell.BrowserSheetNavigation
import com.mybrowser.ui.shell.BrowserSheetNavigation.Destination as Sheet
import com.mybrowser.ui.shell.BrowserSheetHost
import com.mybrowser.ui.shell.BrowserExitConfirmation
import com.mybrowser.ui.library.BookmarksSheet
import com.mybrowser.ui.library.HistorySheet
import com.mybrowser.ui.player.CastSheet
import com.mybrowser.ui.menu.MenuSheet
import com.mybrowser.ui.menu.TabsSheet
import com.mybrowser.ui.download.DownloadsSheet
import com.mybrowser.ui.settings.SettingsSheet
import com.mybrowser.ui.settings.FilterSettingsSheet
import com.mybrowser.ui.settings.UserScriptsSheet
import com.mybrowser.ui.shell.BookmarkEditDialog
import com.mybrowser.ui.library.LibraryPager
import com.mybrowser.ui.shell.PageContextSheet
import com.mybrowser.ui.theme.MyBrowserTheme
import com.mybrowser.ui.shell.Dialogs
import java.io.ByteArrayInputStream
import java.util.WeakHashMap
import com.mybrowser.ui.devtools.DeveloperTools
import com.mybrowser.ui.devtools.FilterExplanationDialog
import com.mybrowser.ui.library.BookmarkLibrary
import com.mybrowser.ui.qr.QrScannerSheet
import com.mybrowser.ui.shell.Omnibar
import com.mybrowser.ui.shell.SSLErrorDialog
import com.mybrowser.ui.shell.SecurityInfoDialog

/**
 * Hosts the browser.
 *
 * The division of labour after the Compose migration: this class owns everything
 * imperative — the pooled WebView, its clients, fullscreen video, intents, lifecycle —
 * and pushes what the UI draws into [BrowserState]. Compose reads that state and never
 * touches the WebView except to attach it.
 *
 * [webView] is snapshot state rather than a plain field on purpose. When the renderer
 * dies and the pool hands over a replacement, assigning it here is the entire fix: the
 * assignment recomposes [BrowserScreen], whose holder reconciliation swaps the instance
 * in. No view manipulation at this level.
 */
class MainActivity : ComponentActivity(),
    BrowserWebViewClient.Listener,
    BrowserChromeClient.Listener,
    com.mybrowser.media.BrowserMediaSession.Owner {

    private lateinit var pool: WebViewPool
    private lateinit var sessionState: com.mybrowser.tabs.BrowserSessionState
    private val dialogs = Dialogs()
    private val rendererRecovery = RendererRecovery()
    private val externalGate = ExternalNavigationGate()
    private data class ExternalPrompt(val url: String, val origin: String?, val epoch: Long)
    private var externalPrompt by mutableStateOf<ExternalPrompt?>(null)
    private var backHistory by mutableStateOf<List<BackHistoryEntry>?>(null)
    private var backHistoryEpoch = 0L
    private lateinit var filter: FilterController
    private lateinit var customFilter: FilterSubscriptions
    private lateinit var userScripts: UserScriptStore
    private val scriptRuntimes = WeakHashMap<WebView, UserScriptRuntime>()
    private var scriptNavigationJob: Job? = null
    private lateinit var privacy: PrivacyMode
    private val dataCleaner by lazy { BrowsingDataCleaner(this) }
    private var showClearData by mutableStateOf(false)
    private var clearingData by mutableStateOf(false)

    // Two separate tab managers: one for normal mode, one for incognito.
    // tabManager points to whichever is active.
    private lateinit var normalTabManager: TabManager
    private lateinit var incognitoTabManager: TabManager
    private lateinit var tabManager: TabManager

    // Bookmarks and history managers
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var historyManager: HistoryManager
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var downloadFileOpener: com.mybrowser.download.DownloadFileOpener
    /** Download presentation: results become strings here, and a page starts transfers here. */
    private val downloadPresenter by lazy {
        com.mybrowser.download.DownloadPresenter(this, downloadHandler, downloadFileOpener)
    }
    /** Confirmation gate for every download request; survives rotation with the session. */
    private val downloadRequests get() = sessionState.downloadRequests
    private var downloadFocusId by mutableStateOf<Long?>(null)
    private lateinit var downloadSettingsRepository: DownloadSettingsRepository
    private var downloadSettings by mutableStateOf(DownloadSettings())
    private lateinit var preferencesRepository: BrowserPreferencesRepository
    private var browserPreferences by mutableStateOf(BrowserPreferences())
    private var showFilterSettings by mutableStateOf(false)
    private var showUserScripts by mutableStateOf(false)
    private lateinit var normalSites: SiteSettingsRepository
    private var privateSites: SiteSettingsRepository?
        get() = sessionState.privateSites
        set(value) { sessionState.privateSites = value }
    private val activeSites get() = if (privacy.isIncognito) privateSites ?: normalSites else normalSites
    private var showSiteOrigin by mutableStateOf<String?>(null)
    private var showManagedSites by mutableStateOf(false)
    private var siteSettingsBusy by mutableStateOf(false)
    private var permissionEpoch by androidx.compose.runtime.mutableLongStateOf(0L)
    private val websitePermissions by lazy {
        WebsitePermissions(lifecycleScope,
            isGranted = { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED },
            launchRuntime = { runtimePermissionLauncher.launch(it) },
            onSaveError = { toast(getString(R.string.site_permission_not_remembered)) })
    }
    private var scriptImportUrl by mutableStateOf<String?>(null)

    private lateinit var bookmarkDocuments: BookmarkDocuments
    private lateinit var bookmarkLibrary: com.mybrowser.ui.library.BookmarkLibrary
    private lateinit var historyLibrary: LibraryPager<HistoryEntry>
    private var currentPageBookmarked by mutableStateOf(false)

    private val state = BrowserState()

    /** Video candidates for the current page; cleared on every main-frame navigation. */
    private val media = MediaCandidateStore()
    private val mediaTrackers = MediaTrackerRegistry()
    private var mediaProbeJob: Job? = null
    private var hasVideo by mutableStateOf(false)
    private val networkLogs = NetworkLogStore()
    private val consoleLogs = ConsoleLogStore()
    private val cast: CastController get() = (application as App).castController

    private val sheetNavigation = BrowserSheetNavigation()
    private val sheet get() = sheetNavigation.current?.destination
    private val exitConfirmation = BrowserExitConfirmation(EXIT_CONFIRM_WINDOW_MS)

    /** Non-null while the add-bookmark editor is visible. */
    private var bookmarkDraft: BookmarkDraft? by mutableStateOf(null)

    private data class BookmarkDraft(val title: String, val url: String, val id: Long? = null)

    private var pageContextTarget by mutableStateOf<PageContextTarget?>(null)
    private val pageContextMenu = PageContextMenuController({ it === webViewOrNull }) { target ->
        if (fullscreenView == null) {
            sheetNavigation.clear()
            pageContextTarget = target
        }
    }

    // Homepage settings and user-created navigation tiles.
    private lateinit var homeRepository: HomeRepository
    private var homepageMode by mutableStateOf(HomepageMode.NAVIGATION)
    private var homeUrl by mutableStateOf("https://www.bing.com")
    private var homeShortcuts by mutableStateOf<List<HomeShortcut>>(emptyList())
    private var restoreLastSession by mutableStateOf(false)
    private var isDefaultBrowser by mutableStateOf(false)
    private lateinit var defaultBrowserLauncher: ActivityResultLauncher<Intent>

    // Search engine setting
    private lateinit var searchEngineManager: SearchEngineManager
    private var searchEngine by mutableStateOf(SearchEngine.BAIDU)
    private var availableSearchEngines by mutableStateOf<List<SearchEngine>>(emptyList())

    /**
     * Nullable behind the scenes because a snapshot state needs an initial value and the
     * real instance cannot exist until the pool does, but never observed as null: the
     * assignment in onCreate happens before setContent, so nothing can read it early.
     * The accessor asserts that rather than letting a null leak into the UI.
     */
    private var webViewOrNull: WebView? by mutableStateOf(null)
    private var clearHistoryOnNextFinish = false
    private var readyWebViewTabId: String? = null
    private var viewOwnerId: String? = null
    private data class ParkedPage(val view: WebView, val settings: SiteSettings, val filtering: Boolean)
    private class PopupPage(val tabId: String, val view: WebView, document: WorkerDocument) {
        @Volatile var alive = true
        @Volatile var document = document
        var loading = true
        var failure: PageFailure? = null
        var certificateError = false
        var clearHistory = false
    }
    private val popupPages = com.mybrowser.tabs.PopupSessionStore<PopupPage>()
    private val pendingPopupTransfers = mutableMapOf<WebView, AutoCloseable>()

    private fun disposePopupPage(page: PopupPage) {
        page.alive = false
        removeMediaPlaybackTracker(page.view)
        pool.discard(page.view)
    }

    /** HTTP and document state continue while only foreground UI/media ownership is suspended. */
    private fun backgroundPopupPage(page: PopupPage) {
        val view = page.view
        (view as? com.mybrowser.core.BrowserWebView)?.keepMediaOnWindowHidden = false
        mediaTrackers.trackerOf(view)?.let { it.pauseAll(); it.setSuspended(true) }
        view.webViewClient = com.mybrowser.core.PopupWebViewClient(object : com.mybrowser.core.PopupWebViewClient.Listener {
            override fun isAlive() = page.alive
            override fun onNavigation(url: String) { updatePopupDocument(page, url) }
            override fun onStarted(url: String) {
                downloadRequests.startDocument(page.tabId)
                page.loading = true
                page.failure = null
                page.certificateError = false
                updatePopupDocument(page, url)
            }
            override fun onHistory(url: String) { updatePopupDocument(page, url) }
            override fun onFinished(url: String) {
                if (url != view.url || url != page.document.url) return
                page.loading = false
                if (page.clearHistory) { view.clearHistory(); page.clearHistory = false }
                onBackgroundTitleChanged(view, view.title)
                val settings = activeSites.get(url)
                if (settings.desktop) WebViewConfig.applyDesktopViewport(view, settings.desktopWidth)
                if (!privacy.isIncognito && page.failure == null) addToHistory(url, view.title ?: url)
                scriptRuntimes[view]?.onPageFinished(url)
            }
            override fun onError(url: String, code: Int, description: String) {
                if (url != page.document.url) return
                page.loading = false
                page.failure = PageFailure(url, PageFailureKind.NETWORK, "$code: $description")
                page.certificateError = code == android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
            }
            override fun onIntercept(request: WebResourceRequest): WebResourceResponse? {
                val document = page.document
                return if (filter.shouldBlock(request, document.url, document.filtering,
                        classifyResourceType(request), countForPage = false)) BLOCKED_RESPONSE else null
            }
            override fun onGone(view: WebView, crashed: Boolean) = onRenderProcessGone(view, crashed)
        })
        // Chrome callbacks retain their source view; foreground-only prompts stay guarded.
        view.webChromeClient = BrowserChromeClient(this, view)
        view.onResume()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun updatePopupDocument(page: PopupPage, url: String) {
        if (!page.alive) return
        page.document = documentFor(url)
        WebViewConfig.applySiteSettings(page.view, activeSites.get(url))
        tabManager.tabs.firstOrNull { it.id == page.tabId }?.let {
            it.url = url
            it.savedState = null // Never restore a pre-login bundle over the live document.
            tabManager.notifyChanged()
        }
    }

    private fun stopDepartingPage() {
        if (popupPages[viewOwnerId]?.view !== webViewOrNull) webViewOrNull?.stopLoading()
    }

    private fun suspendPage(view: WebView) {
        (view as? com.mybrowser.core.BrowserWebView)?.keepMediaOnWindowHidden = false
        mediaTrackers.trackerOf(view)?.setSuspended(true)
        view.onPause()
        view.webViewClient = com.mybrowser.tabs.ParkedWebViewClient { gone -> onRenderProcessGone(gone, true) }
        (view.parent as? ViewGroup)?.removeView(view)
    }
    /**
     * Parked pages, one per tab the user actually opened. Retention follows the tab count
     * rather than a fixed slot count, so memory is bounded by eviction instead: the oldest
     * page goes first when the allocator runs out or the system reports pressure.
     */
    private val recentViews by lazy {
        com.mybrowser.tabs.RecentTabStore<ParkedPage> { page ->
            removeMediaPlaybackTracker(page.view)
            pool.discard(page.view)
        }
    }

    private fun clearResidentTabs() {
        popupPages.clear().forEach { page ->
            page.alive = false
            if (page.view !== webViewOrNull) disposePopupPage(page)
        }
        recentViews.clear()
    }
    private fun pruneResidentTabs() {
        downloadRequests.retainTabs(tabManager.tabs.map { it.id }.toSet())
        recentViews.retain(normalTabManager.tabs.map { it.id }.toSet())
        popupPages.retain(tabManager.tabs.map { it.id }.toSet()).forEach(::disposePopupPage)
    }

    /** Frees the least recently parked page. False when there was nothing left to free. */
    private fun evictOldestParkedPage(): Boolean = recentViews.evictOldest()

    /**
     * Creates an instance, evicting parked pages oldest-first if the allocator is out of
     * memory. Unbounded retention needs this backstop: without it, opening one page too many
     * would crash instead of dropping the page the user is least likely to return to.
     *
     * Configuration runs after the pool has taken ownership, so a failure there has to hand the
     * instance back: it would otherwise sit in the pool attached to nothing and leak exactly the
     * memory this retry is trying to free.
     */
    private fun acquireFreshPage(): WebView = com.mybrowser.core.acquireWithMemoryRecovery(
        create = { pool.acquireFresh() },
        configure = ::configure,
        discard = ::discardFailedPage,
        evict = ::evictOldestParkedPage,
    )

    /** Releases a half-configured instance; its trackers and script runtime die with it. */
    private fun discardFailedPage(view: WebView) {
        removeMediaPlaybackTracker(view)
        scriptRuntimes.remove(view)?.close()
        pool.discard(view)
    }

    /**
     * A worker-thread-safe copy for shouldInterceptRequest. Reading Compose snapshot state
     * from Chromium's network thread is unnecessary coupling and can observe a stale snapshot;
     * the volatile value is updated alongside the main-frame callbacks instead.
     */
    private data class WorkerDocument(val url: String = ABOUT_BLANK, val filtering: Boolean = true)
    @Volatile private var workerDocument = WorkerDocument()
    private var temporaryFilterOrigins by mutableStateOf<Set<String>>(emptySet())
    private var filterExplanation by mutableStateOf<com.mybrowser.core.NetworkRequestLog?>(null)
    private fun documentFor(url: String) = WorkerDocument(url,
        activeSites.get(url).filtering && SiteOrigin.of(url) !in temporaryFilterOrigins)

    private val webView: WebView
        get() = checkNotNull(webViewOrNull) { "WebView read before onCreate acquired it" }

    /** Fullscreen video state. */
    private var fullscreenView by mutableStateOf<FullscreenVideoView?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pictureInPictureSession = false
    private lateinit var pipController: com.mybrowser.media.PictureInPictureController
    private val systemMedia get() = (application as App).mediaSession
    private var rememberedVideo: String? = null

    // Security dialog state
    private var currentCertificateError by mutableStateOf(false)
    private val certificateWarnings get() = (application as App).certificateWarnings
    private val hasCertificateWarning get() = currentCertificateError || certificateWarnings.contains(state.currentUrl)
    private var showSecurityDialog by mutableStateOf(false)
    private var securityCertificate by mutableStateOf<CertificateDetails?>(null)
    private var showSSLErrorDialog by mutableStateOf(false)
    private var pendingSSLError: Pair<SslErrorHandler, String>? = null

    // WebView callbacks that outlive a single stack frame. They are cleared on replacement
    // and teardown so a page cannot receive a result after its tab has gone away.
    private lateinit var fileChooser: com.mybrowser.core.WebFileChooser
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var runtimePermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var downloadDirectoryLauncher: ActivityResultLauncher<Uri?>
    private lateinit var settingsExportLauncher: ActivityResultLauncher<String>
    private lateinit var settingsImportLauncher: ActivityResultLauncher<Array<String>>

    /** Parsed backup + preview while the user decides whether to apply an import. */
    private var importCandidate by mutableStateOf<Pair<com.mybrowser.backup.SettingsBackup, com.mybrowser.backup.ImportPreview>?>(
        null
    )

    // Developer tools state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerActivityLaunchers()

        // Edge-to-edge is enforced at targetSdk 35+; opting out is deprecated, so we
        // handle insets rather than fight them. Compose applies them per-bar inside
        // BrowserScreen via WindowInsets.safeDrawing.
        enableEdgeToEdge()

        val app = application as App
        sessionState = androidx.lifecycle.ViewModelProvider(this)[com.mybrowser.tabs.BrowserSessionState::class.java]
        app.prepareWebEngine()
        pool = WebViewPool(this)
        filter = app.filterController
        customFilter = app.filterSubscriptions
        userScripts = app.userScripts
        privacy = sessionState.privacy
        normalSites = app.siteSettings
        preferencesRepository = BrowserPreferencesRepository(this)
        browserPreferences = loadBrowserPreferences()
        downloadNotificationsEnabled = com.mybrowser.download.DownloadNotificationGuide.isEnabled(this)
        // A persisted incognito wish applies only on a fresh process. An Activity
        // recreation keeps its live session; a cold start always opens a brand-new
        // incognito session — private tabs, cookies and temporary grants are never
        // restored, and the normal tab metadata stays available for the next normal start.
        if (!sessionState.initialized && browserPreferences.incognitoEnabled) {
            if (privacy.isTransitioning) sessionState.pendingPrivateStart = true
            else {
                privateSites = normalSites.privateSession()
                sessionState.beginPrivateSession()
            }
        }

        // Initialize search engine manager
        searchEngineManager = SearchEngineManager(this)
        searchEngine = searchEngineManager.getCurrentEngine()
        availableSearchEngines = searchEngineManager.getAvailableEngines()

        homeRepository = HomeRepository(this)
        homeRepository.loadSettings().let { settings ->
            homepageMode = settings.mode
            homeUrl = settings.fixedUrl
            restoreLastSession = settings.restoreLastSession
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val shortcuts = homeRepository.loadShortcuts()
            withContext(Dispatchers.Main) { homeShortcuts = shortcuts }
        }

        // Initialize both tab managers
        normalTabManager = sessionState.normalTabs
        // A recreated Activity in this process retains its tabs. A fresh process follows
        // the startup preference even when Android retained the task's old saved state.
        val sessionSnapshot = savedInstanceState
            ?.takeIf { it.getString(STATE_PROCESS_SESSION) == PROCESS_SESSION }
            ?.getBundle(STATE_NORMAL_TABS)
        if (savedInstanceState?.getString(STATE_PROCESS_SESSION) == PROCESS_SESSION) {
            val names = savedInstanceState.getStringArrayList(STATE_SHEET_ROUTES).orEmpty()
            val keys = savedInstanceState.getLongArray(STATE_SHEET_KEYS) ?: longArrayOf()
            val routes = if (names.size == keys.size) names.mapIndexedNotNull { index, name ->
                runCatching { BrowserSheetNavigation.Route(keys[index], Sheet.valueOf(name)) }.getOrNull()
            } else emptyList()
            sheetNavigation.restore(routes.takeIf { it.size == names.size }.orEmpty())
            showFilterSettings = savedInstanceState.getBoolean(STATE_FILTER_SETTINGS_OPEN)
            showUserScripts = savedInstanceState.getBoolean(STATE_USER_SCRIPTS_OPEN)
            showManagedSites = savedInstanceState.getBoolean(STATE_MANAGED_SITES_OPEN)
            showSiteOrigin = savedInstanceState.getString(STATE_SITE_ORIGIN)
        }
        if (!sessionState.initialized && sessionSnapshot != null) {
            normalTabManager.restoreMetadata(sessionSnapshot)
        } else if (!sessionState.initialized && restoreLastSession) {
            normalTabManager.restoreMetadata(this, NORMAL_TABS_PREFS)
        } else if (!sessionState.initialized) {
            normalTabManager.clearMetadata(this, NORMAL_TABS_PREFS)
        }
        incognitoTabManager = sessionState.privateTabs
        tabManager = if (privacy.isIncognito) incognitoTabManager else normalTabManager
        sessionState.initialized = true

        // Initialize bookmarks and history managers
        bookmarkManager = BookmarkManager(this)
        historyManager = HistoryManager(this)
        bookmarkLibrary = com.mybrowser.ui.library.BookmarkLibrary(lifecycleScope, bookmarkManager) {
            toast(getString(R.string.library_update_failed))
        }
        historyLibrary = LibraryPager(lifecycleScope, HistoryEntry::id) { query, limit, offset ->
            if (query.isBlank()) historyManager.getAllHistory(limit, offset)
            else historyManager.searchHistory(query, limit, offset)
        }
        downloadSettingsRepository = DownloadSettingsRepository(this)
        downloadSettings = downloadSettingsRepository.load()
        // The process owns the check and the offer; this Activity only displays it (see App).
        (application as App).checkForStartupUpdate()
        pipController = com.mybrowser.media.PictureInPictureController(this,
            { webViewOrNull?.let(mediaTrackers::trackerOf) }, { !privacy.isIncognito }, { fullscreenView },
            { browserPreferences.video.automaticPip })
        downloadHandler = app.downloadHandler

        webViewOrNull = acquireFreshPage()
        // A VIEW/SEND intent is an explicit destination. Restoring the previous tab first
        // would briefly start its WebView and let late subresource callbacks pollute the
        // media candidates for the requested URL. Restore only for a normal launcher start;
        // handleIntent() performs the explicit navigation below after Compose is ready.
        if (savedInstanceState != null || intentNavigationText(intent) == null) {
            loadCurrentTab()
        } else {
            media.clear()
            webViewOrNull?.let { mediaTrackers.trackerOf(it)?.reset() }
        }

        // Register the fallback before Compose, including when restoring open settings.
        setUpBackHandling()
        setContent {
            MyBrowserTheme(themeMode = browserPreferences.theme) {
                val lightSystemBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
                dialogs.Render()
                val cleanupState by privacy.cleanupState.collectAsState()
                if (cleanupState == com.mybrowser.privacy.PrivacyCleanupBarrier.State.FAILED) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {},
                        title = { Text(getString(R.string.privacy_cleanup_title)) },
                        text = { Text(getString(R.string.privacy_cleanup_failed)) },
                        confirmButton = { TextButton(onClick = privacy::retryCleanup) { Text(getString(R.string.ui_retry)) } },
                        dismissButton = { TextButton(onClick = { finish() }) { Text(getString(R.string.ui_exit_browser)) } },
                    )
                }
                val startupApp = application as App
                val startupOffer by startupApp.startupUpdateOffer.collectAsState()
                startupOffer?.takeIf { !privacy.isIncognito }?.let { offered ->
                    com.mybrowser.ui.settings.UpdatePanel(
                        onDismiss = { startupApp.dismissStartupUpdateOffer() },
                        offered = offered,
                        installAfterDownload = true,
                    )
                }
                filterExplanation?.let { com.mybrowser.ui.devtools.FilterExplanationDialog(it, filter) { filterExplanation = null } }
                if (showClearData) ClearBrowsingDataDialog(privacy.isIncognito, privacy.hasRealIsolation,
                    dataCleaner.supportsCompleteDeletion, clearingData, ::clearBrowsingData, { showClearData = false })
                // The single download confirmation; the request behind it lives in memory only.
                downloadRequests.pending.collectAsState().value?.let { confirmation ->
                    com.mybrowser.ui.download.DownloadConfirmDialog(
                        request = confirmation.request,
                        isNewCopy = confirmation.isNewCopy,
                        destinationLabel = downloadSettings.displayDestinationLabel(resources),
                        onConfirm = ::confirmDownloadRequest,
                        onCancel = downloadRequests::rejectPending,
                    )
                }
                // Import preview: nothing is written until the user applies.
                importCandidate?.let { (backup, summary) ->
                    com.mybrowser.ui.settings.ImportPreviewDialog(
                        backup = backup,
                        summary = summary,
                        onApply = ::applyImportedSettings,
                        onCancel = { importCandidate = null },
                    )
                }
                externalPrompt?.let { prompt ->
                    ExternalAppDialog(prompt.origin.orEmpty(), UrlUtils.schemeOf(prompt.url), privacy.isIncognito) { allow, remember ->
                        externalPrompt = null
                        val repository = activeSites
                        lifecycleScope.launch {
                            if (prompt.epoch != permissionEpoch) return@launch
                            if (remember && prompt.origin != null) runCatching {
                                repository.update(prompt.origin) { it.copy(externalApps = if (allow) SitePermission.ALLOW else SitePermission.BLOCK) }
                            }.onFailure { toast(getString(R.string.site_save_failed)) }
                            if (allow && prompt.epoch == permissionEpoch) launchExternalUrl(prompt.url)
                        }
                    }
                }
                backHistory?.let { entries ->
                    BackHistoryDialog(entries, onSelect = { offset ->
                        backHistory = null
                        if (backHistoryEpoch == permissionEpoch) navigateHistory(offset)
                    }, onExitSite = { backHistory = null; webView.clearHistory(); goHome() }, onDismiss = { backHistory = null })
                }
                val mediaSnapshot by media.state.collectAsState()
                val activeSheetEntry = sheetNavigation.current
                // The window policy follows exactly the state that decides it; the
                // effect runs on a change of any of these, never per recomposition.
                LaunchedEffect(
                    browserPreferences.browserFullscreenEnabled,
                    state.isChromeRevealed,
                    state.isOmnibarFocused,
                    state.isFindBarVisible,
                    fullscreenView,
                    activeSheetEntry,
                ) { applyWindowInsetsPolicy() }
                val activeSheet = activeSheetEntry?.destination
                androidx.compose.runtime.DisposableEffect(activeSheet) {
                    cast.setVisible(activeSheet == Sheet.CAST)
                    onDispose {
                        networkLogs.setVisible(false)
                        consoleLogs.setVisible(false)
                        if (activeSheet == Sheet.CAST) { cast.setVisible(false); cast.cancel() }
                    }
                }
                BrowserScreen(
                    state = state,
                    webView = webView,
                    onNavigate = ::navigate,
                    onBack = { navigateBackAcrossTabs() },
                    onBackLongPress = ::showBackHistory,
                    onForward = { navigateHistory(1) },
                    onHome = ::goHome,
                    onReloadOrStop = {
                        if (state.isLoading) {
                            webView.stopLoading()
                            state.onLoadStopped()
                        } else reloadPage()
                    },
                    onMenu = { if (sheet == null) openSheet(Sheet.MENU) },
                    onScanQr = { openSheet(Sheet.QR_SCANNER) },
                    onTabs = {
                        tabManager.captureCurrentThumbnail(webView, 200, 300)
                        openSheet(Sheet.TABS)
                    },
                    tabCount = tabManager.count,
                    isVideoFullscreen = fullscreenView != null,
                    mediaCount = mediaSnapshot.count,
                    onCast = {
                        mediaTrackers.trackerOf(webView)?.probe()
                        openSheet(Sheet.CAST)
                        cast.search()
                    },
                    showHomeDashboard = homepageMode == HomepageMode.NAVIGATION &&
                        state.currentUrl == ABOUT_BLANK,
                    homeShortcuts = homeShortcuts,
                    onOpenHomeShortcut = { shortcut -> navigate(shortcut.url) },
                    onSaveHomeShortcut = ::saveHomeShortcut,
                    onRemoveHomeShortcut = ::removeHomeShortcut,
                    onFindQueryChange = { query ->
                        if (query.isEmpty()) {
                            webView.clearMatches()
                        } else {
                            webView.findAllAsync(query)
                        }
                    },
                    onFindNext = { webView.findNext(true) },
                    onFindPrevious = { webView.findNext(false) },
                    onFindClose = {
                        webView.clearMatches()
                        state.hideFindBar()
                    },
                    isIncognito = privacy.isIncognito,
                    hasPrivateIsolation = privacy.hasRealIsolation,
                    onRetryPage = ::retryFailedPage,
                    onNewTab = { createNewTab() },
                    bottomAddressBar = browserPreferences.bottomAddressBar,
                    swipeTabs = browserPreferences.swipeTabs,
                    isBrowserFullscreen = browserPreferences.browserFullscreenEnabled,
                    onRevealChrome = state::revealChrome,
                    onContinueFullscreen = { applyWindowInsetsPolicy() },
                    onExitBrowserFullscreen = { setBrowserFullscreenEnabled(false) },
                    onSwitchTab = { delta ->
                        val next = tabManager.currentIndex + delta
                        if (next in tabManager.tabs.indices) switchToTab(next)
                    },
                    onSecurityClick = {
                        securityCertificate = SecurityChecker.certificateDetails(webView.certificate)
                        showSecurityDialog = true
                    },
                    bookmarkManager = bookmarkManager,
                    historyManager = historyManager,
                    searchEngine = searchEngine,
                    onlineSuggestionsEnabled = browserPreferences.searchSuggestionsEnabled &&
                        (!privacy.isIncognito || browserPreferences.privateSearchSuggestionsEnabled),
                    suggestionProvider = (application as App).searchSuggestionProvider,
                    onOmnibarSearch = { query -> navigate(searchEngine.buildSearchUrl(query)) },
                    tabRevision = tabManager.revision,
                    certificateError = hasCertificateWarning,
                )

                BrowserSheetHost(sheetNavigation) { entry ->
                    when (entry.destination) {
                        Sheet.MENU -> MenuSheet(
                            isIncognito = privacy.isIncognito,
                            isFilterEnabled = filter.enabled.collectAsState().value,
                            blockedCount = filter.blockedCount,
                            mediaCount = mediaSnapshot.count,
                            hasCastSession = cast.state.collectAsState().value.connected != null,
                            hasVideo = hasVideo,
                            isDesktopMode = state.isDesktopMode,
                            isBrowserFullscreen = browserPreferences.browserFullscreenEnabled,
                            isCurrentPageBookmarked = currentPageBookmarked,
                            canUsePageActions = UrlUtils.isHttpUrl(state.currentUrl),
                            onOpenSiteSettings = {
                                SiteOrigin.of(state.currentUrl)?.let { origin ->
                                    if (sheetNavigation.push(entry, Sheet.SITE_SETTINGS)) showSiteOrigin = origin
                                }
                            },
                            onPinWebsite = { sheetAction(entry) {
                                if (!com.mybrowser.core.WebsiteShortcuts.pin(this, state.currentUrl, state.pageTitle.orEmpty(), tabManager.currentTab?.favicon))
                                    toast(getString(R.string.shortcut_unavailable))
                            } },
                            onToggleIncognito = { sheetAction(entry, ::toggleIncognito) },
                            onToggleFilter = { if (sheetNavigation.isCurrent(entry)) filter.setEnabled(it) },
                            onToggleDesktopMode = { sheetAction(entry, ::toggleDesktopMode) },
                            onToggleBrowserFullscreen = { target ->
                                // The menu closes first, then the immersive state takes the screen.
                                sheetAction(entry) { setBrowserFullscreenEnabled(target) }
                            },
                            onOpenFind = { sheetAction(entry) { state.showFindBar() } },
                            onOpenMedia = {
                                if (sheetNavigation.push(entry, Sheet.CAST)) {
                                    mediaTrackers.trackerOf(webView)?.probe()
                                    cast.search()
                                }
                            },
                            onOpenBookmarks = {
                                if (sheetNavigation.push(entry, Sheet.BOOKMARKS)) bookmarkLibrary.search("")
                            },
                            onOpenHistory = {
                                if (sheetNavigation.push(entry, Sheet.HISTORY)) historyLibrary.search("")
                            },
                            onOpenDownloads = {
                                sheetNavigation.push(entry, Sheet.DOWNLOADS)
                            },
                            onOpenSettings = {
                                sheetNavigation.push(entry, Sheet.SETTINGS)
                            },
                            onToggleBookmark = {
                                if (!sheetNavigation.isCurrent(entry)) Unit
                                else if (isCurrentPageBookmarked()) {
                                    removeCurrentPageFromBookmarks()
                                } else {
                                    openBookmarkEditor()
                                }
                            },
                            onClearData = {
                                sheetAction(entry) {
                                    showClearData = true
                                }
                            },
                            onOpenDeveloperTools = {
                                sheetNavigation.push(entry, Sheet.DEVELOPER_TOOLS)
                            },
                            onScanQr = { sheetNavigation.push(entry, Sheet.QR_SCANNER) },
                            onExit = { sheetAction(entry, ::exitBrowser) },
                            onDismiss = { dismissSheet(entry) },
                        )

                        Sheet.CAST -> CurrentCastPicker(onDismiss = { dismissSheet(entry) })

                        Sheet.TABS -> TabsSheet(
                            onMoveTab = { id, delta -> tabManager.move(id, delta); persistNormalSession() },
                            onGroupTab = { id, group -> tabManager.setGroup(id, group); persistNormalSession() },
                            tabs = tabManager.tabs,
                            currentIndex = tabManager.currentIndex,
                            isIncognito = privacy.isIncognito,
                            canCreateTab = tabManager.canCreateTab,
                            onSelectTab = { id ->
                                sheetAction(entry) { switchToTab(tabManager.tabs.indexOfFirst { it.id == id }) }
                            },
                            onCloseTab = { id ->
                                closeTab(tabManager.tabs.indexOfFirst { it.id == id })
                            },
                            onNewTab = { sheetAction(entry, ::createNewTab) },
                            onCloseAll = { sheetAction(entry, ::closeAllTabs) },
                            onCloseOthers = {
                                tabManager.closeOtherTabs()
                                pruneResidentTabs()
                                persistNormalSession()
                            },
                            onDismiss = { dismissSheet(entry) },
                        )

                        Sheet.BOOKMARKS -> BookmarksSheet(
                            library = bookmarkLibrary,
                            onImport = bookmarkDocuments::importFile,
                            onExport = bookmarkDocuments::exportFile,
                            transferBusy = bookmarkDocuments.busy,
                            bookmarks = bookmarkLibrary.entries,
                            query = bookmarkLibrary.query,
                            loading = bookmarkLibrary.loading,
                            hasMore = bookmarkLibrary.hasMore,
                            error = bookmarkLibrary.error,
                            onQueryChange = bookmarkLibrary::search,
                            onLoadMore = bookmarkLibrary::loadMore,
                            onOpenNewTab = { url -> sheetAction(entry) { openUrlInNewTab(url) } },
                            onCopy = ::copyToClipboard,
                            onEditBookmark = { bookmark ->
                                if (sheetNavigation.isCurrent(entry)) {
                                    bookmarkDraft = BookmarkDraft(bookmark.title, bookmark.url, bookmark.id)
                                }
                            },
                            onSelectBookmark = { url ->
                                sheetAction(entry) { navigate(url) }
                            },
                            onDeleteBookmark = ::removeBookmark,
                            onClearAll = {
                                dialogs.confirm(this, getString(R.string.bookmarks_clear),
                                    getString(R.string.bookmarks_clear_confirm)) { confirmed ->
                                    if (confirmed) updateLibrary({ bookmarkManager.clearAll() }) {
                                        bookmarkStatus.setKnown(state.currentUrl, false)
                                        loadBookmarks()
                                    }
                                }
                            },
                            onDismiss = { dismissSheet(entry) },
                        )

                        Sheet.HISTORY -> HistorySheet(
                            history = historyLibrary.entries,
                            query = historyLibrary.query,
                            loading = historyLibrary.loading,
                            hasMore = historyLibrary.hasMore,
                            error = historyLibrary.error,
                            onQueryChange = historyLibrary::search,
                            onLoadMore = historyLibrary::loadMore,
                            onOpenNewTab = { url -> sheetAction(entry) { openUrlInNewTab(url) } },
                            onCopy = ::copyToClipboard,
                            onSelectHistory = { url ->
                                sheetAction(entry) { navigate(url) }
                            },
                            onDeleteHistory = { id ->
                                updateLibrary({ historyManager.removeHistory(id) }, ::loadHistory)
                            },
                            onClearAll = {
                                dialogs.confirm(this, getString(R.string.history_clear),
                                    getString(R.string.history_clear_confirm)) { confirmed ->
                                    if (confirmed) updateLibrary({ historyManager.clearAll() }, ::loadHistory)
                                }
                            },
                            onDismiss = { dismissSheet(entry) },
                        )

                        Sheet.DOWNLOADS -> DownloadsSheet(
                            downloads = downloadPresenter.downloads.collectAsState().value,
                            focusedId = downloadFocusId,
                            blocked = downloadRequests.blocked.collectAsState().value,
                            overflowCount = downloadRequests.overflow.collectAsState().value,
                            onUnblockDownload = { identity ->
                                downloadRequests.requestBlocked(identity, privacy::cookiesFor)?.let(::handleDownloadSubmission)
                            },
                            onDismissBlocked = downloadRequests::removeBlocked,
                            onDismiss = { dismissSheet(entry) },
                            onOpenFile = downloadPresenter::openFile,
                            onCancelDownload = downloadPresenter::cancel,
                            onPauseDownload = downloadPresenter::pause,
                            onRetryDownload = downloadPresenter::retry,
                            onDeleteDownload = downloadPresenter::delete,
                            onClearCompleted = downloadPresenter::clearCompleted,
                        )

                        Sheet.SETTINGS -> SettingsSheet(
                            childOpen = showFilterSettings || showUserScripts || showManagedSites || showSiteOrigin != null || showClearData,
                            restoreLastSession = restoreLastSession,
                            onRestoreLastSessionChange = { enabled ->
                                restoreLastSession = enabled
                                homeRepository.saveRestoreLastSession(enabled)
                                persistNormalSession()
                            },
                            isDefaultBrowser = isDefaultBrowser,
                            onSetDefaultBrowser = ::requestDefaultBrowser,
                            currentSearchEngine = searchEngine,
                            availableSearchEngines = availableSearchEngines,
                            onSearchEngineChange = { engine ->
                                searchEngine = engine
                                searchEngineManager.setCurrentEngine(engine)
                                toast(getString(R.string.ui_search_engine_changed_to, engine.displayName(resources)))
                            },
                            onAddCustomSearchEngine = { name, template, suggestTemplate ->
                                runCatching {
                                    searchEngineManager.addCustomEngine(name, template, suggestTemplate)
                                }.onSuccess { added ->
                                    availableSearchEngines = searchEngineManager.getAvailableEngines()
                                    toast(getString(R.string.ui_search_engine_added, added.displayName(resources)))
                                }.onFailure { error ->
                                    Log.w("MainActivity", "invalid custom search engine", error)
                                    toast(getString(R.string.ui_invalid_search_engine_format_or_engine_limit_reached))
                                }
                            },
                            onRemoveCustomSearchEngine = { engine ->
                                searchEngineManager.removeCustomEngine(engine.id)
                                availableSearchEngines = searchEngineManager.getAvailableEngines()
                                if (searchEngine.id == engine.id) {
                                    searchEngine = searchEngineManager.getCurrentEngine()
                                }
                                toast(getString(R.string.ui_search_engine_removed, engine.displayName(resources)))
                            },
                            currentHomepageMode = homepageMode,
                            currentHomepage = homeUrl,
                            onHomepageModeChange = { mode ->
                                homepageMode = mode
                                homeRepository.saveMode(mode)
                                if (state.currentUrl == ABOUT_BLANK && mode == HomepageMode.FIXED_URL) {
                                    goHome()
                                }
                                toast(
                                    if (mode == HomepageMode.NAVIGATION) getString(R.string.ui_using_the_shortcuts_homepage)
                                    else getString(R.string.ui_using_a_custom_homepage_url),
                                )
                            },
                            onHomepageChange = { newHomepage ->
                                val cleanHomepage = newHomepage.trim()
                                val normalized = UrlOrSearch.resolve(newHomepage, searchEngine)
                                val scheme = UrlUtils.schemeOf(normalized)
                                if (UrlUtils.isNavigableInput(cleanHomepage) &&
                                    (scheme == "http" || scheme == "https") &&
                                    UrlUtils.isHttpUrl(normalized)
                                ) {
                                    homeUrl = normalized
                                    homeRepository.saveFixedUrl(normalized)
                                    toast(getString(R.string.ui_homepage_updated))
                                    true
                                } else {
                                    toast(getString(R.string.ui_the_homepage_must_use_an_http_or_https))
                                    false
                                }
                            },
                            onManageCustomFilters = {
                                showFilterSettings = true
                            },
                            onManageUserScripts = { showUserScripts = true },
                            onManageSites = { showManagedSites = true },
                            downloadSettings = downloadSettings,
                            onUseSystemDownloadDirectory = {
                                downloadSettings = downloadSettingsRepository.useSystemDownloads()
                                toast(getString(R.string.ui_downloads_will_be_saved_to_the_system_downloads))
                            },
                            onChooseDownloadDirectory = {
                                val initial = downloadSettings.customTreeUri
                                    ?.let { runCatching { it.toUri() }.getOrNull() }
                                downloadDirectoryLauncher.launch(initial)
                            },
                            onDownloadThreadCountChange = { count ->
                                downloadSettings = downloadSettingsRepository.setThreadCount(count)
                                toast(getString(R.string.ui_download_connections_set_to, downloadSettings.threadCount))
                            },
                            onDownloadNetworkChange = { downloadSettings = downloadSettingsRepository.setUnmeteredOnly(it) },
                            downloadNotificationsEnabled = downloadNotificationsEnabled,
                            onDownloadNotifications = ::openDownloadNotificationGuide,
                            preferences = browserPreferences,
                            onPreferencesChange = {
                                // Turning private suggestions off ends their cache
                                // lifetime: nothing typed under the old setting may
                                // resurface if it is turned back on.
                                val privateSuggestionsOff = privacy.isIncognito &&
                                    browserPreferences.privateSearchSuggestionsEnabled && !it.privateSearchSuggestionsEnabled
                                val fullscreenTarget = it.browserFullscreenEnabled
                                val fullscreenChanged = fullscreenTarget != browserPreferences.browserFullscreenEnabled
                                browserPreferences = preferencesRepository.save(it.copy(browserFullscreenEnabled = browserPreferences.browserFullscreenEnabled))
                                if (fullscreenChanged) setBrowserFullscreenEnabled(fullscreenTarget)
                                if (privateSuggestionsOff) {
                                    (application as App).searchSuggestionProvider.rotatePrivateSession()
                                }
                            },
                            isIncognito = privacy.isIncognito,
                            onIncognitoChange = ::setIncognitoEnabled,
                            isFilterEnabled = filter.enabled.collectAsState().value,
                            onFilterEnabledChange = filter::setEnabled,
                            onClearData = {
                                showClearData = true
                            },
                            onExportSettings = ::exportSettings,
                            onImportSettings = ::importSettings,
                            onDismiss = { dismissSheet(entry) },
                        )

                        Sheet.SITE_SETTINGS -> CurrentSiteSettings(entry)
                        Sheet.QR_SCANNER -> com.mybrowser.ui.qr.QrScannerSheet(
                            onOpenUrl = { url -> sheetAction(entry) { navigate(url) } },
                            onDismiss = { dismissSheet(entry) },
                        )
                        Sheet.DEVELOPER_TOOLS -> {
                            // Hidden tools keep their bounded history without invalidating browser UI.
                            val networkEntries by networkLogs.entries.collectAsState()
                            val consoleEntries by consoleLogs.entries.collectAsState()
                            com.mybrowser.ui.devtools.DeveloperTools(
                                webView = webView,
                                networkEntries = networkEntries,
                                consoleEntries = consoleEntries,
                                onClearNetwork = networkLogs::clear,
                                onClearConsole = consoleLogs::clear,
                                onExplainFilter = { filterExplanation = it },
                                pageUrl = state.currentUrl,
                                pageGeneration = permissionEpoch,
                                isCurrentDocument = { it == permissionEpoch },
                                onLogVisibility = { network, console ->
                                    networkLogs.setVisible(network)
                                    consoleLogs.setVisible(console)
                                },
                                onDismiss = { dismissSheet(entry) }
                            )
                        }
                    }
                }

                if (showFilterSettings) {
                    FilterSettingsSheet(customFilter, filter, onDismiss = { showFilterSettings = false })
                }
                if (showUserScripts) {
                    UserScriptsSheet(userScripts, scriptImportUrl, onUrlConsumed = { scriptImportUrl = null },
                        onDismiss = { showUserScripts = false; scriptImportUrl = null })
                }

                bookmarkDraft?.let { draft ->
                    BookmarkEditDialog(
                        initialTitle = draft.title,
                        initialUrl = draft.url,
                        onSave = ::saveBookmark,
                        isEditing = draft.id != null,
                        onDismiss = {
                            bookmarkDraft = null
                        },
                    )
                }

                pageContextTarget?.let { target ->
                    PageContextSheet(target,
                        onOpen = { url, background -> pageContextTarget = null; openUrlInNewTab(url, background) },
                        onCopy = { url -> pageContextTarget = null; copyToClipboard(url) },
                        onShare = { url -> pageContextTarget = null; shareUrl(url) },
                        onSaveImage = { url -> pageContextTarget = null; downloadImage(url) },
                        onDismiss = { pageContextTarget = null })
                }

                // Security Info Dialog
                if (showSecurityDialog) {
                    com.mybrowser.ui.shell.SecurityInfoDialog(
                        url = state.currentUrl,
                        certificate = securityCertificate,
                        certificateError = hasCertificateWarning,
                        onSiteSettings = SiteOrigin.of(state.currentUrl)?.let { origin -> ({ showSecurityDialog = false; showSiteOrigin = origin }) },
                        onDismiss = {
                            showSecurityDialog = false
                            securityCertificate = null
                        },
                    )
                }

                // SSL Error Dialog
                if (showSSLErrorDialog) {
                    pendingSSLError?.let { (handler, url) ->
                        com.mybrowser.ui.shell.SSLErrorDialog(
                            url = url,
                            onProceed = {
                                currentCertificateError = true
                                certificateWarnings.remember(url)
                                certificateWarnings.remember(state.currentUrl)
                                showSSLErrorDialog = false
                                pendingSSLError = null
                                handler.proceed()
                            },
                            onCancel = {
                                handler.cancel()
                                showSSLErrorDialog = false
                                pendingSSLError = null
                            }
                        )
                    }
                }

                // Developer Tools
                if (showManagedSites) {
                    val sites by activeSites.entries.collectAsState()
                    val needsRepair by activeSites.needsRepair.collectAsState()
                    ManagedSitesSheet(sites, onSelect = { showSiteOrigin = it }, onDismiss = { showManagedSites = false },
                        needsRepair = needsRepair, busy = siteSettingsBusy, onRepair = ::repairSiteSettings)
                }
                if (sheet != Sheet.SITE_SETTINGS) {
                    androidx.compose.runtime.key(showSiteOrigin) { CurrentSiteSettings() }
                }
                bookmarkDocuments.preview?.let {
                    BookmarkImportDialog(it, bookmarkDocuments.busy, bookmarkDocuments::confirmImport, bookmarkDocuments::dismissPreview)
                }
                websitePermissions.prompt?.let { WebsitePermissionDialog(it, websitePermissions::respond) }
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    @androidx.compose.runtime.Composable
    private fun CurrentCastPicker(onDismiss: () -> Unit, embedded: Boolean = false) {
        val mediaSnapshot by media.state.collectAsState()
        val castSnapshot by cast.state.collectAsState()
        if (embedded) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                cast.setVisible(true)
                cast.search()
                onDispose { cast.setVisible(false); cast.cancel() }
            }
        }
        CastSheet(
            candidates = mediaSnapshot.candidates,
            devices = castSnapshot.devices,
            isSearching = castSnapshot.isSearching,
            onSearch = cast::search,
            onCast = { candidate, device -> cast.cast(candidate, device, ::toast) },
            onCopyUrl = { copyToClipboard(it.url) },
            onDismiss = onDismiss,
            preferredCandidate = mediaSnapshot.preferredCandidate,
            playingCandidateUrls = mediaSnapshot.playingCandidateUrls,
            isCasting = castSnapshot.isCasting,
            pendingDevice = castSnapshot.pendingDevice,
            connectedDevice = castSnapshot.connected,
            lastError = castSnapshot.lastError,
            playback = castSnapshot.playback,
            statusUnavailable = castSnapshot.statusUnavailable,
            isControlling = castSnapshot.isControlling,
            onPause = { cast.pause(::toast) }, onResume = { cast.resume(::toast) },
            onStop = { cast.stop(::toast) }, onVolume = { cast.setVolume(it, ::toast) },
            onSeek = { cast.seek(it, ::toast) }, onRefreshStatus = cast::refreshStatus,
            onDisconnect = cast::disconnect,
            embedded = embedded,
        )
    }

    @androidx.compose.runtime.Composable
    private fun CurrentSiteSettings(siteOwner: BrowserSheetNavigation.Presentation? = null) {
        val origin = showSiteOrigin ?: return
        val sites by activeSites.entries.collectAsState()
        val needsRepair by activeSites.needsRepair.collectAsState()
        val settings = androidx.compose.runtime.remember(sites, origin) { activeSites.get(origin) }
        SiteSettingsSheet(origin, settings, privacy.isIncognito, siteSettingsBusy,
            needsRepair = needsRepair, onRepair = ::repairSiteSettings,
            defaultEnhancedPlayback = browserPreferences.video.enhancedControls,
            temporaryFilteringOff = origin in temporaryFilterOrigins,
            onTemporaryFilteringChange = {
                temporaryFilterOrigins = if (origin in temporaryFilterOrigins) temporaryFilterOrigins - origin
                    else (temporaryFilterOrigins + origin).toList().takeLast(128).toSet()
                if (SiteOrigin.of(state.currentUrl) == origin) {
                    workerDocument = documentFor(state.currentUrl)
                    reloadPage()
                }
            },
            onSave = { saveSiteSettings(origin, it) }, onReset = { saveSiteSettings(origin, SiteSettings()) },
            onClearSiteData = { confirmClearSite(origin) },
            onConnectionInfo = if (origin == SiteOrigin.of(state.currentUrl)) ({
                securityCertificate = SecurityChecker.certificateDetails(webView.certificate)
                showSecurityDialog = true
            }) else null,
            onDismiss = {
                if (siteOwner == null || sheetNavigation.isCurrent(siteOwner)) {
                    showSiteOrigin = null
                    siteOwner?.let(::dismissSheet)
                }
            })
    }

    private fun registerActivityLaunchers() {
        // Only the settings "开启通知" action reaches this launcher; downloads never ask.
        // A refusal is a valid end state: it refreshes the row and changes nothing else.
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            downloadNotificationsEnabled = com.mybrowser.download.DownloadNotificationGuide.isEnabled(this)
        }

        downloadFileOpener = com.mybrowser.download.DownloadFileOpener(this, { downloadHandler }, ::toast)
        bookmarkDocuments = BookmarkDocuments(this, lifecycleScope, { bookmarkManager },
            onChanged = { loadBookmarks(); refreshBookmarkStatus(state.currentUrl) }, message = ::toast)
        defaultBrowserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            isDefaultBrowser = DefaultBrowser.isDefault(this)
        }
        fileChooser = com.mybrowser.core.WebFileChooser(this, { webViewOrNull to permissionEpoch }) {
            toast(getString(R.string.capture_failed))
        }
        runtimePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { websitePermissions.onRuntimeResult() }
        settingsExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            // Cancelling the system picker is a valid outcome, not a failure.
            if (uri != null) exportSettingsTo(uri)
        }
        settingsImportLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) importSettingsFrom(uri)
        }
        downloadDirectoryLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null || !::downloadSettingsRepository.isInitialized) {
                return@registerForActivityResult
            }
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching {
                contentResolver.takePersistableUriPermission(uri, grantFlags)
            }.isSuccess
            if (!granted) {
                toast(getString(R.string.ui_unable_to_keep_write_access_to_this_folder))
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val label = withContext(Dispatchers.IO) {
                    downloadSettingsRepository.describeDirectory(uri)
                }
                val updated = runCatching {
                    downloadSettingsRepository.useCustomDirectory(uri, label)
                }.getOrElse {
                    Log.w("MainActivity", "Unable to save custom download directory", it)
                    toast(getString(R.string.ui_unable_to_use_the_selected_download_folder))
                    return@launch
                }
                downloadSettings = updated
                if (updated.customTreeUri == uri.toString() &&
                    updated.destinationMode ==
                    com.mybrowser.download.DownloadDestinationMode.CUSTOM_DIRECTORY
                ) {
                    toast(getString(R.string.ui_download_folder_set_to, updated.displayDestinationLabel(resources)))
                } else {
                    toast(getString(R.string.ui_folder_access_is_unavailable_using_the_system_downloads))
                }
            }
        }
    }

    private fun configure(view: WebView) {
        view.webViewClient = BrowserWebViewClient(this)
        view.webChromeClient = BrowserChromeClient(this, view)
        pageContextMenu.attach(view)
        view.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (webViewOrNull === view) {
                state.onPageScroll(scrollY, oldScrollY, resources.displayMetrics.density)
            }
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (webViewOrNull !== view) return@setDownloadListener
            if (onUserScriptUrl(url)) return@setDownloadListener
            submitDownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // Find in page listener
        view.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting && webViewOrNull === view) {
                state.onFindResultUpdate(activeMatchOrdinal + 1, numberOfMatches)
            }
        }

        // Re-applied on every acquire, including the post-crash replacement: a fresh
        // instance defaults to the normal profile, which would silently drop an incognito
        // session back onto persistent storage.
        privacy.applyTo(view)
        com.mybrowser.core.SystemLoginSupport.configure(view, privacy.isIncognito)
        // Normal pages keep their departed document so Back does not rebuild it; private
        // pages never do, because a cached document cannot be flushed when the session ends.
        com.mybrowser.core.WebViewConfig.applyBackForwardCache(view, !privacy.isIncognito)

        // Apply desktop mode setting
        applySiteSettings(view, state.currentUrl)

        installMediaPlaybackTracker(view)
        scriptRuntimes.remove(view)?.close()
        scriptRuntimes[view] = UserScriptRuntime(view, userScripts, lifecycleScope,
            isAllowed = { !privacy.isIncognito }, isCurrent = { webViewOrNull === view }).also { it.install() }
    }

    /** Installs one media probe per pooled WebView and replaces stale Activity callbacks. */
    private fun installMediaPlaybackTracker(view: WebView) {
        mediaTrackers.install(view) { tracked, tracker, signal -> applyMediaSignal(tracked, tracker, signal) }
    }

    /** What one probe signal means here: media store, system session, fullscreen host, speed. */
    private fun applyMediaSignal(
        view: WebView,
        tracker: MediaPlaybackTracker,
        signal: MediaPlaybackTracker.Signal,
    ) {
        runOnUiThread {
            // A popup installs its tracker before the previous tab is released.
            // Disposing that other WebView must not invalidate this view's signals.
            if (webViewOrNull !== view || mediaTrackers.trackerOf(view) !== tracker) {
                return@runOnUiThread
            }
            hasVideo = signal.hasVideo
            (view as? com.mybrowser.core.BrowserWebView)?.keepMediaOnWindowHidden =
                signal.isPlaying && browserPreferences.video.backgroundPlayback && !privacy.isIncognito
            media.updatePlayback(signal)
            systemMedia.update(this, signal, state.pageTitle.orEmpty(), privacy.isIncognito, browserPreferences.video.backgroundPlayback)
            pipController.update()
            fullscreenView?.update(signal)
            val preferences = browserPreferences.video
            if (signal.isPlaying && !signal.isBoosting && preferences.rememberSpeed &&
                signal.identity != rememberedVideo
            ) {
                rememberedVideo = signal.identity
                mediaTrackers.trackerOf(view)?.setPlaybackRate(preferences.preferredSpeed)
            }
        }
    }

    private fun removeMediaPlaybackTracker(view: WebView) {
        popupPages.idOf { it.view === view }?.let { popupPages.remove(it)?.alive = false }
        scriptRuntimes.remove(view)?.close()
        if (webViewOrNull === view) {
            systemMedia.detach(this)
            leaveFullscreen()
            rememberedVideo = null
            hasVideo = false
        }
        mediaTrackers.remove(view)?.close()
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val view = webViewOrNull
        val tracker = view?.let(mediaTrackers::trackerOf)
        if (view == null || tracker == null) {
            toast(getString(R.string.playback_speed_failed))
            return
        }
        tracker.setPlaybackRate(speed) { applied ->
            runOnUiThread {
                if (webViewOrNull !== view || mediaTrackers.trackerOf(view) !== tracker) return@runOnUiThread
                if (applied) {
                    rememberPlaybackSpeed(speed)
                    val host = fullscreenView
                    if (host != null) host.announceSpeed(speed)
                    else toast(
                        getString(
                            R.string.playback_speed_applied,
                            PlaybackSpeed.label(speed),
                        ),
                    )
                } else {
                    tracker.probe()
                    toast(getString(R.string.playback_speed_failed))
                }
            }
        }
    }

    private fun handleFileChooser(
        callback: ValueCallback<Array<Uri>?>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = fileChooser.show(callback, params)

    private fun handlePermissionRequest(request: PermissionRequest) {
        val capabilities = request.resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SiteCapability.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SiteCapability.MICROPHONE
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> SiteCapability.PROTECTED_MEDIA
                else -> null
            }
        }
        if (capabilities.size != request.resources.size) { request.deny(); return }
        val view = webViewOrNull
        val epoch = permissionEpoch
        websitePermissions.request(request, request.origin.toString(), capabilities, privacy.isIncognito, activeSites,
            isCurrent = { !isFinishing && !isDestroyed && webViewOrNull === view && permissionEpoch == epoch },
            reply = { allowed -> if (allowed) request.grant(request.resources) else request.deny() })
    }

    private fun handleGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        val view = webViewOrNull
        val epoch = permissionEpoch
        websitePermissions.request("geolocation", origin, listOf(SiteCapability.LOCATION), privacy.isIncognito, activeSites,
            isCurrent = { !isFinishing && !isDestroyed && webViewOrNull === view && permissionEpoch == epoch },
            reply = { allowed -> callback.invoke(origin, allowed, false) })
        // WebView must ask our gate every time; its own remembered grant would bypass
        // later revocation in the website settings screen.
    }

    private fun cancelWebsitePermissions() {
        if (::fileChooser.isInitialized) fileChooser.cancelDocument()
        permissionEpoch++
        externalPrompt = null
        backHistory = null
        websitePermissions.cancel()
    }

    private fun applySiteSettings(view: WebView, url: String) {
        val settings = activeSites.get(url)
        state.isDesktopMode = settings.desktop
        workerDocument = documentFor(url)
        WebViewConfig.applySiteSettings(view, settings)
    }

    private fun repairSiteSettings() {
        if (siteSettingsBusy) return
        val repository = activeSites
        siteSettingsBusy = true
        lifecycleScope.launch {
            try {
                repository.repair()
                if (repository === activeSites) {
                    cancelWebsitePermissions()
                    webViewOrNull?.let { applySiteSettings(it, state.currentUrl) }
                    reloadPage()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("MainActivity", "Unable to repair website settings", error)
                toast(getString(R.string.site_save_failed))
            } finally { siteSettingsBusy = false }
        }
    }

    private fun saveSiteSettings(origin: String, settings: SiteSettings) {
        if (siteSettingsBusy) return
        val repository = activeSites
        val owner = sheetNavigation.current?.takeIf { it.destination == Sheet.SITE_SETTINGS }
        siteSettingsBusy = true
        lifecycleScope.launch {
            try {
                repository.update(origin) { settings }
                if (repository === activeSites && (SiteOrigin.of(state.currentUrl) == origin ||
                    repository.get(state.currentUrl).desktop != state.isDesktopMode)) {
                    cancelWebsitePermissions()
                    applySiteSettings(webView, state.currentUrl)
                    reloadPage()
                }
                if (showSiteOrigin == origin && (owner == null || sheetNavigation.isCurrent(owner))) {
                    showSiteOrigin = null
                    // Saving explicitly refreshes the page; cancelling uses Back and
                    // returns to the menu. Do not leave the refreshed page under a scrim.
                    if (owner != null) sheetAction(owner) {}
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("MainActivity", "Unable to save website settings", error)
                toast(getString(R.string.site_save_failed))
            } finally { siteSettingsBusy = false }
        }
    }

    private fun handleJsDialog(
        type: BrowserChromeClient.JsDialogType,
        origin: String,
        message: String,
        defaultValue: String?,
        result: JsResult,
    ): Boolean {
        if (isFinishing || isDestroyed) {
            runCatching { result.cancel() }
            return true
        }
        val title = if (origin.isBlank()) getString(R.string.ui_webpage_message) else getString(R.string.ui_message_from, origin)
        when (type) {
            BrowserChromeClient.JsDialogType.ALERT -> dialogs.alert(
                this,
                title,
                message,
            ) { runCatching { result.confirm() } }
            BrowserChromeClient.JsDialogType.CONFIRM -> dialogs.confirm(
                this,
                title,
                message,
            ) { accepted -> runCatching { if (accepted) result.confirm() else result.cancel() } }
            BrowserChromeClient.JsDialogType.PROMPT -> dialogs.prompt(
                this,
                title,
                message,
                defaultValue,
            ) { value ->
                val prompt = result as? JsPromptResult
                runCatching {
                    if (value == null) {
                        result.cancel()
                    } else if (prompt != null) {
                        prompt.confirm(value)
                    } else {
                        result.confirm()
                    }
                }
            }
            BrowserChromeClient.JsDialogType.BEFORE_UNLOAD -> dialogs.confirm(
                this,
                title,
                message.ifBlank { getString(R.string.ui_leave_this_page) },
                positiveText = getString(R.string.ui_leave),
                negativeText = getString(R.string.ui_stay),
            ) { leave -> runCatching { if (leave) result.confirm() else result.cancel() } }
        }
        return true
    }

    private fun createPopupTab(isUserGesture: Boolean): WebView? {
        if (privacy.isTransitioning) return null
        if (!isUserGesture || !tabManager.canCreateTab) return null
        val old = webViewOrNull ?: return null
        val openerId = tabManager.currentTab?.id ?: return null
        saveCurrentTab()
        val openerClearHistory = clearHistoryOnNextFinish
        // Chromium rejects a popup transport whose target has ever navigated, including
        // about:blank used when returning an old instance to the pool.
        val popup = runCatching { acquireFreshPage() }.getOrNull() ?: return null
        mediaTrackers.trackerOf(popup)?.prepareForPopup()
        scriptRuntimes[popup]?.prepareForPopup()
        cancelWebsitePermissions()
        cancelPendingSslError()
        dismissPageContext()
        leaveFullscreen()
        systemMedia.detach(this)
        mediaProbeJob?.cancel()
        media.clear(); hasVideo = false; rememberedVideo = null
        tabManager.createTab(openerTabId = openerId)
        exitConfirmation.reset()
        viewOwnerId = tabManager.currentTab?.id
        viewOwnerId?.let(downloadRequests::selectTab)
        readyWebViewTabId = null
        clearHistoryOnNextFinish = true
        val opener = popupPages[openerId] ?: PopupPage(openerId, old, workerDocument)
        opener.document = documentFor(state.currentUrl)
        opener.clearHistory = openerClearHistory
        opener.loading = state.isLoading
        opener.failure = state.pageFailure
        opener.certificateError = currentCertificateError
        val child = PopupPage(checkNotNull(viewOwnerId), popup, documentFor(ABOUT_BLANK))
        child.clearHistory = true
        popupPages.connect(openerId, opener, child.tabId, child)
        pendingPopupTransfers[popup] = pool.holdForPopup(old, popup)
        webViewOrNull = popup
        backgroundPopupPage(opener)
        state.onPageStarted(ABOUT_BLANK)
        state.onTitleChanged("")
        persistNormalSession()
        return popup
    }

    private fun toggleDesktopMode() {
        val origin = SiteOrigin.of(state.currentUrl) ?: return
        saveSiteSettings(origin, activeSites.get(origin).copy(desktop = !state.isDesktopMode))
    }

    /**
     * Switching modes destroys the WebView and switches to the other tab group.
     * Normal and incognito modes maintain completely separate tab systems.
     */
    private fun toggleIncognito() {
        setIncognitoEnabled(!privacy.isIncognito)
    }

    /**
     * The single mode-switch entry for the menu, the settings toggle and settings import.
     * Idempotent: asking for the mode already active changes nothing, so an import that
     * repeats the current value never wipes the private tabs being used. The persistent
     * wish is written first with a confirmed result — a failed save aborts the switch.
     */
    private fun setIncognitoEnabled(target: Boolean) {
        if (target == privacy.isIncognito || clearingData || privacy.isTransitioning) return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { preferencesRepository.saveIncognitoEnabled(target) }
            if (target == privacy.isIncognito || clearingData || privacy.isTransitioning) return@launch
            if (!saved) {
                toast(getString(R.string.incognito_preference_save_failed))
                return@launch
            }
            browserPreferences = browserPreferences.copy(incognitoEnabled = target)
            performIncognitoSwitch(target)
        }
    }

    private fun loadBrowserPreferences() = preferencesRepository.load().let {
        it.copy(browserFullscreenEnabled = sessionState.fullscreenPreference.effective(it.browserFullscreenEnabled))
    }

    /** Shared entry: enable after confirmed save; exit immediately, even if saving fails. */
    private fun setBrowserFullscreenEnabled(target: Boolean) {
        val controller = sessionState.fullscreenPreference
        val token = controller.request(target)
        if (!target) {
            browserPreferences = browserPreferences.copy(browserFullscreenEnabled = false)
            state.revealChrome()
            applyWindowInsetsPolicy()
        }
        lifecycleScope.launch {
            val saved = controller.persist(token, target, preferencesRepository::saveBrowserFullscreenEnabled) ?: return@launch
            browserPreferences = loadBrowserPreferences()
            applyWindowInsetsPolicy()
            if (!saved) toast(getString(if (target) R.string.browser_fullscreen_save_failed else R.string.browser_fullscreen_exit_unsaved))
        }
    }

    private fun performIncognitoSwitch(entering: Boolean) {
        if (clearingData || privacy.isTransitioning) return
        scriptNavigationJob?.cancel()
        scriptNavigationJob = null
        clearResidentTabs()
        temporaryFilterOrigins = emptySet()
        viewOwnerId = null
        cancelWebsitePermissions()
        showSiteOrigin = null
        showManagedSites = false
        // The pending download's cookie context belongs to the session being left; drop
        // it rather than let the new session download with the old credentials.
        downloadRequests.resetTransientState()
        // Entering starts a fresh private session and leaving ends one; either way the
        // incognito suggestion cache must not survive the boundary.
        (application as App).searchSuggestionProvider.rotatePrivateSession()
        // BrowserSessionState scopes private downloads on every session start/end,
        // including a persisted private startup and closing/reopening this window.
        // Do not retain a private session's certificate choices in the normal session.
        webViewOrNull?.clearSslPreferences()
        certificateWarnings.clear()
        currentCertificateError = false
        cancelPendingSslError()

        // Save current tab state before switching
        saveCurrentTab()

        webViewOrNull?.let { old ->
            removeMediaPlaybackTracker(old)
            old.webViewClient = DetachedWebViewClient
            old.webChromeClient = null
            (old.parent as? ViewGroup)?.removeView(old)
            if (!entering) privacy.wipeInstanceState(old)
            // Discarded, not returned to the pool: a profile-bound instance must never be
            // handed to a later normal session, and discard also fixes pool bookkeeping.
            pool.discard(old)
        }
        webViewOrNull = null

        // Switch to the other tab manager. The incognito stack is discarded on exit;
        // no URL, thumbnail or saved WebView bundle survives the session.
        if (entering) {
            persistNormalSession()
            privateSites = normalSites.privateSession()
            sessionState.beginPrivateSession()
            tabManager = incognitoTabManager
        } else {
            privateSites = null
            incognitoTabManager.clearAllTabs()
            tabManager = normalTabManager
            // The fallback mode shares the normal cookie jar while open. Keep the new
            // WebView blank until the asynchronous cookie wipe has completed.
            sessionState.endPrivateSession()
        }

        media.clear()
        filter.resetPageCount()

        // A profile-bound WebView must be completely unused before attachment. Never
        // borrow an idle normal-profile instance for a mode transition.
        webViewOrNull = acquireFreshPage()
        if (!entering) {
            loadCurrentTab()
            lifecycleScope.launch {
                privacy.awaitReady()
                if (!isFinishing && !isDestroyed && !privacy.isIncognito) {
                    toast(getString(if (privacy.lastCleanupSucceeded) R.string.incognito_off else R.string.clear_data_failed))
                }
            }
        }
        if (entering) {
            loadCurrentTab()
            toast(
                if (privacy.hasRealIsolation) getString(R.string.incognito_on)
                // Honest about the difference: without WebView 114+ multi-profile the
                // session is wipe-on-exit rather than truly isolated.
                else getString(R.string.incognito_fallback),
            )
        }
    }

    // --- Tab management ---

    /**
     * Loads the current tab's state into the WebView.
     * If the tab has saved state, restore it; otherwise load its URL.
     */
    private fun loadCurrentTab() {
        if (awaitPrivacyReady(::loadCurrentTab)) return
        systemMedia.detach(this)
        cancelWebsitePermissions()
        cancelPendingSslError()
        if (awaitScriptsReady(::loadCurrentTab)) return
        val tab = tabManager.currentTab ?: return
        downloadRequests.retainTabs(tabManager.tabs.map { it.id }.toSet())
        downloadRequests.selectTab(tab.id)
        val oldId = viewOwnerId
        val wasReady = readyWebViewTabId
        val priorSettings = activeSites.get(state.currentUrl)
        val priorFiltering = workerDocument.filtering && filter.enabled.value
        readyWebViewTabId = null
        dismissPageContext()
        leaveFullscreen()
        state.revealToolbar()
        mediaProbeJob?.cancel()
        media.clear()
        hasVideo = false
        rememberedVideo = null
        if (oldId != null && oldId != tab.id) {
            val previousFailure = state.pageFailure
            val previousPageFailed = previousFailure != null
            state.pageFailure = null
            webViewOrNull?.let { old ->
                val liveOld = popupPages[oldId]?.takeIf { it.view === old }
                val liveTarget = popupPages[tab.id]
                val keep = liveOld == null && !privacy.isIncognito && wasReady == oldId && !previousPageFailed &&
                    !state.isLoading && normalTabManager.tabs.any { it.id == oldId }
                val parked = if (!privacy.isIncognito) recentViews.take(tab.id) else null
                when {
                    liveOld != null -> {
                        liveOld.document = documentFor(state.currentUrl)
                        liveOld.loading = state.isLoading
                        liveOld.failure = previousFailure
                        liveOld.certificateError = currentCertificateError
                        liveOld.clearHistory = clearHistoryOnNextFinish
                        backgroundPopupPage(liveOld)
                    }
                    keep -> {
                        old.stopLoading()
                        suspendPage(old)
                        recentViews.put(oldId, ParkedPage(old, priorSettings, priorFiltering))
                    }
                    else -> { removeMediaPlaybackTracker(old); pool.discard(old) }
                }
                webViewOrNull = liveTarget?.view ?: parked?.view ?: acquireFreshPage()
                viewOwnerId = tab.id
                if (liveTarget != null) {
                    webView.webViewClient = BrowserWebViewClient(this)
                    webView.webChromeClient = BrowserChromeClient(this, webView)
                    webView.onResume()
                    mediaTrackers.trackerOf(webView)?.setSuspended(false)
                    val url = liveTarget.document.url
                    state.onPageStarted(url)
                    applySiteSettings(webView, url)
                    currentCertificateError = liveTarget.certificateError
                    clearHistoryOnNextFinish = liveTarget.clearHistory
                    state.pageFailure = liveTarget.failure
                    if (!liveTarget.loading) {
                        state.onPageFinished(url, webView.canGoBack(), webView.canGoForward())
                        readyWebViewTabId = tab.id.takeIf { liveTarget.failure == null }
                    }
                    state.onTitleChanged(webView.title ?: tab.title)
                    refreshBookmarkStatus(url)
                    startPlayingVideoDetection()
                    popupPages.releaseIfUnlinked(tab.id)?.alive = false
                    return
                }
                if (parked != null) {
                    webView.webViewClient = BrowserWebViewClient(this)
                    webView.webChromeClient = BrowserChromeClient(this, webView)
                    webView.onResume()
                    mediaTrackers.trackerOf(webView)?.setSuspended(false)
                    currentCertificateError = false
                    state.onPageStarted(tab.url)
                    applySiteSettings(webView, tab.url)
                    if (parked.settings != activeSites.get(tab.url) || parked.filtering != (workerDocument.filtering && filter.enabled.value)) {
                        reloadPage()
                    } else {
                        clearHistoryOnNextFinish = false
                        readyWebViewTabId = tab.id
                        state.onPageFinished(tab.url, webView.canGoBack(), webView.canGoForward())
                        state.onTitleChanged(webView.title ?: tab.title)
                        refreshBookmarkStatus(tab.url)
                        startPlayingVideoDetection()
                    }
                    return
                }
            }
        }
        viewOwnerId = tab.id
        // WebView.restoreState is only supported before the instance builds history.
        // Reusing a loaded instance loses restored navigation entries on older providers.
        if (tab.savedState != null && webView.copyBackForwardList().size > 0) {
            val old = webView
            removeMediaPlaybackTracker(old)
            pool.discard(old)
            webViewOrNull = acquireFreshPage()
        }
        webViewOrNull?.let { mediaTrackers.trackerOf(it)?.reset() }
        applySiteSettings(webView, tab.url)
        val restored = tabManager.loadCurrentState(webView)
        clearHistoryOnNextFinish = !restored
        if (!restored) {
            val url = tab.url.takeIf { it.isNotBlank() && it != ABOUT_BLANK }
                ?: if (homepageMode == HomepageMode.NAVIGATION) ABOUT_BLANK else homeUrl
            state.onPageStarted(url)
            state.onTitleChanged(tab.title)
            applySiteSettings(webView, url)
            loadPage(url)
        } else {
            // restoreState starts an asynchronous navigation. Older providers briefly
            // report about:blank, so neither display nor persist that intermediate page.
            state.onPageStarted(tab.url)
            state.onTitleChanged(tab.title)
        }
    }

    /**
     * Saves the current WebView state into the current tab before switching away.
     */
    private fun saveCurrentTab() {
        if (readyWebViewTabId != tabManager.currentTab?.id) return
        tabManager.saveCurrentState(webView)
        // Update thumbnail
        tabManager.captureCurrentThumbnail(webView, 200, 300)
    }

    private fun createNewTab() {
        if (!tabManager.canCreateTab) {
            toast(getString(R.string.ui_tab_limit_reached))
            return
        }
        saveCurrentTab()
        tabManager.createTab()
        stopDepartingPage()
        loadCurrentTab()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabManager.tabs.indices || index == tabManager.currentIndex) return
        saveCurrentTab()
        stopDepartingPage()
        tabManager.switchToIndex(index)
        loadCurrentTab()
    }

    private fun closeTab(index: Int) {
        if (index !in tabManager.tabs.indices) return
        exitConfirmation.reset()
        popupPages.remove(tabManager.tabs[index].id)?.let { page ->
            page.alive = false
            if (page.view !== webViewOrNull) disposePopupPage(page)
        }
        val wasCurrent = index == tabManager.currentIndex
        tabManager.closeTab(index)
        pruneResidentTabs()
        if (wasCurrent) {
            webView.stopLoading()
            loadCurrentTab()
        } else {
            viewOwnerId?.let { popupPages.releaseIfUnlinked(it)?.alive = false }
        }
        persistNormalSession()
    }

    private fun closeAllTabs() {
        clearResidentTabs()
        downloadRequests.resetTransientState()
        tabManager.clearAllTabs()
        webView.stopLoading()
        loadCurrentTab()
        persistNormalSession()
    }

    private fun openUrlInNewTab(url: String, background: Boolean = false) {
        if (!UrlUtils.isHttpUrl(url)) { navigate(url); return }
        if (!tabManager.canCreateTab) { toast(getString(R.string.ui_tab_limit_reached)); return }
        if (!background) saveCurrentTab()
        tabManager.createTab(url, select = !background, title = UrlUtils.hostOf(url).orEmpty(),
            openerTabId = tabManager.currentTab?.id)
        if (background) {
            persistNormalSession()
            toast(getString(R.string.context_opened_background))
        } else {
            stopDepartingPage()
            loadCurrentTab()
        }
    }

    private fun dismissPageContext() {
        pageContextMenu.invalidate()
        pageContextTarget = null
    }

    private fun shareUrl(url: String) {
        if (!UrlUtils.isHttpUrl(url)) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.context_share_link))) }
            .onFailure { toast(getString(R.string.context_share_unavailable)) }
    }

    private fun downloadAdded(id: Long?) {
        toast(getString(if (id != null) R.string.download_started else R.string.ui_unable_to_start_the_download))
    }

    /** Real system state of download notifications, for the settings row. */
    private var downloadNotificationsEnabled by mutableStateOf(true)

    /** The settings entry: the only place that asks for notification permission. */
    private fun openDownloadNotificationGuide() {
        when (com.mybrowser.download.DownloadNotificationGuide.route(this)) {
            com.mybrowser.download.DownloadNotificationGuide.Route.PermissionRequest -> {
                com.mybrowser.download.DownloadNotificationGuide.markPermissionRequested(this)
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            com.mybrowser.download.DownloadNotificationGuide.Route.ChannelSettings ->
                com.mybrowser.download.DownloadNotificationGuide.safeStart(
                    this, com.mybrowser.download.DownloadNotificationGuide.channelSettingsIntent(this))
            com.mybrowser.download.DownloadNotificationGuide.Route.AppSettings ->
                com.mybrowser.download.DownloadNotificationGuide.safeStart(
                    this, com.mybrowser.download.DownloadNotificationGuide.appSettingsIntent(this))
        }
        downloadNotificationsEnabled = com.mybrowser.download.DownloadNotificationGuide.isEnabled(this)
    }

    private fun downloadImage(url: String) {
        // An explicit save is independent of page popup limits and prior rejections.
        // It confirms a new copy locally or reports an unfinished task without opening a sheet.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        submitDownloadRequest(url, webView.settings.userAgentString, null, mime, fromUser = true)
    }

    /**
     * The one entry every download request passes through. Nothing touches the engine
     * before the user confirms; repeats collapse into the existing task or the blocked
     * list instead of stacking dialogs.
     */
    private fun submitDownloadRequest(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long = -1, fromUser: Boolean = false) {
        val filename = downloadHandler.previewFilename(url, contentDisposition, mimeType)
        if (filename == null) {
            toast(getString(R.string.ui_unable_to_start_the_download))
            return
        }
        val request = com.mybrowser.download.DownloadRequestCoordinator.Request(
            url = url,
            filename = filename,
            mimeType = mimeType,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            referer = state.currentUrl,
            isPrivate = privacy.isIncognito,
            cookieHeader = privacy.cookiesFor(url),
            sourceOrigin = com.mybrowser.site.SiteOrigin.of(state.currentUrl),
            contentLength = contentLength.takeIf { it >= 0 },
        )
        handleDownloadSubmission(if (fromUser) downloadRequests.submitFromUser(request) else downloadRequests.submit(request))
    }

    private fun handleDownloadSubmission(result: com.mybrowser.download.DownloadRequestCoordinator.SubmitResult) {
        when (result) {
            is com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.Confirm -> Unit
            is com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.ExistingNotice -> toast(getString(
                if (result.status == com.mybrowser.download.DownloadStatus.PAUSED) R.string.download_existing_paused
                else R.string.download_existing_active))
            com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.ConfirmationBusy ->
                toast(getString(R.string.download_blocked_limit))
            is com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.Existing -> if (result.firstForPage) {
                // The "view task" entry: once per document, silent on repeats.
                downloadFocusId = result.id
                openSheet(Sheet.DOWNLOADS)
            }
            is com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.Suppressed -> Unit
            is com.mybrowser.download.DownloadRequestCoordinator.SubmitResult.Intercepted ->
                if (result.firstNotice) {
                    // Notify for the first interception and first overflow only;
                    // subsequent events update the sheet without a toast flood.
                    toast(if (downloadRequests.overflow.value > 0) getString(R.string.download_blocked_overflow, downloadRequests.overflow.value)
                        else getString(R.string.download_requests_intercepted, result.blockedCount))
                }
        }
    }

    private fun confirmDownloadRequest() {
        when (val outcome = downloadRequests.confirmPending()) {
            is com.mybrowser.download.DownloadHandler.EnqueueOutcome.Started -> downloadAdded(outcome.id)
            is com.mybrowser.download.DownloadHandler.EnqueueOutcome.Existing -> {
                downloadFocusId = outcome.id
                toast(getString(R.string.download_already_known))
            }
            com.mybrowser.download.DownloadHandler.EnqueueOutcome.Rejected ->
                toast(getString(R.string.ui_unable_to_start_the_download))
        }
    }

    // --- Bookmarks and History ---

    private fun loadBookmarks() = bookmarkLibrary.refresh()

    private fun loadHistory() = historyLibrary.refresh()

    /** Opens the editor with the URL and title currently shown by the WebView. */
    private fun openBookmarkEditor() {
        val url = state.currentUrl.takeUnless { it == ABOUT_BLANK }.orEmpty()
        val title = state.pageTitle?.trim().orEmpty()
            .ifBlank { webView.title?.trim().orEmpty() }
            .ifBlank { UrlUtils.hostOf(url).orEmpty() }
            .ifBlank { url }

        bookmarkDraft = BookmarkDraft(title = title, url = url)
        // The editor is a separate modal surface. Dismiss the menu first so two modal windows
        // never compete for focus or leave the sheet's scrim above the text fields.
        sheetNavigation.clear()
    }

    /** Saves edited bookmark data after normalising a schemeless host. */
    private fun saveBookmark(title: String, rawUrl: String, addToHome: Boolean) {
        val draft = bookmarkDraft ?: return
        val cleanTitle = title.trim()
        val cleanInput = rawUrl.trim()
        if (cleanTitle.isEmpty() || !UrlUtils.isNavigableInput(cleanInput)) {
            toast(getString(R.string.bookmark_invalid_url))
            return
        }

        val normalized = UrlOrSearch.resolve(cleanInput, searchEngine)
        val scheme = UrlUtils.schemeOf(normalized)
        val accepted = when {
            (scheme == "http" || scheme == "https") && UrlUtils.isHttpUrl(normalized) -> true
            UrlUtils.isAllowedExternalScheme(normalized) -> true
            scheme == "about" && normalized != ABOUT_BLANK -> true
            else -> false
        }
        if (!accepted) {
            toast(getString(R.string.bookmark_invalid_url))
            return
        }

        val shouldAddToHome = addToHome && UrlUtils.isHttpUrl(normalized)
        // Give the background encoder its own bitmap, and only reuse the site's icon
        // when the edited URL still identifies the current page.
        val faviconCopy = if (shouldAddToHome && normalized == state.currentUrl) {
            runCatching {
                tabManager.currentTab?.favicon
                    ?.takeUnless(Bitmap::isRecycled)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
        } else {
            null
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val rowId = runCatching {
                if (draft.id == null) bookmarkManager.addBookmark(cleanTitle, normalized)
                else if (bookmarkManager.updateBookmark(draft.id, cleanTitle, normalized)) draft.id else -1L
            }.getOrDefault(-1L)
            val updatedShortcuts = if (rowId >= 0L && shouldAddToHome) {
                runCatching {
                    homeRepository.upsertShortcut(cleanTitle, normalized, faviconCopy)
                }.onFailure { error ->
                    Log.w("MainActivity", "failed to add homepage shortcut", error)
                }.getOrNull()
            } else {
                null
            }
            faviconCopy?.recycle()
            withContext(Dispatchers.Main) {
                if (rowId < 0L) {
                    toast(getString(if (draft.id == null) R.string.bookmark_save_failed else R.string.library_edit_failed))
                    return@withContext
                }
                if (updatedShortcuts != null) homeShortcuts = updatedShortcuts
                if (bookmarkDraft == draft) {
                    bookmarkDraft = null
                }
                if (state.currentUrl == normalized) {
                    bookmarkStatus.setKnown(normalized, true)
                } else {
                    refreshBookmarkStatus(state.currentUrl)
                }
                loadBookmarks()
                toast(
                    when {
                        shouldAddToHome && updatedShortcuts?.any { it.url == normalized } == true ->
                            getString(R.string.bookmark_added_to_home)
                        shouldAddToHome -> getString(R.string.bookmark_home_add_failed)
                        draft.id != null -> getString(R.string.library_bookmark_saved)
                        else -> getString(R.string.bookmark_added)
                    },
                )
            }
        }
    }

    private suspend fun saveHomeShortcut(id: String, title: String, url: String, icon: ShortcutIconChange): ShortcutSaveResult {
        val (result, updated) = withContext(Dispatchers.IO) {
            runCatching {
                val result = homeRepository.updateShortcut(id, title, url, icon)
                result to if (result == ShortcutSaveResult.SAVED) homeRepository.loadShortcuts() else null
            }.onFailure { Log.w("MainActivity", "failed to edit homepage shortcut", it) }
                .getOrElse { ShortcutSaveResult.FAILED to null }
        }
        if (updated != null) homeShortcuts = updated
        return result
    }

    private suspend fun removeHomeShortcut(shortcut: HomeShortcut): Boolean {
        val updated = withContext(Dispatchers.IO) {
            runCatching { homeRepository.removeShortcut(shortcut.id) }
                .onFailure { Log.w("MainActivity", "failed to remove homepage shortcut", it) }.getOrNull()
        } ?: return false
        homeShortcuts = updated
        toast(getString(R.string.home_shortcut_removed))
        return true
    }

    /** Apply UI changes only after the database has confirmed the mutation. */
    private fun updateLibrary(write: () -> Unit, onSuccess: () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                onSuccess()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MainActivity", "Library update failed", error)
                toast(getString(R.string.library_update_failed))
            }
        }
    }

    private fun removeBookmark(url: String) {
        updateLibrary({ bookmarkManager.removeBookmark(url) }) {
            bookmarkStatus.setKnown(url, false)
            loadBookmarks()
            toast(getString(R.string.bookmark_removed))
        }
    }

    private fun removeCurrentPageFromBookmarks() {
        state.currentUrl.takeIf(String::isNotBlank)?.let(::removeBookmark)
    }

    private fun isCurrentPageBookmarked(): Boolean {
        return currentPageBookmarked
    }

    /** Whether the visible page is bookmarked; owns the read and the staleness check. */
    private val bookmarkStatus by lazy {
        com.mybrowser.data.BookmarkStatusTracker(
            lookup = { bookmarkManager.isBookmarked(it) },
            scope = lifecycleScope,
            visibleUrl = { state.currentUrl },
            isHome = { url -> isHomeDocument(url) },
            onChange = { currentPageBookmarked = it },
        )
    }

    private fun refreshBookmarkStatus(url: String) = bookmarkStatus.refresh(url)

    private fun addToHistory(url: String, title: String) {
        // Don't add to history in incognito mode or for special URLs
        if (privacy.isIncognito) return
        if (isHomeDocument(url)) return
        if (url.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                historyManager.addHistory(title, url)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An unavailable history database must not interrupt page browsing.
                Log.w("MainActivity", "Unable to record page visit", error)
            }
        }
    }

    /**
     * Clears browsing state promised by the menu label while preserving user-created
     * bookmarks and downloaded files.  The previous implementation silently deleted both,
     * even though the confirmation text only named history, cookies and cache.
     */
    private fun clearBrowsingData(request: ClearDataRequest) {
        if (clearingData) return
        val targets = request.scope.targets(privacy.isIncognito, privacy.hasRealIsolation)
        val current = ClearScope.CURRENT.targets(privacy.isIncognito, privacy.hasRealIsolation).single()
        clearingData = true
        lifecycleScope.launch {
            var failed = false
            var siteStorageUnreadable = false
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (error: Exception) {
                    if (!isActive) throw error
                    failed = true
                    Log.w("DataCleanup", "Selected data operation failed", error)
                }
            }
            try {
                if (request.types.any { it == ClearDataType.WEBSITE_DATA || it == ClearDataType.COOKIES }) {
                    if (current in targets) resetPageForDataRemoval()
                }
                targets.forEach { target ->
                    if (ClearDataType.WEBSITE_DATA in request.types) attempt { dataCleaner.clearWebsiteData(target) }
                    else if (ClearDataType.COOKIES in request.types) attempt { dataCleaner.clearCookies(target) }
                    if (ClearDataType.PERMISSIONS in request.types) attempt {
                        try {
                            (if (target == DataProfile.NORMAL) normalSites else checkNotNull(privateSites)).clearPermissions()
                        } catch (error: IllegalStateException) {
                            // An unreadable store keeps its original bytes; point the user at the repair flow.
                            if (normalSites.needsRepair.value) siteStorageUnreadable = true
                            throw error
                        }
                    }
                }
                if (ClearDataType.HISTORY in request.types) attempt {
                    withContext(Dispatchers.IO) {
                        historyManager.clearSince(request.historyPeriod.durationMs?.let { System.currentTimeMillis() - it } ?: 0)
                    }
                    historyLibrary.refresh()
                }
                showClearData = false
                toast(getString(when {
                    siteStorageUnreadable -> R.string.clear_data_site_storage_unreadable
                    failed -> R.string.clear_data_failed
                    else -> R.string.clear_data_done
                }))
            } finally { clearingData = false }
        }
    }

    private fun resetPageForDataRemoval() {
        readyWebViewTabId = null
        clearResidentTabs()
        downloadRequests.resetTransientState()
        cancelWebsitePermissions()
        cancelPendingSslError()
        currentCertificateError = false
        leaveFullscreen()
        val old = webView
        removeMediaPlaybackTracker(old)
        pool.discard(old)
        tabManager.currentTab?.apply { savedState = null; url = ABOUT_BLANK; title = "" }
        webViewOrNull = acquireFreshPage()
        state.pageFailure = null
        state.onPageStarted(ABOUT_BLANK)
        loadPage(ABOUT_BLANK)
    }

    private fun confirmClearSite(origin: String) {
        val profile = ClearScope.CURRENT.targets(privacy.isIncognito, privacy.hasRealIsolation).single()
        val epoch = permissionEpoch
        val domain = origin.toUri().host?.let(UrlUtils::registrableDomain) ?: origin
        dialogs.confirm(this, getString(R.string.clear_this_site), getString(
            if (dataCleaner.supportsCompleteDeletion) R.string.clear_site_message else R.string.clear_site_legacy, domain),
            positiveText = getString(R.string.action_clear)) { confirmed ->
            if (confirmed && epoch == permissionEpoch && !clearingData) {
                clearingData = true
                lifecycleScope.launch {
                    try {
                        val currentHost = state.currentUrl.toUri().host
                        if (SiteOrigin.of(state.currentUrl) == origin ||
                            (dataCleaner.supportsCompleteDeletion && currentHost?.let(UrlUtils::registrableDomain) == domain)) resetPageForDataRemoval()
                        dataCleaner.clearSite(profile, origin)
                        showSiteOrigin = null
                        toast(getString(R.string.clear_data_done))
                    } catch (error: Exception) {
                        if (!isActive) throw error
                        toast(getString(R.string.clear_data_failed))
                    } finally { clearingData = false }
                }
            }
        }
    }

    // --- BrowserWebViewClient.Listener ---

    override fun isCurrentWebView(view: WebView): Boolean = webViewOrNull === view

    override fun onMainFrameNavigation(url: String) {
        state.onNavigationRequested(url)
        cancelWebsitePermissions()
        applySiteSettings(webView, url)
    }

    override fun onPageStarted(url: String) {
        systemMedia.detach(this)
        viewOwnerId = tabManager.currentTab?.id
        cancelWebsitePermissions()
        applySiteSettings(webView, url)
        cancelPendingSslError()
        currentCertificateError = false
        readyWebViewTabId = null
        dismissPageContext()
        leaveFullscreen()
        rememberedVideo = null
        workerDocument = documentFor(url)
        state.onPageStarted(url)
        networkLogs.beginPage(url)
        consoleLogs.clear()
        mediaProbeJob?.cancel()
        webViewOrNull?.let { mediaTrackers.trackerOf(it)?.reset() }
        // Per-page counters. Both are scoped to the document, so a new main frame resets
        // them; subresources of the same page keep accumulating.
        media.clear()
        filter.resetPageCount()
        // A new document invalidates the old one's download answers: pending
        // confirmations and per-page rejections die here, confirmed tasks keep running.
        viewOwnerId?.let { downloadRequests.selectTab(it); downloadRequests.startDocument(it) }
        // Update current tab URL
        tabManager.currentTab?.url = url
        tabManager.notifyChanged()
        refreshBookmarkStatus(url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.scheduleSaveMetadata(this, NORMAL_TABS_PREFS)
        }
    }

    override fun onPageCommitVisible(url: String) = state.onPageCommitVisible(url)

    override fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (url != webView.url || url != state.currentUrl) return
        readyWebViewTabId = tabManager.currentTab?.id
        workerDocument = documentFor(url)
        val resetHistory = clearHistoryOnNextFinish
        if (resetHistory) {
            webView.clearHistory()
            clearHistoryOnNextFinish = false
        }
        state.onPageFinished(url, !resetHistory && canGoBack, !resetHistory && canGoForward)
        state.onTitleChanged(webView.title)
        networkLogs.markMainFrameFinished(url)
        if (state.isDesktopMode) WebViewConfig.applyDesktopViewport(webView, activeSites.get(url).desktopWidth)
        // Update current tab URL and title
        tabManager.currentTab?.let { tab ->
            tab.url = url
            tab.title = webView.title ?: url
            tabManager.notifyChanged()
        }
        // Add to history (only in normal mode)
        addToHistory(url, webView.title ?: url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.scheduleSaveMetadata(this, NORMAL_TABS_PREFS)
        }

        // Detect the active media element for cast preference. The document-start tracker
        // handles cross-origin iframes; older providers use the one-shot polling fallback.
        startPlayingVideoDetection()
        scriptRuntimes[webView]?.onPageFinished(url)
        val target = webView
        val documentEpoch = permissionEpoch
        lifecycleScope.launch(Dispatchers.Default) {
            val css = filter.cosmeticCss(url, workerDocument.let { it.url == url && it.filtering })
            withContext(Dispatchers.Main) {
                if (permissionEpoch == documentEpoch && webViewOrNull === target && target.url == url) {
                    target.evaluateJavascript(
                        "(function(){var s=document.getElementById('__pureAdStyle');if(!s){s=document.createElement('style');s.id='__pureAdStyle';(document.head||document.documentElement).appendChild(s);}s.textContent=" +
                            org.json.JSONObject.quote(css) + ";})();", null)
                }
            }
        }
    }

    override fun onHistoryUpdated(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        workerDocument = documentFor(url)
        state.onHistoryUpdated(url, canGoBack, canGoForward)
        tabManager.currentTab?.url = url
        tabManager.notifyChanged()
        refreshBookmarkStatus(url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.scheduleSaveMetadata(this, NORMAL_TABS_PREFS)
        }
    }

    private fun startPlayingVideoDetection() {
        mediaProbeJob?.cancel()
        val view = webViewOrNull ?: return
        val tracker = mediaTrackers.trackerOf(view) ?: return

        // Probe once after the load even when the bridge is available. This covers a player
        // that was already playing before its first event listener was attached.
        tracker.probe()
        if (tracker.isInstalled) return

        val pageUrl = state.currentUrl
        mediaProbeJob = lifecycleScope.launch {
            while (isActive && webViewOrNull === view && state.currentUrl == pageUrl) {
                if (fullscreenView == null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.probe()
                delay(MEDIA_PROBE_INTERVAL_MS)
            }
        }
    }

    override fun onPageError(url: String, code: Int, description: String) {
        if (url != state.currentUrl) return
        state.onPageError()
        networkLogs.markMainFrameError(url, code, description)
        // ERROR_UNKNOWN with an empty description is what a cancelled navigation looks
        // like; a toast for it would fire on every fast tap.
        if (description.isNotEmpty()) state.pageFailure = PageFailure(url, PageFailureKind.NETWORK, "$code: $description")
    }

    override fun onExternalUrl(url: String): Boolean {
        return onExternalNavigation(url, hasGesture = true, isMainFrame = true)
    }

    override fun onExternalNavigation(url: String, hasGesture: Boolean, isMainFrame: Boolean): Boolean {
        if (!UrlUtils.isAllowedExternalScheme(url)) return true
        val origin = SiteOrigin.of(state.currentUrl)
        when (externalGate.decide(activeSites.get(state.currentUrl).externalApps, hasGesture, isMainFrame,
            externalPrompt != null, SystemClock.elapsedRealtime())) {
            ExternalNavigationGate.Decision.OPEN -> launchExternalUrl(url)
            ExternalNavigationGate.Decision.ASK -> externalPrompt = ExternalPrompt(url, origin, permissionEpoch)
            ExternalNavigationGate.Decision.BLOCK -> Unit
        }
        return true
    }

    private fun launchExternalUrl(url: String) {
        when (val result = ExternalIntentHandler.handle(this, url)) {
            ExternalIntentHandler.Result.Launched -> Unit
            ExternalIntentHandler.Result.Rejected -> Unit
            is ExternalIntentHandler.Result.Fallback -> loadPage(result.url)
        }
        // Always true: the URL is consumed here either way, and returning false would let
        // the WebView also try to load a scheme it cannot handle.
    }

    private fun retryFailedPage() {
        val failed = state.pageFailure ?: return reloadPage()
        rendererRecovery.retry(tabManager.currentTab?.id.orEmpty())
        // Recovery remains visible until this attempt commits a successful document.
        loadPage(failed.url)
    }

    private fun showBackHistory() {
        val history = webView.copyBackForwardList()
        backHistoryEpoch = permissionEpoch
        backHistory = (history.currentIndex - 1 downTo 0).take(64).map { index ->
            val item = history.getItemAtIndex(index)
            BackHistoryEntry(index - history.currentIndex, item.title.orEmpty(), item.url.orEmpty())
        }
    }

    override fun onSslError(handler: SslErrorHandler, error: SslError, url: String?) {
        // Keep the handler pending until the user makes an explicit choice. A previous
        // request cannot remain alive when navigation races to a second certificate error.
        pendingSSLError?.first?.cancel()
        val failedUrl = url.orEmpty()
        if (failedUrl == state.currentUrl) currentCertificateError = true
        pendingSSLError = handler to failedUrl
        runOnUiThread { showSSLErrorDialog = true }
    }

    private fun cancelPendingSslError() {
        pendingSSLError?.first?.let { runCatching { it.cancel() } }
        pendingSSLError = null
        showSSLErrorDialog = false
    }

    override fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String) {
        dialogs.credentials(
            this,
            host,
            realm,
            onResult = { username, password -> runCatching { handler.proceed(username, password) } },
            onCancel = { runCatching { handler.cancel() } },
        )
    }

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        // Never silently continue a known phishing/malware URL. The WebView callback is
        // answered immediately so Chromium can show its own interstitial and the app does
        // not hold a network thread while a Compose surface is being created.
        runCatching { callback.backToSafety(true) }
        toast(getString(R.string.ui_unsafe_webpage_blocked))
    }

    override fun onRenderProcessGone(webView: WebView, crashed: Boolean) {
        if (isDestroyed) {
            // Disposal is idempotent, including callbacks after this host was closed.
            removeMediaPlaybackTracker(webView)
            pool.discard(webView)
            return
        }
        // A late callback from a view that has already been detached must not replace the
        // currently visible tab. It is still safe to discard that corpse.
        if (webViewOrNull !== webView) {
            popupPages.idOf { it.view === webView }?.let { popupPages.remove(it)?.alive = false }
            recentViews.removeWhere { it.view === webView }
            removeMediaPlaybackTracker(webView)
            pool.discard(webView)
            return
        }
        // The instance is dead. Replacing the state value is the whole fix: it recomposes
        // BrowserScreen, whose holder reconciliation detaches the corpse and attaches the
        // replacement.
        val lastUrl = state.currentUrl
        popupPages.idOf { it.view === webView }?.let { popupPages.remove(it)?.alive = false }
        readyWebViewTabId = null
        cancelWebsitePermissions()
        leaveFullscreen()
        removeMediaPlaybackTracker(webView)
        pool.discard(webView)
        webViewOrNull = acquireFreshPage()
        if (lastUrl != ABOUT_BLANK && rendererRecovery.shouldReload(tabManager.currentTab?.id.orEmpty(), SystemClock.elapsedRealtime())) {
            loadPage(lastUrl)
        } else if (lastUrl != ABOUT_BLANK) {
            state.onPageError()
            state.pageFailure = PageFailure(lastUrl, PageFailureKind.RENDERER)
        }
        toast(if (crashed) getString(R.string.ui_the_page_process_crashed_and_was_restarted) else getString(R.string.ui_the_system_closed_the_page_process_it_was))
    }

    /**
     * Runs on a Chromium worker thread, once per subresource, and blocks that request until
     * it returns — so everything here is allocation-light and lock-free. Both consumers are
     * built for that: the filter is an immutable index behind a read lock, and the store
     * takes a snapshot-state write that Compose is happy to receive off-thread.
     */
    override fun onInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val mediaGeneration = media.pageGeneration
        val document = workerDocument
        val resourceType = classifyResourceType(request)
        val requestId = networkLogs.recordRequest(request, document.url, resourceType)
        val documentUrl = document.url

        if (filter.shouldBlock(request, documentUrl, document.filtering, resourceType)) {
            networkLogs.markBlocked(requestId)
            return BLOCKED_RESPONSE
        }

        MediaSniffer.inspect(request, documentUrl)?.let { media.add(it, mediaGeneration) }
        return null
    }

    override fun onNetworkHttpError(
        request: WebResourceRequest,
        statusCode: Int,
        reasonPhrase: String?,
        responseHeaders: Map<String, String>?,
    ) {
        networkLogs.markHttpError(request, statusCode, reasonPhrase, responseHeaders)
    }

    override fun onNetworkResourceError(
        request: WebResourceRequest,
        code: Int,
        description: String,
    ) {
        networkLogs.markResourceError(request, code, description)
    }

    // --- BrowserChromeClient.Listener ---

    override fun onProgressChanged(progress: Int) = state.onProgressChanged(progress)

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val level = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> ConsoleLogLevel.ERROR
            ConsoleMessage.MessageLevel.WARNING -> ConsoleLogLevel.WARNING
            ConsoleMessage.MessageLevel.DEBUG -> ConsoleLogLevel.DEBUG
            ConsoleMessage.MessageLevel.TIP -> ConsoleLogLevel.TIP
            else -> ConsoleLogLevel.LOG
        }
        consoleLogs.add(
            level = level,
            message = message.message(),
            sourceId = message.sourceId(),
            lineNumber = message.lineNumber(),
        )
        return true
    }

    override fun onTitleChanged(title: String?) {
        state.onTitleChanged(title)
        // Update current tab title
        tabManager.currentTab?.title = title ?: webView.url ?: ""
        tabManager.notifyChanged()
    }

    override fun onIconChanged(icon: Bitmap?) {
        // Update current tab favicon
        tabManager.currentTab?.let { tab ->
            // Chromium and pending Compose frames may still hold the previous bitmap.
            tab.favicon = icon
            tabManager.notifyChanged()
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        websitePermissions.cancel(request)
    }

    override fun onGeolocationRequestCanceled() { websitePermissions.cancel("geolocation") }

    private fun rememberPlaybackSpeed(speed: Float) {
        if (!browserPreferences.video.rememberSpeed) return
        browserPreferences = preferencesRepository.save(browserPreferences.copy(
            video = browserPreferences.video.copy(preferredSpeed = speed),
        ))
    }

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val tracker = webViewOrNull?.let(mediaTrackers::trackerOf)
        if (fullscreenView != null || tracker == null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenCallback = callback
        val host = FullscreenVideoView(
            activity = this,
            videoView = view,
            preferences = browserPreferences.video,
            enhancedPlayback = activeSites.get(state.currentUrl).useEnhancedPlayback(browserPreferences.video.enhancedControls),
            tracker = tracker,
            titleProvider = { state.pageTitle ?: getString(R.string.ui_video_playback) },
            canCast = { media.count > 0 },
            onExit = ::leaveFullscreen,
            onChooseSpeed = ::applyPlaybackSpeed,
            castContent = { CurrentCastPicker(onDismiss = {}, embedded = true) },
            onPictureInPicture = if (pipController.isAvailable) ({ pipController.enter(); Unit }) else null,
        )
        fullscreenView = host
        // DecorView manages its own children during PiP/window resize. Keep the
        // video in the Activity content container so it is measured with the new
        // bounds instead of retaining an off-screen fullscreen surface.
        findViewById<ViewGroup>(android.R.id.content).addView(host, ViewGroup.LayoutParams(-1, -1))
        host.requestFocus()
        applyWindowInsetsPolicy()
        pipController.update()
    }

    private fun leaveFullscreen() {
        val callback = fullscreenCallback
        onExitFullscreen()
        runCatching { callback?.onCustomViewHidden() }
    }

    override fun onExitFullscreen() {
        val host = fullscreenView ?: return
        fullscreenView = null
        fullscreenCallback = null
        host.release()
        (host.parent as? ViewGroup)?.removeView(host)
        // Returning from video fullscreen must land back in whatever mode was active
        // before it — browser fullscreen included — instead of unconditionally showing bars.
        applyWindowInsetsPolicy()
        if (::pipController.isInitialized) pipController.update()
    }

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>?>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = handleFileChooser(callback, params)

    override fun onPermissionRequest(request: PermissionRequest) {
        handlePermissionRequest(request)
    }

    override fun onGeolocationRequest(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        handleGeolocationRequest(origin, callback)
    }

    override fun onCreateWindow(isDialog: Boolean, isUserGesture: Boolean): WebView? =
        createPopupTab(isUserGesture)

    override fun onPopupContentsAttached(view: WebView, opener: WebView) {
        try {
            if (!isDestroyed && !isFinishing && pool.owns(view)) {
                mediaTrackers.trackerOf(view)?.onPopupContentsAttached()
                scriptRuntimes[view]?.onPopupContentsAttached()
            }
        } finally {
            pendingPopupTransfers.remove(view)?.close()
        }
    }

    override fun onBackgroundTitleChanged(view: WebView, title: String?) {
        val id = popupPages.idOf { it.alive && it.view === view } ?: return
        tabManager.tabs.firstOrNull { it.id == id }?.let {
            it.title = title ?: it.url
            tabManager.notifyChanged()
        }
    }

    override fun onCloseWindow(window: WebView) {
        if (window === webViewOrNull) onCloseWindow()
        else {
            val id = popupPages.idOf { it.alive && it.view === window } ?: return
            closeTab(tabManager.tabs.indexOfFirst { it.id == id })
        }
    }

    override fun onCloseWindow() {
        if (tabManager.count > 1) closeTab(tabManager.currentIndex)
    }

    override fun onJsDialog(
        type: BrowserChromeClient.JsDialogType,
        origin: String,
        message: String,
        defaultValue: String?,
        result: JsResult,
    ): Boolean = handleJsDialog(type, origin, message, defaultValue, result)

    // --- Navigation ---

    private fun loadPage(url: String) {
        if (awaitPrivacyReady { loadPage(url) }) return
        state.onNavigationRequested(url)
        webView.loadUrl(url)
    }

    private fun reloadPage() {
        if (awaitPrivacyReady(::reloadPage)) return
        state.onNavigationRequested(webView.url ?: state.currentUrl)
        webView.reload()
    }

    private fun navigateHistory(offset: Int) {
        if (awaitPrivacyReady { navigateHistory(offset) }) return
        if (!webView.canGoBackOrForward(offset)) return
        val history = webView.copyBackForwardList()
        val target = history.getItemAtIndex(history.currentIndex + offset) ?: return
        state.onNavigationRequested(target.url)
        webView.goBackOrForward(offset)
    }

    private fun navigate(input: String) {
        if (awaitPrivacyReady { navigate(input) }) return
        if (awaitScriptsReady { navigate(input) }) return
        var tabUrl: String? = null
        when (val target = NavigationPolicy.resolve(input, searchEngine)) {
            is NavigationTarget.Load -> {
                if (onUserScriptUrl(target.url)) return
                prepareForNavigation()
                applySiteSettings(webView, target.url)
                loadPage(target.url)
                tabUrl = target.url
            }
            is NavigationTarget.External -> onExternalUrl(target.url)
        }
        // Update the current tab's URL
        tabUrl?.let {
            tabManager.currentTab?.url = it
            tabManager.notifyChanged()
        }
    }

    private fun goHome() {
        navigate(if (homepageMode == HomepageMode.NAVIGATION) ABOUT_BLANK else homeUrl)
    }

    private var privacyNavigationJob: Job? = null

    /** Every load/restore waits, including a new Activity or window during old-session cleanup. */
    private fun awaitPrivacyReady(action: () -> Unit): Boolean {
        if (!privacy.isTransitioning && !sessionState.pendingPrivateStart) return false
        privacyNavigationJob?.cancel()
        privacyNavigationJob = lifecycleScope.launch {
            privacy.awaitReady()
            if (isFinishing || isDestroyed) return@launch
            if (sessionState.pendingPrivateStart) {
                sessionState.pendingPrivateStart = false
                performIncognitoSwitch(true)
            }
            action()
        }
        return true
    }

    /** First navigation waits for local script metadata so document-start is deterministic. */
    private fun awaitScriptsReady(action: () -> Unit): Boolean {
        if (userScripts.initialized && filter.isReady) {
            webViewOrNull?.let { scriptRuntimes[it]?.refresh() }
            return false
        }
        scriptNavigationJob?.cancel()
        scriptNavigationJob = lifecycleScope.launch {
            userScripts.initialize()
            customFilter.initialize()
            filter.awaitReady()
            webViewOrNull?.let { scriptRuntimes[it]?.refresh() }
            action()
        }
        return true
    }

    override fun onUserScriptUrl(url: String): Boolean {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        if (uri.scheme !in listOf("http", "https") || uri.path?.endsWith(".user.js", true) != true) return false
        scriptImportUrl = url
        showUserScripts = true
        return true
    }

    private fun isHomeDocument(url: String): Boolean =
        url == ABOUT_BLANK || (homepageMode == HomepageMode.FIXED_URL && url == homeUrl)

    /** Clears page-scoped work before a new URL starts, closing the old-request race window. */
    private fun prepareForNavigation() {
        cancelWebsitePermissions()
        cancelPendingSslError()
        dialogs.dismiss()
        dismissPageContext()
        state.revealToolbar()
        mediaProbeJob?.cancel()
        mediaProbeJob = null
        media.clear()
        webViewOrNull?.let { view ->
            mediaTrackers.trackerOf(view)?.reset()
            view.stopLoading()
        }
    }

    // --- Intents ---

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * VIEW intents are how we act as the default browser. On the initial launch with no
     * URL we load the home page; on a later intent with no URL we leave the current page
     * alone, because that is a task switch rather than a request to navigate.
     */
    private fun handleIntent(intent: Intent) {
        if (intent.action == com.mybrowser.download.DownloadTransferService.ACTION_SHOW_DOWNLOAD) {
            val id = intent.getLongExtra(com.mybrowser.download.DownloadTransferService.EXTRA_DOWNLOAD_ID, 0L)
            downloadFocusId = id.takeIf { it != 0L }
            openSheet(Sheet.DOWNLOADS)
            return
        }
        val url = intentNavigationText(intent)
        when {
            !url.isNullOrBlank() -> {
                // A link handed in by another app is a navigation request, not a request to
                // keep whatever bottom sheet was open. Close transient surfaces first so the
                // destination page is immediately visible (and cannot be covered by a stale
                // bookmark/cast sheet).
                sheetNavigation.clear()
                showFilterSettings = false
                showUserScripts = false
                showSiteOrigin = null
                showManagedSites = false
                showSecurityDialog = false
                bookmarkDocuments.dismissPreview()
                scriptImportUrl = null
                bookmarkDraft = null
                navigate(url)
            }
        }
    }

    private fun intentNavigationText(intent: Intent): String? = when {
        intent.action == Intent.ACTION_VIEW -> intent.dataString
        intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    // --- Back handling ---

    // This is Activity's public input hook. AndroidX marks its forwarding override
    // restricted, but dispatch must run before Chromium consumes browser shortcuts.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Chromium can consume a Back key before our full-screen view sees it.
        // The current activity window owns this key; modal dialogs have their own window.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && fullscreenView != null) {
            return fullscreenView!!.dispatchKeyEvent(event)
        }
        // Hardware keyboard: F11 toggles browser fullscreen; Esc walks the overlay
        // priority like back but never steps page history.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_F11 -> {
                    setBrowserFullscreenEnabled(!browserPreferences.browserFullscreenEnabled)
                    return true
                }
                android.view.KeyEvent.KEYCODE_ESCAPE -> if (handleEscapeKey()) return true
            }
        }
        // Chromium consumes some Ctrl combinations before Activity.onKeyShortcut.
        // Browser commands own the key-down; ordinary text keys still reach the page.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.isCtrlPressed && event.keyCode in setOf(android.view.KeyEvent.KEYCODE_L,
                android.view.KeyEvent.KEYCODE_T, android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_R,
                android.view.KeyEvent.KEYCODE_F, android.view.KeyEvent.KEYCODE_TAB)) {
            return onKeyShortcut(event.keyCode, event)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Esc walks the same overlay priority as the back gesture — video fullscreen, sheet,
     * find bar, omnibar editing, browser fullscreen — but stops there: it never
     * navigates page history or triggers the exit confirmation.
     */
    private fun handleEscapeKey(): Boolean {
        when {
            fullscreenView != null -> {
                if (fullscreenView?.handleBack() != true) leaveFullscreen()
                return true
            }
            sheetNavigation.current != null -> {
                dismissSheet(sheetNavigation.current!!)
                return true
            }
            state.isFindBarVisible -> {
                webView.clearMatches()
                state.hideFindBar()
                return true
            }
            state.isOmnibarFocused -> {
                // Reuse the back chain so Compose clears focus and hides the keyboard
                // properly instead of only flipping the state flag.
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            isBrowserFullscreenImmersive -> {
                setBrowserFullscreenEnabled(false)
                return true
            }
            else -> return false
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_L -> { state.revealToolbar(); state.onOmnibarFocusChange(true) }
                android.view.KeyEvent.KEYCODE_T -> createNewTab()
                android.view.KeyEvent.KEYCODE_W -> closeTab(tabManager.currentIndex)
                android.view.KeyEvent.KEYCODE_R -> if (state.pageFailure != null) retryFailedPage() else reloadPage()
                android.view.KeyEvent.KEYCODE_F -> state.showFindBar()
                android.view.KeyEvent.KEYCODE_TAB -> switchToTab((tabManager.currentIndex + (if (event.isShiftPressed) -1 else 1) + tabManager.count) % tabManager.count)
                else -> return super.onKeyShortcut(keyCode, event)
            }
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /**
     * The cases Compose cannot own, in priority order. Omnibar focus is handled by a
     * BackHandler inside the composition, which registers later and therefore runs first.
     */
    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> {
                        if (fullscreenView?.handleBack() != true) leaveFullscreen()
                    }
                    sheetNavigation.current != null -> dismissSheet(sheetNavigation.current!!)
                    // Every overlay is closed and the chrome is hidden: back is the user's
                    // way out of immersion, not a page-history step.
                    isBrowserFullscreenImmersive -> setBrowserFullscreenEnabled(false)
                    navigateBackAcrossTabs() -> Unit
                    else -> confirmExit()
                }
            }
        })
    }

    /** A child tab is not a root task: exhaust its own history before closing it. */
    private fun navigateBackAcrossTabs(): Boolean {
        if (webView.canGoBack()) {
            exitConfirmation.reset()
            navigateHistory(-1)
            return true
        }
        if (tabManager.count > 1) {
            closeTab(tabManager.currentIndex)
            return true
        }
        return false
    }

    private fun confirmExit() {
        if (exitConfirmation.onBack(SystemClock.uptimeMillis())) {
            exitBrowser()
        } else {
            toast(getString(R.string.press_back_again))
        }
    }

    private fun openSheet(destination: Sheet) {
        exitConfirmation.reset()
        sheetNavigation.open(destination)
    }

    private fun dismissSheet(owner: BrowserSheetNavigation.Presentation) {
        if (sheetNavigation.back(owner)) exitConfirmation.reset()
    }

    private fun sheetAction(owner: BrowserSheetNavigation.Presentation, action: () -> Unit) {
        if (sheetNavigation.close(owner)) {
            exitConfirmation.reset()
            action()
        }
    }

    // --- System bars ---

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Immersive and nothing revealed on top: back leaves fullscreen instead of history. */
    private val isBrowserFullscreenImmersive: Boolean
        get() = browserPreferences.browserFullscreenEnabled && !state.isChromeRevealed &&
            !state.isOmnibarFocused && !state.isFindBarVisible

    /**
     * The one place that decides system-bar visibility, so no branch calls show/hide on
     * its own. Video fullscreen keeps its own immersive policy; browser fullscreen hides
     * the bars only while its chrome is fully hidden, so a revealed omnibar or find bar
     * brings them back, and any native sheet showing keeps them visible. In PiP the
     * system owns the window and the activity must not fight it.
     */
    private fun applyWindowInsetsPolicy() {
        if (isFinishing || isDestroyed || isInPictureInPictureMode) return
        setSystemBarsVisible(shouldShowSystemBars())
    }

    private fun shouldShowSystemBars(): Boolean {
        if (fullscreenView != null) return false
        if (!browserPreferences.browserFullscreenEnabled) return true
        if (sheetNavigation.current != null) return true
        if (state.isChromeRevealed || state.isOmnibarFocused || state.isFindBarVisible) return true
        return false
    }

    // --- Lifecycle ---

    override fun setPlaying(playing: Boolean) {
        if (playing && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
            !isInPictureInPictureMode && (privacy.isIncognito || !browserPreferences.video.backgroundPlayback)) return
        webViewOrNull?.let { view ->
            (view as? com.mybrowser.core.BrowserWebView)?.keepMediaOnWindowHidden =
                playing && browserPreferences.video.backgroundPlayback && !privacy.isIncognito
            if (playing) mediaTrackers.trackerOf(view)?.setSuspended(false)
            if (playing) mediaTrackers.trackerOf(view)?.setPlaying(true) else mediaTrackers.trackerOf(view)?.pauseAll()
        }
    }

    override fun seek(positionMs: Long) { webViewOrNull?.let { mediaTrackers.trackerOf(it)?.seekTo(positionMs / 1000.0) } }
    override fun openIntent(): Intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun onUserLeaveHint() {
        if (android.os.Build.VERSION.SDK_INT < 31 && ::pipController.isInitialized && browserPreferences.video.automaticPip &&
            webViewOrNull?.let { mediaTrackers.trackerOf(it)?.current?.isPlaying } == true) pipController.enter()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(active: Boolean, configuration: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(active, configuration)
        if (::pipController.isInitialized) pipController.modeChanged(active)
        if (active) pictureInPictureSession = true
        else if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            pictureInPictureSession = false
        } else if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            stopHiddenMedia()
            leaveFullscreen()
        }
    }

    private fun stopHiddenMedia() {
        webViewOrNull?.let { mediaTrackers.trackerOf(it)?.setSuspended(true); it.onPause() }
        systemMedia.detach(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Dialog windows can clear immersive flags on older Android versions. Only the
            // video host may steal focus here; browser fullscreen must never grab it away
            // from the WebView or a half-typed omnibar draft.
            fullscreenView?.requestFocus()
            applyWindowInsetsPolicy()
        }
    }

    private fun requestDefaultBrowser() {
        runCatching {
            defaultBrowserLauncher.launch(DefaultBrowser.requestIntent(this))
        }.recoverCatching {
            defaultBrowserLauncher.launch(DefaultBrowser.settingsIntent())
        }.onFailure { toast(getString(R.string.ui_unable_to_open_default_app_settings)) }
    }

    private fun persistNormalSession() {
        if (!::normalTabManager.isInitialized) return
        if (!privacy.isIncognito && readyWebViewTabId == normalTabManager.currentTab?.id) {
            webViewOrNull?.let { normalTabManager.saveCurrentState(it) }
        }
        if (restoreLastSession) normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
        else normalTabManager.clearMetadata(this, NORMAL_TABS_PREFS)
    }

    private fun exitBrowser() {
        sheetNavigation.clear()
        persistNormalSession()
        webViewOrNull?.let {
            it.stopLoading()
            it.onPause()
        }
        // After process death a system picker can be the task's surviving root.
        // Finish only our current AppTask so Exit also removes that stale child UI.
        val currentTask = runCatching {
            getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo?.taskId == taskId }
        }.getOrNull()
        if (currentTask == null || runCatching { currentTask.finishAndRemoveTask() }.isFailure) {
            finishAndRemoveTask()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistNormalSession()
        outState.putBundle(STATE_NORMAL_TABS, normalTabManager.snapshotMetadata())
        outState.putString(STATE_PROCESS_SESSION, PROCESS_SESSION)
        outState.putStringArrayList(STATE_SHEET_ROUTES, ArrayList(sheetNavigation.routes.map { it.destination.name }))
        outState.putLongArray(STATE_SHEET_KEYS, sheetNavigation.routes.map { it.key }.toLongArray())
        outState.putBoolean(STATE_FILTER_SETTINGS_OPEN, showFilterSettings)
        outState.putBoolean(STATE_USER_SCRIPTS_OPEN, showUserScripts)
        outState.putBoolean(STATE_MANAGED_SITES_OPEN, showManagedSites)
        outState.putString(STATE_SITE_ORIGIN, showSiteOrigin)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        persistNormalSession()
        // A visible PiP Activity stays STARTED. Stopping it means the user closed
        // or hid that player; background-playback preference must not revive it.
        if (::privacy.isInitialized && (pictureInPictureSession ||
                (!isInPictureInPictureMode && (privacy.isIncognito || !browserPreferences.video.backgroundPlayback)))) {
            stopHiddenMedia()
            if (pictureInPictureSession) leaveFullscreen()
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::pool.isInitialized) return
        // Retention follows the tab count, so pressure is what bounds it. Each signal frees the
        // page parked longest ago rather than all of them: an ordinary window switch should cost
        // one page, not the whole session. Only the last level before the process is killed drops
        // the rest, where keeping them would not survive anyway. UI_HIDDEN is "no longer visible",
        // not "short on memory". Live popup groups are not eligible for ordinary eviction.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> releaseBackgroundWindowsForMemory()
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Unit
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> evictOldestParkedPage()
        }
    }

    /** Extreme pressure may end window communication, but keeps tabs/URLs recoverable. */
    private fun releaseBackgroundWindowsForMemory() {
        recentViews.clear()
        val released = popupPages.clear()
        released.forEach { page ->
            page.alive = false
            if (page.view !== webViewOrNull) {
                // Do not allocate a large WebView state bundle while under memory pressure.
                tabManager.tabs.firstOrNull { it.id == page.tabId }?.let { tab ->
                    tab.url = page.document.url
                    tab.savedState = null
                }
                downloadRequests.startDocument(page.tabId)
                disposePopupPage(page)
            }
        }
        if (released.any { it.view !== webViewOrNull }) {
            tabManager.notifyChanged()
            persistNormalSession()
        }
    }

    override fun onPause() {
        cast.setVisible(false)
        dismissPageContext()
        fullscreenView?.cancelTransientControls()
        super.onPause()
        // onStop applies the explicit media/background policy; Chromium owns its timers.
    }

    override fun onResume() {
        if (::preferencesRepository.isInitialized) browserPreferences = loadBrowserPreferences()
        cast.setVisible(sheet == Sheet.CAST)
        super.onResume()
        if (!isInPictureInPictureMode) pictureInPictureSession = false
        isDefaultBrowser = DefaultBrowser.isDefault(this)
        // Returning from the system permission dialog or notification pages must be
        // reflected by the settings row without re-asking for anything.
        if (::preferencesRepository.isInitialized) {
            downloadNotificationsEnabled = com.mybrowser.download.DownloadNotificationGuide.isEnabled(this)
        }
        downloadHandler.resumeInterrupted()
        webViewOrNull?.onResume()
        webViewOrNull?.let { mediaTrackers.trackerOf(it)?.setSuspended(false) }
    }

    override fun onDestroy() {
        if (isChangingConfigurations && ::privacy.isInitialized && privacy.isIncognito) saveCurrentTab()
        dialogs.dismiss()
        if (::pipController.isInitialized) pipController.close()
        systemMedia.detach(this)
        clearResidentTabs()
        cast.setVisible(false)
        cast.cancel()
        dismissPageContext()
        mediaProbeJob?.cancel()
        mediaProbeJob = null
        leaveFullscreen()
        if (::fileChooser.isInitialized) fileChooser.close()
        cancelWebsitePermissions()
        pendingSSLError?.first?.cancel()
        pendingSSLError = null
        showSSLErrorDialog = false

        // Save current tab state before destroying
        persistNormalSession()

        // The pool defers native teardown only until pending Chromium transports finish.
        webViewOrNull?.let(::removeMediaPlaybackTracker)
        if (::pool.isInitialized) pool.close()
        webViewOrNull = null

        // ViewModel clears tab resources and private storage only when this window ends.
        if (::bookmarkManager.isInitialized) bookmarkManager.close()
        if (::historyManager.isInitialized) historyManager.close()

        super.onDestroy()
    }

    // --- Helpers ---

    // --- Settings import/export (single-file, whitelist only) ---

    private val settingsTransfer: com.mybrowser.backup.SettingsTransfer by lazy {
        val app = application as App
        com.mybrowser.backup.SettingsTransfer(
            this, app.filterController, app.filterSubscriptions, app.siteSettings,
            bookmarkManager, historyManager,
        )
    }

    private fun exportSettings() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        settingsExportLauncher.launch("pure-browser-settings-$stamp.json")
    }

    private fun exportSettingsTo(uri: Uri) {
        lifecycleScope.launch {
            val export = withContext(Dispatchers.IO) {
                runCatching {
                    val version = runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrNull().orEmpty()
                    val result = settingsTransfer.collect(version)
                    val json = com.mybrowser.backup.SettingsBackupCodec.encode(result.backup)
                    // Success is reported only after the stream is closed with the payload in it.
                    val stored = contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                    result.takeIf { stored }
                }.getOrNull()
            }
            if (export == null) {
                toast(getString(R.string.settings_export_failed))
                return@launch
            }
            // Never claim a complete file when the size budget cut the library short.
            val truncated = export.omittedBookmarks > 0 || export.omittedHistory > 0
            toast(
                getString(R.string.settings_exported) +
                    if (truncated) {
                        "\n" + getString(
                            R.string.settings_export_truncated,
                            export.omittedBookmarks, export.omittedHistory,
                        )
                    } else "",
            )
        }
    }

    private fun importSettings() {
        settingsImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    private fun importSettingsFrom(uri: Uri) {
        lifecycleScope.launch {
            val candidate = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        val chunks = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            if (chunks.size() + read > com.mybrowser.backup.SettingsBackupCodec.MAX_FILE_BYTES) {
                                throw IllegalArgumentException("File too large")
                            }
                            chunks.write(chunk, 0, read)
                        }
                        chunks.toString("UTF-8")
                    } ?: throw IllegalArgumentException("Unreadable file")
                    val backup = com.mybrowser.backup.SettingsBackupCodec.decode(text)
                    backup to settingsTransfer.preview(backup)
                }.getOrNull()
            }
            if (candidate == null) {
                toast(getString(R.string.settings_import_invalid))
            } else {
                importCandidate = candidate
            }
        }
    }

    private fun applyImportedSettings() {
        val candidate = importCandidate ?: return
        importCandidate = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { settingsTransfer.apply(candidate.first) }
            // A successful explicit import replaces any session-only exit fallback.
            if ("browser" in result.applied && candidate.first.settings.browser?.browserFullscreenEnabled != null) {
                sessionState.fullscreenPreference.acceptImport()
            }
            // One coherent runtime refresh; the repositories are now the source of truth.
            browserPreferences = loadBrowserPreferences()
            searchEngine = searchEngineManager.getCurrentEngine()
            availableSearchEngines = searchEngineManager.getAvailableEngines()
            homeRepository.loadSettings().let { settings ->
                homepageMode = settings.mode
                homeUrl = settings.fixedUrl
                restoreLastSession = settings.restoreLastSession
            }
            downloadSettings = downloadSettingsRepository.load()
            // An incognito wish change goes through the full existing switch flow.
            if ("browser" in result.applied && browserPreferences.incognitoEnabled != privacy.isIncognito) {
                toggleIncognito()
            }
            if (result.failed.isEmpty()) {
                toast(
                    getString(R.string.settings_imported, result.applied.size) +
                        if (result.pendingFilterUpdates > 0) {
                            "\n" + getString(R.string.settings_import_filter_updates, result.pendingFilterUpdates)
                        } else "",
                )
                // Say how much browsing data actually landed: a merge can add nothing.
                if (result.importedBookmarks > 0 || result.importedHistory > 0) {
                    toast(
                        getString(
                            R.string.settings_import_library_added,
                            result.importedBookmarks, result.importedHistory,
                        ),
                    )
                }
            } else {
                // Name the failing groups: a count alone cannot tell the user what to re-check.
                val names = result.failed.map { groupId ->
                    getString(when (groupId) {
                        "browser" -> R.string.settings_group_browser
                        "home" -> R.string.settings_group_home
                        "search" -> R.string.settings_group_search
                        "downloads" -> R.string.settings_group_downloads
                        "filtering" -> R.string.settings_group_filtering
                        "bookmarks" -> R.string.settings_group_bookmarks
                        "history" -> R.string.settings_group_history
                        else -> R.string.settings_group_sites
                    })
                }.joinToString("、")
                toast(getString(R.string.settings_import_partial, result.applied.size, names))
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(null, text))
        // Android 13+ displays its own clipboard confirmation.
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
        const val EXIT_CONFIRM_WINDOW_MS = 2_000L
        const val NORMAL_TABS_PREFS = "normal_tabs"
        const val STATE_NORMAL_TABS = "normal_tab_snapshot"
        const val STATE_PROCESS_SESSION = "process_session"
        const val STATE_SHEET_ROUTES = "sheet_routes"
        const val STATE_SHEET_KEYS = "sheet_keys"
        const val STATE_FILTER_SETTINGS_OPEN = "filter_settings_open"
        const val STATE_USER_SCRIPTS_OPEN = "user_scripts_open"
        const val STATE_MANAGED_SITES_OPEN = "managed_sites_open"
        const val STATE_SITE_ORIGIN = "site_origin"
        val PROCESS_SESSION = java.util.UUID.randomUUID().toString()
        const val MEDIA_PROBE_INTERVAL_MS = 1_200L

        /**
         * Returned for every blocked request. HTTP 200 with an empty body, not 403: some
         * scripts retry or surface a visible error on a failure status, and an empty OK is
         * what the mainstream blockers settled on for the same reason. A fresh instance per
         * call rather than a shared one: Chromium takes ownership of the stream and closes
         * it, so a single reused response would hand out an already-closed stream.
         */
        val BLOCKED_RESPONSE: WebResourceResponse
            get() = WebResourceResponse(
                "text/plain",
                "utf-8",
                200,
                "OK",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
    }
}
