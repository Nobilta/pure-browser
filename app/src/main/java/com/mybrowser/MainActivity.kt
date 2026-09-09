package com.mybrowser

import com.mybrowser.R
import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mybrowser.core.BrowserChromeClient
import com.mybrowser.core.BrowserWebViewClient
import com.mybrowser.core.ConsoleLogLevel
import com.mybrowser.core.ConsoleLogStore
import com.mybrowser.core.ExternalIntentHandler
import com.mybrowser.core.NetworkLogStore
import com.mybrowser.core.NavigationPolicy
import com.mybrowser.core.NavigationTarget
import com.mybrowser.core.UrlUtils
import com.mybrowser.core.WebViewPool
import com.mybrowser.core.WebViewConfig
import com.mybrowser.core.DetachedWebViewClient
import com.mybrowser.core.DefaultBrowser
import com.mybrowser.core.PageContextTarget
import com.mybrowser.core.PageContextMenuController
import com.mybrowser.dlna.CastController
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.CustomFilterController
import com.mybrowser.media.MediaCandidateStore
import com.mybrowser.media.MediaPlaybackTracker
import com.mybrowser.media.FullscreenVideoView
import com.mybrowser.data.BrowserPreferences
import com.mybrowser.data.BrowserPreferencesRepository
import com.mybrowser.media.PlaybackSpeed
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchEngineManager
import com.mybrowser.search.UrlOrSearch
import com.mybrowser.media.MediaSniffer
import com.mybrowser.privacy.PrivacyMode
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
import com.mybrowser.ui.BrowserScreen
import com.mybrowser.ui.BrowserState
import com.mybrowser.ui.BookmarksSheet
import com.mybrowser.ui.HistorySheet
import com.mybrowser.ui.CastSheet
import com.mybrowser.ui.MenuSheet
import com.mybrowser.ui.PlaybackSpeedSheet
import com.mybrowser.ui.TabsSheet
import com.mybrowser.ui.DownloadsSheet
import com.mybrowser.ui.SettingsSheet
import com.mybrowser.ui.FilterSettingsSheet
import com.mybrowser.ui.BookmarkEditDialog
import com.mybrowser.ui.LibraryPager
import com.mybrowser.ui.PageContextSheet
import com.mybrowser.ui.theme.MyBrowserTheme
import com.mybrowser.ui.Dialogs
import java.io.ByteArrayInputStream
import java.util.WeakHashMap

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
    BrowserChromeClient.Listener {

    private lateinit var pool: WebViewPool
    private lateinit var filter: FilterController
    private lateinit var customFilter: CustomFilterController
    private lateinit var privacy: PrivacyMode

    // Two separate tab managers: one for normal mode, one for incognito.
    // tabManager points to whichever is active.
    private lateinit var normalTabManager: TabManager
    private lateinit var incognitoTabManager: TabManager
    private lateinit var tabManager: TabManager

    // Bookmarks and history managers
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var historyManager: HistoryManager
    private lateinit var downloadHandler: DownloadHandler
    private lateinit var downloadSettingsRepository: DownloadSettingsRepository
    private var downloadSettings by mutableStateOf(DownloadSettings())
    private lateinit var preferencesRepository: BrowserPreferencesRepository
    private var browserPreferences by mutableStateOf(BrowserPreferences())
    private var showFilterSettings by mutableStateOf(false)

    private lateinit var bookmarkLibrary: LibraryPager<Bookmark>
    private lateinit var historyLibrary: LibraryPager<HistoryEntry>
    private var currentPageBookmarked by mutableStateOf(false)

    private val state = BrowserState()

    /** Video candidates for the current page; cleared on every main-frame navigation. */
    private val media = MediaCandidateStore()
    private val mediaTrackers = WeakHashMap<WebView, MediaPlaybackTracker>()
    // Monotonically separates callbacks from a tracker that has just been replaced or
    // detached. WebView can deliver a queued JavaScript message after close(), so checking
    // only the WebView instance is not enough when a pooled instance is reconfigured.
    private var mediaTrackerGeneration = 0L
    private var mediaProbeJob: Job? = null
    private var hasVideo by mutableStateOf(false)
    private var playbackSpeed by mutableFloatStateOf(PlaybackSpeed.DEFAULT)
    private val networkLogs = NetworkLogStore()
    private val consoleLogs = ConsoleLogStore()
    private val cast: CastController by lazy { CastController(this, lifecycleScope) }

    /** Which bottom sheet is up, if any. */
    private var sheet: Sheet? by mutableStateOf(null)

    private enum class Sheet {
        MENU,
        CAST,
        PLAYBACK_SPEED,
        TABS,
        BOOKMARKS,
        HISTORY,
        DOWNLOADS,
        SETTINGS,
        FILTER_SETTINGS,
    }

    /** Non-null while the add-bookmark editor is visible. */
    private var bookmarkDraft: BookmarkDraft? by mutableStateOf(null)

    private data class BookmarkDraft(val title: String, val url: String, val id: Long? = null)

    private var pageContextTarget by mutableStateOf<PageContextTarget?>(null)
    private val pageContextMenu = PageContextMenuController({ it === webViewOrNull }) { target ->
        if (fullscreenView == null) {
            sheet = null
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

    /**
     * A worker-thread-safe copy for shouldInterceptRequest. Reading Compose snapshot state
     * from Chromium's network thread is unnecessary coupling and can observe a stale snapshot;
     * the volatile value is updated alongside the main-frame callbacks instead.
     */
    @Volatile private var documentUrlForWorkers: String = ABOUT_BLANK

    private val webView: WebView
        get() = checkNotNull(webViewOrNull) { "WebView read before onCreate acquired it" }

    /** Fullscreen video state. */
    private var fullscreenView: FullscreenVideoView? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var rememberedVideo: String? = null

    // Security dialog state
    private var showSecurityDialog by mutableStateOf(false)
    private var securityCertificate by mutableStateOf<CertificateDetails?>(null)
    private var showSSLErrorDialog by mutableStateOf(false)
    private var pendingSSLError: Pair<SslErrorHandler, String>? = null

    // WebView callbacks that outlive a single stack frame. They are cleared on replacement
    // and teardown so a page cannot receive a result after its tab has gone away.
    private var pendingFileCallback: ValueCallback<Array<Uri>?>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeoRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var runtimePermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var downloadDirectoryLauncher: ActivityResultLauncher<Uri?>

    // Developer tools state
    private var showDeveloperTools by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerActivityLaunchers()

        // Edge-to-edge is enforced at targetSdk 35+; opting out is deprecated, so we
        // handle insets rather than fight them. Compose applies them per-bar inside
        // BrowserScreen via WindowInsets.safeDrawing.
        enableEdgeToEdge()

        val app = application as App
        pool = app.webViewPool
        filter = app.filterController
        customFilter = CustomFilterController(this, filter)
        privacy = app.privacyMode

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
        normalTabManager = TabManager()
        // A recreated Activity in this process retains its tabs. A fresh process follows
        // the startup preference even when Android retained the task's old saved state.
        val sessionSnapshot = savedInstanceState
            ?.takeIf { it.getString(STATE_PROCESS_SESSION) == PROCESS_SESSION }
            ?.getBundle(STATE_NORMAL_TABS)
        if (savedInstanceState?.getString(STATE_PROCESS_SESSION) == PROCESS_SESSION &&
            savedInstanceState.getBoolean(STATE_SETTINGS_OPEN)
        ) {
            sheet = Sheet.SETTINGS
            showFilterSettings = savedInstanceState.getBoolean(STATE_FILTER_SETTINGS_OPEN)
        }
        if (sessionSnapshot != null) {
            normalTabManager.restoreMetadata(sessionSnapshot)
        } else if (restoreLastSession) {
            normalTabManager.restoreMetadata(this, NORMAL_TABS_PREFS)
        } else {
            normalTabManager.clearMetadata(this, NORMAL_TABS_PREFS)
        }
        incognitoTabManager = TabManager()
        tabManager = normalTabManager

        // Initialize bookmarks and history managers
        bookmarkManager = BookmarkManager(this)
        historyManager = HistoryManager(this)
        bookmarkLibrary = LibraryPager(lifecycleScope, Bookmark::id) { query, limit, offset ->
            if (query.isBlank()) bookmarkManager.getAllBookmarks(limit, offset)
            else bookmarkManager.searchBookmarks(query, limit, offset)
        }
        historyLibrary = LibraryPager(lifecycleScope, HistoryEntry::id) { query, limit, offset ->
            if (query.isBlank()) historyManager.getAllHistory(limit, offset)
            else historyManager.searchHistory(query, limit, offset)
        }
        downloadSettingsRepository = DownloadSettingsRepository(this)
        downloadSettings = downloadSettingsRepository.load()
        preferencesRepository = BrowserPreferencesRepository(this)
        browserPreferences = preferencesRepository.load()
        downloadHandler = app.downloadHandler

        webViewOrNull = pool.acquire(this).also(::configure)

        // A VIEW/SEND intent is an explicit destination. Restoring the previous tab first
        // would briefly start its WebView and let late subresource callbacks pollute the
        // media candidates for the requested URL. Restore only for a normal launcher start;
        // handleIntent() performs the explicit navigation below after Compose is ready.
        if (savedInstanceState != null || intentNavigationText(intent) == null) {
            loadCurrentTab()
        } else {
            media.clear()
            webViewOrNull?.let { mediaTrackers[it]?.reset() }
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
                val downloads by downloadHandler.downloads.collectAsState()
                val networkEntries by networkLogs.entries.collectAsState()
                val consoleEntries by consoleLogs.entries.collectAsState()
                val mediaSnapshot by media.state.collectAsState()
                val castSnapshot by cast.state.collectAsState()
                BrowserScreen(
                    state = state,
                    webView = webView,
                    onNavigate = ::navigate,
                    onBack = { if (webView.canGoBack()) webView.goBack() },
                    onBackLongPress = {
                        // Long-press back escapes SPA history. A page using pushState can
                        // pile up dozens of entries and canGoBack() counts every one, so
                        // plain back can take many presses to leave a site.
                        webView.clearHistory()
                        goHome()
                    },
                    onForward = { if (webView.canGoForward()) webView.goForward() },
                    onHome = ::goHome,
                    onReloadOrStop = {
                        if (state.isLoading) webView.stopLoading() else webView.reload()
                    },
                    onMenu = {
                        Log.d("MainActivity", "onMenu clicked, setting sheet to MENU")
                        sheet = Sheet.MENU
                    },
                    onTabs = {
                        tabManager.captureCurrentThumbnail(webView, 200, 300)
                        sheet = Sheet.TABS
                    },
                    tabCount = tabManager.count,
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
                    onSecurityClick = {
                        securityCertificate = SecurityChecker.certificateDetails(webView.certificate)
                        showSecurityDialog = true
                    },
                    bookmarkManager = bookmarkManager,
                    historyManager = historyManager,
                    tabRevision = tabManager.revision,
                )

                when (sheet) {
                    Sheet.MENU -> {
                        Log.d("MainActivity", "Rendering MenuSheet")
                        MenuSheet(
                        isIncognito = privacy.isIncognito,
                        isFilterEnabled = filter.enabled.collectAsState().value,
                        blockedCount = filter.blockedCount,
                        mediaCount = mediaSnapshot.count,
                        hasVideo = hasVideo,
                        playbackSpeed = playbackSpeed,
                        isDesktopMode = state.isDesktopMode,
                        isCurrentPageBookmarked = currentPageBookmarked,
                        canUsePageActions = UrlUtils.isHttpUrl(state.currentUrl),
                        onSharePage = { sheet = null; shareUrl(state.currentUrl) },
                        onCopyPage = { sheet = null; copyToClipboard(state.currentUrl) },
                        onToggleIncognito = {
                            sheet = null
                            toggleIncognito()
                        },
                        onToggleFilter = { filter.setEnabled(it) },
                        onToggleDesktopMode = {
                            sheet = null
                            toggleDesktopMode()
                        },
                        onOpenFind = {
                            sheet = null
                            state.showFindBar()
                        },
                        onOpenPlaybackSpeed = {
                            mediaTrackers[webView]?.probe()
                            sheet = Sheet.PLAYBACK_SPEED
                        },
                        onOpenMedia = {
                            mediaTrackers[webView]?.probe()
                            sheet = Sheet.CAST
                            cast.search()
                        },
                        onOpenBookmarks = {
                            sheet = Sheet.BOOKMARKS
                            bookmarkLibrary.search("")
                        },
                        onOpenHistory = {
                            sheet = Sheet.HISTORY
                            historyLibrary.search("")
                        },
                        onOpenDownloads = {
                            sheet = Sheet.DOWNLOADS
                        },
                        onOpenSettings = {
                            sheet = Sheet.SETTINGS
                        },
                        onToggleBookmark = {
                            if (isCurrentPageBookmarked()) {
                                removeCurrentPageFromBookmarks()
                            } else {
                                openBookmarkEditor()
                            }
                        },
                        onClearData = {
                            sheet = null
                            Dialogs.confirm(
                                context = this,
                                title = getString(R.string.clear_data_title),
                                message = getString(R.string.clear_data_message),
                                positiveText = getString(R.string.action_clear),
                                negativeText = getString(R.string.action_cancel),
                            ) { confirmed ->
                                if (confirmed) clearBrowsingData()
                            }
                        },
                        onOpenDeveloperTools = {
                            sheet = null
                            showDeveloperTools = true
                        },
                        onExit = ::exitBrowser,
                        onDismiss = { sheet = null },
                    )
                    }

                    Sheet.CAST -> CastSheet(
                        candidates = mediaSnapshot.candidates,
                        devices = castSnapshot.devices,
                        isSearching = castSnapshot.isSearching,
                        onSearch = cast::search,
                        onCast = { candidate, device ->
                            cast.cast(candidate, device, ::toast)
                        },
                        onCopyUrl = { copyToClipboard(it.url) },
                        onDismiss = { sheet = null },
                        preferredCandidate = mediaSnapshot.preferredCandidate,
                        playingCandidateUrls = mediaSnapshot.playingCandidateUrls,
                    )

                    Sheet.PLAYBACK_SPEED -> PlaybackSpeedSheet(
                        currentSpeed = playbackSpeed,
                        onSelect = { speed ->
                            sheet = null
                            applyPlaybackSpeed(speed)
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.TABS -> TabsSheet(
                        tabs = tabManager.tabs,
                        currentIndex = tabManager.currentIndex,
                        isIncognito = privacy.isIncognito,
                        canCreateTab = tabManager.canCreateTab,
                        onSelectTab = { id ->
                            sheet = null
                            switchToTab(tabManager.tabs.indexOfFirst { it.id == id })
                        },
                        onCloseTab = { id ->
                            closeTab(tabManager.tabs.indexOfFirst { it.id == id })
                        },
                        onNewTab = {
                            sheet = null
                            createNewTab()
                        },
                        onCloseAll = {
                            sheet = null
                            closeAllTabs()
                        },
                        onCloseOthers = {
                            tabManager.closeOtherTabs()
                            persistNormalSession()
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.BOOKMARKS -> BookmarksSheet(
                        bookmarks = bookmarkLibrary.entries,
                        query = bookmarkLibrary.query,
                        loading = bookmarkLibrary.loading,
                        hasMore = bookmarkLibrary.hasMore,
                        error = bookmarkLibrary.error,
                        onQueryChange = bookmarkLibrary::search,
                        onLoadMore = bookmarkLibrary::loadMore,
                        onOpenNewTab = { url -> sheet = null; openUrlInNewTab(url) },
                        onCopy = ::copyToClipboard,
                        onEditBookmark = { bookmark ->
                            sheet = null
                            bookmarkDraft = BookmarkDraft(bookmark.title, bookmark.url, bookmark.id)
                        },
                        onSelectBookmark = { url ->
                            sheet = null
                            navigate(url)
                        },
                        onDeleteBookmark = { url ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                bookmarkManager.removeBookmark(url)
                                withContext(Dispatchers.Main) {
                                    if (state.currentUrl == url) currentPageBookmarked = false
                                    loadBookmarks()
                                }
                            }
                            toast(getString(R.string.bookmark_removed))
                        },
                        onClearAll = {
                            Dialogs.confirm(this, getString(R.string.bookmarks_clear),
                                getString(R.string.bookmarks_clear_confirm)) { confirmed ->
                                if (confirmed) lifecycleScope.launch(Dispatchers.IO) {
                                    bookmarkManager.clearAll()
                                    withContext(Dispatchers.Main) {
                                        currentPageBookmarked = false
                                        loadBookmarks()
                                    }
                                }
                            }
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.HISTORY -> HistorySheet(
                        history = historyLibrary.entries,
                        query = historyLibrary.query,
                        loading = historyLibrary.loading,
                        hasMore = historyLibrary.hasMore,
                        error = historyLibrary.error,
                        onQueryChange = historyLibrary::search,
                        onLoadMore = historyLibrary::loadMore,
                        onOpenNewTab = { url -> sheet = null; openUrlInNewTab(url) },
                        onCopy = ::copyToClipboard,
                        onSelectHistory = { url ->
                            sheet = null
                            navigate(url)
                        },
                        onDeleteHistory = { id ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                historyManager.removeHistory(id)
                                withContext(Dispatchers.Main) { loadHistory() }
                            }
                        },
                        onClearAll = {
                            Dialogs.confirm(this, getString(R.string.history_clear),
                                getString(R.string.history_clear_confirm)) { confirmed ->
                                if (confirmed) lifecycleScope.launch(Dispatchers.IO) {
                                    historyManager.clearAll()
                                    withContext(Dispatchers.Main) { loadHistory() }
                                }
                            }
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.DOWNLOADS -> DownloadsSheet(
                        downloads = downloads,
                        onDismiss = { sheet = null },
                        onOpenFile = { id ->
                            if (!downloadHandler.openFile(id)) {
                                toast(getString(R.string.ui_no_app_can_open_this_file))
                            }
                        },
                        onCancelDownload = downloadHandler::cancel,
                        onRetryDownload = { id ->
                            if (downloadHandler.retry(id) == null) {
                                toast(getString(R.string.ui_unable_to_retry_this_download))
                            }
                        },
                        onDeleteDownload = { id, deleteFile ->
                            downloadHandler.delete(id, deleteFile) { result ->
                                when {
                                    result.failedFileCount > 0 -> toast(
                                        getString(R.string.ui_unable_to_delete_the_local_file_the_download),
                                    )
                                    result.removedCount > 0 && deleteFile -> toast(
                                        getString(R.string.ui_download_record_and_local_file_deleted),
                                    )
                                    result.removedCount > 0 -> toast(getString(R.string.ui_download_record_deleted))
                                }
                            }
                        },
                        onClearCompleted = { deleteFiles ->
                            downloadHandler.clearCompleted(deleteFiles) { result ->
                                when {
                                    result.failedFileCount > 0 -> toast(
                                        getString(R.string.downloads_cleared_partial, result.removedCount, result.failedFileCount),
                                    )
                                    result.removedCount > 0 && deleteFiles -> toast(
                                        getString(R.string.ui_cleared_records_and_files, result.removedCount),
                                    )
                                    result.removedCount > 0 -> toast(
                                        getString(R.string.ui_cleared_records, result.removedCount),
                                    )
                                }
                            }
                        },
                    )

                    Sheet.SETTINGS -> SettingsSheet(
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
                        onAddCustomSearchEngine = { name, template ->
                            runCatching {
                                searchEngineManager.addCustomEngine(name, template)
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
                        preferences = browserPreferences,
                        onPreferencesChange = { browserPreferences = preferencesRepository.save(it) },
                        isFilterEnabled = filter.enabled.collectAsState().value,
                        onFilterEnabledChange = filter::setEnabled,
                        onClearData = {
                            Dialogs.confirm(
                                context = this,
                                title = getString(R.string.clear_data_title),
                                message = getString(R.string.clear_data_message),
                                positiveText = getString(R.string.action_clear),
                                negativeText = getString(R.string.action_cancel),
                            ) { confirmed -> if (confirmed) clearBrowsingData() }
                        },
                        onOpenDeveloperTools = {
                            sheet = null
                            showDeveloperTools = true
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.FILTER_SETTINGS -> FilterSettingsSheet(
                        controller = customFilter,
                        filterController = filter,
                        onDismiss = { sheet = null },
                    )

                    null -> Unit
                }

                if (showFilterSettings) {
                    FilterSettingsSheet(customFilter, filter, onDismiss = { showFilterSettings = false })
                }

                bookmarkDraft?.let { draft ->
                    BookmarkEditDialog(
                        initialTitle = draft.title,
                        initialUrl = draft.url,
                        onSave = ::saveBookmark,
                        isEditing = draft.id != null,
                        onDismiss = {
                            bookmarkDraft = null
                            if (draft.id != null) sheet = Sheet.BOOKMARKS
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
                    com.mybrowser.ui.SecurityInfoDialog(
                        url = state.currentUrl,
                        certificate = securityCertificate,
                        onDismiss = {
                            showSecurityDialog = false
                            securityCertificate = null
                        },
                    )
                }

                // SSL Error Dialog
                if (showSSLErrorDialog) {
                    pendingSSLError?.let { (handler, url) ->
                        com.mybrowser.ui.SSLErrorDialog(
                            url = url,
                            onProceed = {
                                handler.proceed()
                                showSSLErrorDialog = false
                                pendingSSLError = null
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
                if (showDeveloperTools) {
                    com.mybrowser.ui.DeveloperTools(
                        webView = webView,
                        networkEntries = networkEntries,
                        consoleEntries = consoleEntries,
                        onClearNetwork = networkLogs::clear,
                        onClearConsole = consoleLogs::clear,
                        pageUrl = state.currentUrl,
                        onDismiss = { showDeveloperTools = false }
                    )
                }
            }
        }

        if (savedInstanceState == null && intentNavigationText(intent) != null) {
            handleIntent(intent)
        }
    }

    private fun registerActivityLaunchers() {
        defaultBrowserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            isDefaultBrowser = DefaultBrowser.isDefault(this)
        }
        openDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(uri?.let { arrayOf(it) })
        }
        openMultipleDocumentsLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(uris.toTypedArray())
        }
        runtimePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            val granted = result.isNotEmpty() && result.values.all { it }
            runCatching {
                if (granted) request.grant(request.resources) else request.deny()
            }
        }
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val pending = pendingGeoRequest
            pendingGeoRequest = null
            if (pending == null) return@registerForActivityResult
            val allowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            runCatching { pending.second.invoke(pending.first, allowed, !privacy.isIncognito) }
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

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val id = downloadHandler.enqueue(
                url,
                userAgent,
                contentDisposition,
                mimeType,
                referer = state.currentUrl,
            )
            if (id != null) {
                toast(getString(R.string.download_started))
            } else {
                toast(getString(R.string.ui_unable_to_start_the_download))
            }
        }

        // Find in page listener
        view.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                state.onFindResultUpdate(activeMatchOrdinal + 1, numberOfMatches)
            }
        }

        // Re-applied on every acquire, including the post-crash replacement: a fresh
        // instance defaults to the normal profile, which would silently drop an incognito
        // session back onto persistent storage.
        privacy.applyTo(view)

        // Apply desktop mode setting
        applyDesktopMode(view, state.isDesktopMode)

        installMediaPlaybackTracker(view)
    }

    /** Installs one media probe per pooled WebView and replaces stale Activity callbacks. */
    private fun installMediaPlaybackTracker(view: WebView) {
        val generation = ++mediaTrackerGeneration
        mediaTrackers.remove(view)?.close()
        val tracker = MediaPlaybackTracker(view) { signal ->
            runOnUiThread {
                if (webViewOrNull !== view || generation != mediaTrackerGeneration) {
                    return@runOnUiThread
                }
                hasVideo = signal.hasVideo
                playbackSpeed = signal.playbackRate ?: PlaybackSpeed.DEFAULT
                media.updatePlayback(signal)
                fullscreenView?.update(signal)
                val preferences = browserPreferences.video
                if (signal.isPlaying && !signal.isBoosting && preferences.rememberSpeed &&
                    signal.identity != rememberedVideo
                ) {
                    rememberedVideo = signal.identity
                    mediaTrackers[view]?.setPlaybackRate(preferences.preferredSpeed)
                }
            }
        }
        tracker.install()
        mediaTrackers[view] = tracker
    }

    private fun removeMediaPlaybackTracker(view: WebView) {
        if (webViewOrNull === view) leaveFullscreen()
        rememberedVideo = null
        mediaTrackerGeneration++
        mediaTrackers.remove(view)?.close()
        hasVideo = false
        playbackSpeed = PlaybackSpeed.DEFAULT
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val view = webViewOrNull
        val tracker = view?.let(mediaTrackers::get)
        if (view == null || tracker == null) {
            toast(getString(R.string.playback_speed_failed))
            return
        }
        tracker.setPlaybackRate(speed) { applied ->
            runOnUiThread {
                if (webViewOrNull !== view || mediaTrackers[view] !== tracker) return@runOnUiThread
                if (applied) {
                    playbackSpeed = speed
                    rememberPlaybackSpeed(speed)
                    toast(
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

    private fun applyDesktopMode(view: WebView, enabled: Boolean) {
        WebViewConfig.setUserAgent(view, enabled)
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
    }

    private fun handleFileChooser(
        callback: ValueCallback<Array<Uri>?>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        val accepts = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("*/*") }
            .toTypedArray()
        return runCatching {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                openMultipleDocumentsLauncher.launch(accepts)
            } else {
                openDocumentLauncher.launch(accepts)
            }
            true
        }.getOrElse {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toString()
        val scheme = UrlUtils.schemeOf(origin)
        if (scheme != "http" && scheme != "https") {
            request.deny()
            return
        }

        val runtimePermissions = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
                add(Manifest.permission.CAMERA)
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }.distinct()

        if (runtimePermissions.isEmpty()) {
            // Protected media IDs do not have an app runtime permission. Grant only the
            // resource types WebView explicitly requested.
            runCatching { request.grant(request.resources) }
            return
        }

        pendingPermissionRequest?.let { old -> runCatching { old.deny() } }
        pendingPermissionRequest = request
        val missing = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pendingPermissionRequest = null
            runCatching { request.grant(request.resources) }
        } else {
            runtimePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun handleGeolocationRequest(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        val scheme = UrlUtils.schemeOf(origin)
        if (scheme != "http" && scheme != "https") {
            callback.invoke(origin, false, false)
            return
        }

        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            callback.invoke(origin, true, !privacy.isIncognito)
            return
        }

        pendingGeoRequest?.let { old ->
            runCatching { old.second.invoke(old.first, false, false) }
        }
        pendingGeoRequest = origin to callback
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
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
            BrowserChromeClient.JsDialogType.ALERT -> Dialogs.alert(
                this,
                title,
                message,
            ) { runCatching { result.confirm() } }
            BrowserChromeClient.JsDialogType.CONFIRM -> Dialogs.confirm(
                this,
                title,
                message,
            ) { accepted -> runCatching { if (accepted) result.confirm() else result.cancel() } }
            BrowserChromeClient.JsDialogType.PROMPT -> Dialogs.prompt(
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
            BrowserChromeClient.JsDialogType.BEFORE_UNLOAD -> Dialogs.confirm(
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
        if (!isUserGesture || !tabManager.canCreateTab) return null
        val old = webViewOrNull ?: return null
        saveCurrentTab()
        val popup = runCatching { pool.acquire(this).also(::configure) }.getOrNull() ?: return null
        tabManager.createTab()
        readyWebViewTabId = null
        webViewOrNull = popup
        // The old tab's state is already saved. A normal WebView can return to the pool;
        // an incognito/profile-bound instance must be discarded rather than reused.
        removeMediaPlaybackTracker(old)
        old.webViewClient = DetachedWebViewClient
        old.webChromeClient = null
        (old.parent as? ViewGroup)?.removeView(old)
        if (privacy.isIncognito) {
            pool.discard(old)
        } else {
            pool.release(old)
        }
        return popup
    }

    private fun toggleDesktopMode() {
        state.toggleDesktopMode()
        applyDesktopMode(webView, state.isDesktopMode)
        webView.reload()
        toast(if (state.isDesktopMode) getString(R.string.ui_switched_to_desktop_mode) else getString(R.string.ui_switched_to_mobile_mode))
    }

    /**
     * Switching modes destroys the WebView and switches to the other tab group.
     * Normal and incognito modes maintain completely separate tab systems.
     */
    private fun toggleIncognito() {
        val entering = !privacy.isIncognito

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
            privacy.enter()
            tabManager = incognitoTabManager
        } else {
            val hadRealIsolation = privacy.hasRealIsolation
            incognitoTabManager.clearAllTabs()
            tabManager = normalTabManager
            // The fallback mode shares the normal cookie jar while open. Keep the new
            // WebView blank until the asynchronous cookie wipe has completed.
            privacy.exit(wipeSharedStorage = !hadRealIsolation) {
                if (isFinishing || isDestroyed) return@exit
                loadCurrentTab()
                toast(getString(R.string.incognito_off))
            }
        }

        media.clear()
        filter.resetPageCount()

        // A profile-bound WebView must be completely unused before attachment. Never
        // borrow an idle normal-profile instance for a mode transition.
        webViewOrNull = pool.acquireFresh(this).also(::configure)
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
        val tab = tabManager.currentTab ?: return
        readyWebViewTabId = null
        dismissPageContext()
        leaveFullscreen()
        state.revealToolbar()
        mediaProbeJob?.cancel()
        media.clear()
        // WebView.restoreState is only supported before the instance builds history.
        // Reusing a loaded instance loses restored navigation entries on older providers.
        if (tab.savedState != null && webView.copyBackForwardList().size > 0) {
            val old = webView
            removeMediaPlaybackTracker(old)
            pool.discard(old)
            webViewOrNull = pool.acquireFresh(this).also(::configure)
        }
        webViewOrNull?.let { mediaTrackers[it]?.reset() }
        val restored = tabManager.loadCurrentState(webView)
        clearHistoryOnNextFinish = !restored
        if (!restored) {
            val url = tab.url.takeIf { it.isNotBlank() && it != ABOUT_BLANK }
                ?: if (homepageMode == HomepageMode.NAVIGATION) ABOUT_BLANK else homeUrl
            state.onPageStarted(url)
            state.onTitleChanged(tab.title)
            webView.loadUrl(url)
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
        webView.stopLoading()
        loadCurrentTab()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabManager.tabs.indices || index == tabManager.currentIndex) return
        saveCurrentTab()
        webView.stopLoading()
        tabManager.switchToIndex(index)
        loadCurrentTab()
    }

    private fun closeTab(index: Int) {
        if (index !in tabManager.tabs.indices) return
        val wasCurrent = index == tabManager.currentIndex
        tabManager.closeTab(index)
        if (wasCurrent) {
            webView.stopLoading()
            loadCurrentTab()
        }
        persistNormalSession()
    }

    private fun closeAllTabs() {
        tabManager.clearAllTabs()
        webView.stopLoading()
        loadCurrentTab()
        persistNormalSession()
    }

    private fun openUrlInNewTab(url: String, background: Boolean = false) {
        if (!UrlUtils.isHttpUrl(url)) { navigate(url); return }
        if (!tabManager.canCreateTab) { toast(getString(R.string.ui_tab_limit_reached)); return }
        if (!background) saveCurrentTab()
        tabManager.createTab(url, select = !background, title = UrlUtils.hostOf(url).orEmpty())
        if (background) {
            persistNormalSession()
            toast(getString(R.string.context_opened_background))
        } else {
            webView.stopLoading()
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
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.menu_share_page))) }
            .onFailure { toast(getString(R.string.context_share_unavailable)) }
    }

    private fun downloadImage(url: String) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        val id = downloadHandler.enqueue(url, webView.settings.userAgentString, null, mime, referer = state.currentUrl)
        toast(getString(if (id != null) R.string.download_started else R.string.ui_unable_to_start_the_download))
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
        sheet = null
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
                    if (draft.id != null) sheet = Sheet.BOOKMARKS
                }
                if (state.currentUrl == normalized) {
                    currentPageBookmarked = true
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

    private fun removeCurrentPageFromBookmarks() {
        val url = state.currentUrl
        if (url.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val removed = bookmarkManager.removeBookmark(url)
                withContext(Dispatchers.Main) {
                    if (removed > 0) currentPageBookmarked = false
                    loadBookmarks()
                    if (removed > 0) toast(getString(R.string.bookmark_removed))
                }
            }
        }
    }

    private fun isCurrentPageBookmarked(): Boolean {
        return currentPageBookmarked
    }

    private fun refreshBookmarkStatus(url: String) {
        if (privacy.isIncognito || url.isBlank() || isHomeDocument(url)) {
            currentPageBookmarked = false
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarked = bookmarkManager.isBookmarked(url)
            withContext(Dispatchers.Main) {
                // A fast navigation can make this result stale; only apply it to the page
                // that is still visible.
                if (state.currentUrl == url) currentPageBookmarked = bookmarked
            }
        }
    }

    private fun addToHistory(url: String, title: String) {
        // Don't add to history in incognito mode or for special URLs
        if (privacy.isIncognito) return
        if (isHomeDocument(url)) return
        if (url.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            historyManager.addHistory(title, url)
        }
    }

    /**
     * Clears browsing state promised by the menu label while preserving user-created
     * bookmarks and downloaded files.  The previous implementation silently deleted both,
     * even though the confirmation text only named history, cookies and cache.
     */
    private fun clearBrowsingData() {
        // Cookie removal is asynchronous. Only update the UI and show the completion toast
        // after WebView confirms the wipe; otherwise a fast reload can observe the old jar.
        privacy.wipeEverything {
            if (isFinishing || isDestroyed) return@wipeEverything
            privacy.wipeInstanceState(webView)
            lifecycleScope.launch(Dispatchers.IO) {
                historyManager.clearAll()
                withContext(Dispatchers.Main) {
                    historyLibrary.refresh()
                    toast(getString(R.string.clear_data_done))
                }
            }
        }
    }

    // --- BrowserWebViewClient.Listener ---

    override fun isCurrentWebView(view: WebView): Boolean = webViewOrNull === view

    override fun onPageStarted(url: String) {
        readyWebViewTabId = null
        dismissPageContext()
        leaveFullscreen()
        rememberedVideo = null
        documentUrlForWorkers = url
        state.onPageStarted(url)
        networkLogs.beginPage(url)
        consoleLogs.clear()
        mediaProbeJob?.cancel()
        webViewOrNull?.let { mediaTrackers[it]?.reset() }
        // Per-page counters. Both are scoped to the document, so a new main frame resets
        // them; subresources of the same page keep accumulating.
        media.clear()
        filter.resetPageCount()
        // Update current tab URL
        tabManager.currentTab?.url = url
        tabManager.notifyChanged()
        refreshBookmarkStatus(url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
        }
    }

    override fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (url != webView.url || url != state.currentUrl) return
        readyWebViewTabId = tabManager.currentTab?.id
        documentUrlForWorkers = url
        val resetHistory = clearHistoryOnNextFinish
        if (resetHistory) {
            webView.clearHistory()
            clearHistoryOnNextFinish = false
        }
        state.onPageFinished(url, !resetHistory && canGoBack, !resetHistory && canGoForward)
        state.onTitleChanged(webView.title)
        networkLogs.markMainFrameFinished(url)
        if (state.isDesktopMode) WebViewConfig.applyDesktopViewport(webView)
        // Update current tab URL and title
        tabManager.currentTab?.let { tab ->
            tab.url = url
            tab.title = webView.title ?: url
            tabManager.notifyChanged()
        }
        // Add to history (only in normal mode)
        addToHistory(url, webView.title ?: url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
        }

        // Detect the active media element for cast preference. The document-start tracker
        // handles cross-origin iframes; older providers use the one-shot polling fallback.
        startPlayingVideoDetection()
    }

    override fun onHistoryUpdated(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        documentUrlForWorkers = url
        state.onHistoryUpdated(url, canGoBack, canGoForward)
        tabManager.currentTab?.url = url
        tabManager.notifyChanged()
        refreshBookmarkStatus(url)
        if (!privacy.isIncognito && restoreLastSession) {
            normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
        }
    }

    private fun startPlayingVideoDetection() {
        mediaProbeJob?.cancel()
        val view = webViewOrNull ?: return
        val tracker = mediaTrackers[view] ?: return

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
        state.onPageError()
        networkLogs.markMainFrameError(url, code, description)
        // ERROR_UNKNOWN with an empty description is what a cancelled navigation looks
        // like; a toast for it would fire on every fast tap.
        if (description.isNotEmpty()) {
            toast(getString(R.string.page_load_error_code, code))
        }
    }

    override fun onExternalUrl(url: String): Boolean {
        when (val result = ExternalIntentHandler.handle(this, url)) {
            ExternalIntentHandler.Result.Launched -> Unit
            ExternalIntentHandler.Result.Rejected -> Unit
            is ExternalIntentHandler.Result.Fallback -> webView.loadUrl(result.url)
        }
        // Always true: the URL is consumed here either way, and returning false would let
        // the WebView also try to load a scheme it cannot handle.
        return true
    }

    override fun onSslError(handler: SslErrorHandler, error: SslError, url: String?) {
        // Keep the handler pending until the user makes an explicit choice. A previous
        // request cannot remain alive when navigation races to a second certificate error.
        pendingSSLError?.first?.cancel()
        pendingSSLError = handler to url.orEmpty()
        runOnUiThread { showSSLErrorDialog = true }
    }

    override fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String) {
        Dialogs.credentials(
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
        // A late callback from a view that has already been detached must not replace the
        // currently visible tab. It is still safe to discard that corpse.
        if (webViewOrNull !== webView) {
            removeMediaPlaybackTracker(webView)
            pool.discard(webView)
            return
        }
        // The instance is dead. Replacing the state value is the whole fix: it recomposes
        // BrowserScreen, whose holder reconciliation detaches the corpse and attaches the
        // replacement.
        val lastUrl = state.currentUrl
        removeMediaPlaybackTracker(webView)
        pool.discard(webView)
        webViewOrNull = pool.acquireFresh(this).also(::configure)
        if (lastUrl != ABOUT_BLANK) {
            this.webView.loadUrl(lastUrl)
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
        val requestId = networkLogs.recordRequest(request)
        val documentUrl = documentUrlForWorkers

        if (filter.shouldBlock(request, documentUrl)) {
            networkLogs.markBlocked(requestId)
            return BLOCKED_RESPONSE
        }

        MediaSniffer.inspect(request, documentUrl)?.let(media::add)
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
        if (pendingPermissionRequest === request) pendingPermissionRequest = null
    }

    private fun rememberPlaybackSpeed(speed: Float) {
        if (!browserPreferences.video.rememberSpeed) return
        browserPreferences = preferencesRepository.save(browserPreferences.copy(
            video = browserPreferences.video.copy(preferredSpeed = speed),
        ))
    }

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val tracker = webViewOrNull?.let(mediaTrackers::get)
        if (fullscreenView != null || tracker == null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenCallback = callback
        val host = FullscreenVideoView(
            activity = this,
            videoView = view,
            preferences = browserPreferences.video,
            tracker = tracker,
            titleProvider = { state.pageTitle ?: getString(R.string.ui_video_playback) },
            canCast = { media.count > 0 },
            onExit = ::leaveFullscreen,
            onCast = {
                fullscreenView?.cancelTransientControls()
                tracker.probe()
                sheet = Sheet.CAST
                cast.search()
            },
            onSpeedSelected = ::rememberPlaybackSpeed,
        )
        fullscreenView = host
        (window.decorView as ViewGroup).addView(host, ViewGroup.LayoutParams(-1, -1))
        host.requestFocus()
        setSystemBarsVisible(false)
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
        setSystemBarsVisible(true)
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

    private fun navigate(input: String) {
        var tabUrl: String? = null
        when (val target = NavigationPolicy.resolve(input, searchEngine)) {
            is NavigationTarget.Load -> {
                prepareForNavigation()
                webView.loadUrl(target.url)
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

    private fun isHomeDocument(url: String): Boolean =
        url == ABOUT_BLANK || (homepageMode == HomepageMode.FIXED_URL && url == homeUrl)

    /** Clears page-scoped work before a new URL starts, closing the old-request race window. */
    private fun prepareForNavigation() {
        dismissPageContext()
        state.revealToolbar()
        mediaProbeJob?.cancel()
        mediaProbeJob = null
        media.clear()
        webViewOrNull?.let { view ->
            mediaTrackers[view]?.reset()
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
        val url = intentNavigationText(intent)
        when {
            !url.isNullOrBlank() -> {
                // A link handed in by another app is a navigation request, not a request to
                // keep whatever bottom sheet was open. Close transient surfaces first so the
                // destination page is immediately visible (and cannot be covered by a stale
                // bookmark/cast sheet).
                sheet = null
                showFilterSettings = false
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

    /**
     * The cases Compose cannot own, in priority order. Omnibar focus is handled by a
     * BackHandler inside the composition, which registers later and therefore runs first.
     */
    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> {
                        if (fullscreenView?.unlockOnBack() != true) leaveFullscreen()
                    }
                    sheet != null -> {
                        // Handle nested sheets: if we're in a sub-settings sheet, go back to main settings
                        sheet = when (sheet) {
                            Sheet.FILTER_SETTINGS -> Sheet.SETTINGS
                            else -> null
                        }
                    }
                    webView.canGoBack() -> webView.goBack()
                    else -> confirmExit()
                }
            }
        })
    }

    private var lastBackPressAt = 0L

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            exitBrowser()
        } else {
            lastBackPressAt = now
            toast(getString(R.string.press_back_again))
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

    // --- Lifecycle ---

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreenView != null) {
            // Dialog windows can clear immersive flags on older Android versions.
            fullscreenView?.requestFocus()
            setSystemBarsVisible(false)
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
        sheet = null
        persistNormalSession()
        webViewOrNull?.let {
            it.stopLoading()
            it.onPause()
        }
        finishAndRemoveTask()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistNormalSession()
        outState.putBundle(STATE_NORMAL_TABS, normalTabManager.snapshotMetadata())
        outState.putString(STATE_PROCESS_SESSION, PROCESS_SESSION)
        outState.putBoolean(STATE_SETTINGS_OPEN, sheet == Sheet.SETTINGS)
        outState.putBoolean(STATE_FILTER_SETTINGS_OPEN, showFilterSettings)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        persistNormalSession()
        super.onStop()
    }

    override fun onPause() {
        dismissPageContext()
        fullscreenView?.cancelTransientControls()
        super.onPause()
        // Suspends timers and JS so a backgrounded page cannot keep burning CPU. Media
        // keeps playing: onPause on the WebView itself would kill audio, which is wrong
        // for a browser.
        webViewOrNull?.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        isDefaultBrowser = DefaultBrowser.isDefault(this)
        webViewOrNull?.onResume()
        webViewOrNull?.resumeTimers()
    }

    override fun onDestroy() {
        dismissPageContext()
        mediaProbeJob?.cancel()
        mediaProbeJob = null
        leaveFullscreen()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingPermissionRequest?.let { runCatching { it.deny() } }
        pendingPermissionRequest = null
        pendingGeoRequest?.let { runCatching { it.second.invoke(it.first, false, false) } }
        pendingGeoRequest = null
        pendingSSLError?.first?.cancel()
        pendingSSLError = null
        showSSLErrorDialog = false

        // Save current tab state before destroying
        persistNormalSession()

        // Order matters. Clear the clients first so a late callback cannot touch a
        // half-torn-down Activity, then detach from Compose's holder, then hand back to
        // the pool — which decides whether to keep or destroy the instance.
        webViewOrNull?.let { view ->
            removeMediaPlaybackTracker(view)
            view.webViewClient = DetachedWebViewClient
            view.webChromeClient = null
            (view.parent as? ViewGroup)?.removeView(view)
            if (::privacy.isInitialized && privacy.isIncognito) pool.discard(view)
            else pool.release(view)
        }
        webViewOrNull = null
        if (::customFilter.isInitialized) customFilter.close()

        // Clean up tab resources
        if (::normalTabManager.isInitialized) normalTabManager.cleanup()
        if (::incognitoTabManager.isInitialized) incognitoTabManager.cleanup()
        if (::privacy.isInitialized && privacy.isIncognito) {
            val hadRealIsolation = privacy.hasRealIsolation
            privacy.exit(wipeSharedStorage = !hadRealIsolation)
        }
        if (::bookmarkManager.isInitialized) bookmarkManager.close()
        if (::historyManager.isInitialized) historyManager.close()

        super.onDestroy()
    }

    // --- Helpers ---

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
        const val STATE_SETTINGS_OPEN = "settings_open"
        const val STATE_FILTER_SETTINGS_OPEN = "filter_settings_open"
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
