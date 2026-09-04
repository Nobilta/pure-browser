package com.mybrowser

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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.mybrowser.dlna.CastController
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.CustomFilterController
import com.mybrowser.media.MediaCandidateStore
import com.mybrowser.media.MediaPlaybackTracker
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchEngineManager
import com.mybrowser.search.UrlOrSearch
import com.mybrowser.media.MediaSniffer
import com.mybrowser.privacy.PrivacyMode
import com.mybrowser.home.HomeRepository
import com.mybrowser.home.HomeShortcut
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
import com.mybrowser.ui.TabsSheet
import com.mybrowser.ui.DownloadsSheet
import com.mybrowser.ui.SettingsSheet
import com.mybrowser.ui.FilterSettingsSheet
import com.mybrowser.ui.BookmarkEditDialog
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

    // Cached bookmarks and history for UI
    private var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
    private var history by mutableStateOf<List<HistoryEntry>>(emptyList())
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
    private val networkLogs = NetworkLogStore()
    private val consoleLogs = ConsoleLogStore()
    private val cast: CastController by lazy { CastController(lifecycleScope) }

    /** Which bottom sheet is up, if any. */
    private var sheet: Sheet? by mutableStateOf(null)

    private enum class Sheet { MENU, CAST, TABS, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS, FILTER_SETTINGS }

    /** Non-null while the add-bookmark editor is visible. */
    private var bookmarkDraft: BookmarkDraft? by mutableStateOf(null)

    private data class BookmarkDraft(val title: String, val url: String)

    // Homepage settings and user-created navigation tiles.
    private lateinit var homeRepository: HomeRepository
    private var homepageMode by mutableStateOf(HomepageMode.NAVIGATION)
    private var homeUrl by mutableStateOf("https://www.bing.com")
    private var homeShortcuts by mutableStateOf<List<HomeShortcut>>(emptyList())

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

    /**
     * A worker-thread-safe copy for shouldInterceptRequest. Reading Compose snapshot state
     * from Chromium's network thread is unnecessary coupling and can observe a stale snapshot;
     * the volatile value is updated alongside the main-frame callbacks instead.
     */
    @Volatile private var documentUrlForWorkers: String = ABOUT_BLANK

    private val webView: WebView
        get() = checkNotNull(webViewOrNull) { "WebView read before onCreate acquired it" }

    /** Fullscreen video state. */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = 0

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
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val shortcuts = homeRepository.loadShortcuts()
            withContext(Dispatchers.Main) { homeShortcuts = shortcuts }
        }

        // Initialize both tab managers
        normalTabManager = TabManager()
        normalTabManager.restoreMetadata(this, NORMAL_TABS_PREFS)
        incognitoTabManager = TabManager()
        tabManager = normalTabManager

        // Initialize bookmarks and history managers
        bookmarkManager = BookmarkManager(this)
        historyManager = HistoryManager(this)
        downloadSettingsRepository = DownloadSettingsRepository(this)
        downloadSettings = downloadSettingsRepository.load()
        downloadHandler = app.downloadHandler

        webViewOrNull = pool.acquire(this).also(::configure)

        // A VIEW/SEND intent is an explicit destination. Restoring the previous tab first
        // would briefly start its WebView and let late subresource callbacks pollute the
        // media candidates for the requested URL. Restore only for a normal launcher start;
        // handleIntent() performs the explicit navigation below after Compose is ready.
        if (intentNavigationText(intent) == null) {
            loadCurrentTab()
        } else {
            media.clear()
            webViewOrNull?.let { mediaTrackers[it]?.reset() }
        }

        setContent {
            MyBrowserTheme {
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
                        Log.d("MainActivity", "onTabs clicked, setting sheet to TABS")
                        sheet = Sheet.TABS
                    },
                    tabCount = tabManager.count,
                    showHomeDashboard = homepageMode == HomepageMode.NAVIGATION &&
                        state.currentUrl == ABOUT_BLANK,
                    homeShortcuts = homeShortcuts,
                    onOpenHomeShortcut = { shortcut -> navigate(shortcut.url) },
                    onRemoveHomeShortcut = ::removeHomeShortcut,
                    mediaCount = mediaSnapshot.count,
                    onCast = {
                        mediaTrackers[webView]?.probe()
                        sheet = Sheet.CAST
                        cast.search()
                    },
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
                        isDesktopMode = state.isDesktopMode,
                        isCurrentPageBookmarked = currentPageBookmarked,
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
                        onOpenMedia = {
                            mediaTrackers[webView]?.probe()
                            sheet = Sheet.CAST
                            cast.search()
                        },
                        onOpenBookmarks = {
                            sheet = Sheet.BOOKMARKS
                            loadBookmarks()
                        },
                        onOpenHistory = {
                            sheet = Sheet.HISTORY
                            loadHistory()
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

                    Sheet.TABS -> TabsSheet(
                        tabs = tabManager.tabs,
                        currentIndex = tabManager.currentIndex,
                        isIncognito = privacy.isIncognito,
                        onSelectTab = { index ->
                            sheet = null
                            switchToTab(index)
                        },
                        onCloseTab = { index ->
                            closeTab(index)
                        },
                        onNewTab = {
                            sheet = null
                            createNewTab()
                        },
                        onCloseAll = {
                            sheet = null
                            closeAllTabs()
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.BOOKMARKS -> BookmarksSheet(
                        bookmarks = bookmarks,
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
                            lifecycleScope.launch(Dispatchers.IO) {
                                bookmarkManager.clearAll()
                                withContext(Dispatchers.Main) {
                                    currentPageBookmarked = false
                                    loadBookmarks()
                                }
                            }
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.HISTORY -> HistorySheet(
                        history = history,
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
                            lifecycleScope.launch(Dispatchers.IO) {
                                historyManager.clearAll()
                                withContext(Dispatchers.Main) { loadHistory() }
                            }
                        },
                        onDismiss = { sheet = null },
                    )

                    Sheet.DOWNLOADS -> DownloadsSheet(
                        downloads = downloads,
                        onDismiss = { sheet = null },
                        onOpenFile = { id ->
                            if (!downloadHandler.openFile(id)) {
                                toast("没有可打开此文件的应用")
                            }
                        },
                        onCancelDownload = downloadHandler::cancel,
                        onRetryDownload = { id ->
                            if (downloadHandler.retry(id) == null) {
                                toast("无法重试该下载")
                            }
                        },
                        onDeleteDownload = { id, deleteFile ->
                            downloadHandler.delete(id, deleteFile) { result ->
                                when {
                                    result.failedFileCount > 0 -> toast(
                                        "无法删除本地文件，下载记录已保留",
                                    )
                                    result.removedCount > 0 && deleteFile -> toast(
                                        "下载记录和本地文件已删除",
                                    )
                                    result.removedCount > 0 -> toast("下载记录已删除")
                                }
                            }
                        },
                        onClearCompleted = { deleteFiles ->
                            downloadHandler.clearCompleted(deleteFiles) { result ->
                                when {
                                    result.failedFileCount > 0 -> toast(
                                        "已清除 ${result.removedCount} 项，" +
                                            "${result.failedFileCount} 个文件无法删除",
                                    )
                                    result.removedCount > 0 && deleteFiles -> toast(
                                        "已清除 ${result.removedCount} 项记录和文件",
                                    )
                                    result.removedCount > 0 -> toast(
                                        "已清除 ${result.removedCount} 项记录",
                                    )
                                }
                            }
                        },
                    )

                    Sheet.SETTINGS -> SettingsSheet(
                        currentSearchEngine = searchEngine,
                        availableSearchEngines = availableSearchEngines,
                        onSearchEngineChange = { engine ->
                            searchEngine = engine
                            searchEngineManager.setCurrentEngine(engine)
                            toast("搜索引擎已切换到 ${engine.name}")
                        },
                        onAddCustomSearchEngine = { name, template ->
                            runCatching {
                                searchEngineManager.addCustomEngine(name, template)
                            }.onSuccess { added ->
                                availableSearchEngines = searchEngineManager.getAvailableEngines()
                                toast("已添加搜索引擎：${added.name}")
                            }.onFailure { error ->
                                Log.w("MainActivity", "invalid custom search engine", error)
                                toast("搜索引擎格式无效或已达到数量上限")
                            }
                        },
                        onRemoveCustomSearchEngine = { engine ->
                            searchEngineManager.removeCustomEngine(engine.id)
                            availableSearchEngines = searchEngineManager.getAvailableEngines()
                            if (searchEngine.id == engine.id) {
                                searchEngine = searchEngineManager.getCurrentEngine()
                            }
                            toast("已删除搜索引擎：${engine.name}")
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
                                if (mode == HomepageMode.NAVIGATION) "已使用导航首页"
                                else "已使用固定网址主页",
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
                                toast("主页已更新")
                                true
                            } else {
                                toast("主页必须是 HTTP 或 HTTPS 地址")
                                false
                            }
                        },
                        onManageCustomFilters = {
                            sheet = Sheet.FILTER_SETTINGS
                        },
                        downloadSettings = downloadSettings,
                        onUseSystemDownloadDirectory = {
                            downloadSettings = downloadSettingsRepository.useSystemDownloads()
                            toast("下载将保存到系统下载目录")
                        },
                        onChooseDownloadDirectory = {
                            val initial = downloadSettings.customTreeUri
                                ?.let { runCatching { it.toUri() }.getOrNull() }
                            downloadDirectoryLauncher.launch(initial)
                        },
                        onDownloadThreadCountChange = { count ->
                            downloadSettings = downloadSettingsRepository.setThreadCount(count)
                            toast("下载线程数已设为 ${downloadSettings.threadCount}")
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

                bookmarkDraft?.let { draft ->
                    BookmarkEditDialog(
                        initialTitle = draft.title,
                        initialUrl = draft.url,
                        onSave = ::saveBookmark,
                        onDismiss = { bookmarkDraft = null },
                    )
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

        setUpBackHandling()
        handleIntent(intent, isInitial = true)
    }

    private fun registerActivityLaunchers() {
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
                toast("无法获得该文件夹的长期写入权限")
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
                    toast("无法使用所选下载目录")
                    return@launch
                }
                downloadSettings = updated
                if (updated.customTreeUri == uri.toString() &&
                    updated.destinationMode ==
                    com.mybrowser.download.DownloadDestinationMode.CUSTOM_DIRECTORY
                ) {
                    toast("下载目录已设为 ${updated.destinationLabel}")
                } else {
                    toast("所选目录权限不可用，已继续使用系统下载目录")
                }
            }
        }
    }

    private fun configure(view: WebView) {
        view.webViewClient = BrowserWebViewClient(this)
        view.webChromeClient = BrowserChromeClient(this, view)

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
                toast("无法开始下载")
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
                when {
                    signal.isPlaying && signal.urls.isNotEmpty() -> {
                        media.setPlayingVideos(signal.urls)
                    }
                    !signal.isPlaying -> media.setPlayingVideos(emptyList())
                }
            }
        }
        tracker.install()
        mediaTrackers[view] = tracker
    }

    private fun removeMediaPlaybackTracker(view: WebView) {
        mediaTrackerGeneration++
        mediaTrackers.remove(view)?.close()
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
        val title = if (origin.isBlank()) "网页提示" else "来自 $origin"
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
                message.ifBlank { "确定要离开此页面吗？" },
                positiveText = "离开",
                negativeText = "留在此页",
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
        toast(if (state.isDesktopMode) "已切换到桌面模式" else "已切换到移动模式")
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
            normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
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
        mediaProbeJob?.cancel()
        media.clear()
        webViewOrNull?.let { mediaTrackers[it]?.reset() }
        val restored = tabManager.loadCurrentState(webView)
        if (!restored) {
            // No saved state, load the URL
            if (tab.url.isNotBlank() && tab.url != ABOUT_BLANK) {
                webView.loadUrl(tab.url)
            } else {
                goHome()
            }
        }
        // Update UI state
        state.currentUrl = tab.url
        state.onPageFinished(tab.url, webView.canGoBack(), webView.canGoForward())
    }

    /**
     * Saves the current WebView state into the current tab before switching away.
     */
    private fun saveCurrentTab() {
        tabManager.saveCurrentState(webView)
        // Update thumbnail
        tabManager.captureCurrentThumbnail(webView, 200, 300)
    }

    private fun createNewTab() {
        if (!tabManager.canCreateTab) {
            toast("标签页数量已达上限")
            return
        }
        saveCurrentTab()
        tabManager.createTab()
        clearHistoryOnNextFinish = true
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK)
        loadCurrentTab()
    }

    private fun switchToTab(index: Int) {
        if (index == tabManager.currentIndex) return
        saveCurrentTab()
        webView.stopLoading()
        tabManager.switchToIndex(index)
        loadCurrentTab()
    }

    private fun closeTab(index: Int) {
        val wasCurrent = index == tabManager.currentIndex
        tabManager.closeTab(index)
        if (wasCurrent) {
            clearHistoryOnNextFinish = true
            webView.stopLoading()
            webView.loadUrl(ABOUT_BLANK)
            loadCurrentTab()
        }
    }

    private fun closeAllTabs() {
        tabManager.clearAllTabs()
        clearHistoryOnNextFinish = true
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK)
        loadCurrentTab()
    }

    // --- Bookmarks and History ---

    private fun loadBookmarks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = bookmarkManager.getAllBookmarks()
            withContext(Dispatchers.Main) { bookmarks = result }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = historyManager.getAllHistory(limit = 100)
            withContext(Dispatchers.Main) { history = result }
        }
    }

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
        // TabManager owns and may recycle Chromium's bitmap on the next navigation. The
        // repository receives an independent copy only when the edited URL is still the
        // current page; otherwise using the current site's icon would mislabel the tile.
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
                bookmarkManager.addBookmark(cleanTitle, normalized)
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
                    toast(getString(R.string.bookmark_save_failed))
                    return@withContext
                }
                if (updatedShortcuts != null) homeShortcuts = updatedShortcuts
                bookmarkDraft = null
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
                        else -> getString(R.string.bookmark_added)
                    },
                )
            }
        }
    }

    private fun removeHomeShortcut(shortcut: HomeShortcut) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = homeRepository.removeShortcut(shortcut.id)
            withContext(Dispatchers.Main) {
                homeShortcuts = updated
                toast(getString(R.string.home_shortcut_removed))
            }
        }
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
                    history = emptyList()
                    toast(getString(R.string.clear_data_done))
                }
            }
        }
    }

    // --- BrowserWebViewClient.Listener ---

    override fun isCurrentWebView(view: WebView): Boolean = webViewOrNull === view

    override fun onPageStarted(url: String) {
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
    }

    override fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        documentUrlForWorkers = url
        val resetHistory = clearHistoryOnNextFinish
        if (resetHistory) {
            webView.clearHistory()
            clearHistoryOnNextFinish = false
        }
        state.onPageFinished(url, if (resetHistory) false else canGoBack, canGoForward)
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

        // Detect the active media element for cast preference. The document-start tracker
        // handles cross-origin iframes; older providers use the one-shot polling fallback.
        startPlayingVideoDetection()
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
                tracker.probe()
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
            toast("${getString(R.string.page_load_error)}: $description")
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
        toast("已阻止不安全网页")
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
        toast(if (crashed) "页面进程崩溃，已重建" else "页面进程被系统回收，已重建")
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
            // Some WebView providers reuse the same Bitmap instance for repeated icon
            // callbacks. Recycling it before assigning the callback value leaves the tab
            // holding a bitmap that Chromium (and Compose) can no longer draw.
            if (tab.favicon !== icon) {
                tab.favicon?.recycle()
            }
            tab.favicon = icon
            tabManager.notifyChanged()
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pendingPermissionRequest === request) pendingPermissionRequest = null
    }

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        originalOrientation = requestedOrientation

        // Added to decorView, above the Compose hierarchy, and deliberately outside the
        // composition: this View comes from Chromium with its own lifecycle, so routing it
        // through Compose would buy nothing but a reparenting hazard.
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        view.setBackgroundColor(Color.BLACK)
        setSystemBarsVisible(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onExitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(view)
        fullscreenView = null
        fullscreenCallback = null
        setSystemBarsVisible(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = originalOrientation
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
        handleIntent(intent, isInitial = false)
    }

    /**
     * VIEW intents are how we act as the default browser. On the initial launch with no
     * URL we load the home page; on a later intent with no URL we leave the current page
     * alone, because that is a task switch rather than a request to navigate.
     */
    private fun handleIntent(intent: Intent, isInitial: Boolean) {
        val url = intentNavigationText(intent)
        when {
            !url.isNullOrBlank() -> {
                // A link handed in by another app is a navigation request, not a request to
                // keep whatever bottom sheet was open. Close transient surfaces first so the
                // destination page is immediately visible (and cannot be covered by a stale
                // bookmark/cast sheet).
                sheet = null
                bookmarkDraft = null
                navigate(url)
            }
            isInitial && state.currentUrl == ABOUT_BLANK -> goHome()
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
                    fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
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
            finish()
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

    override fun onPause() {
        super.onPause()
        // Suspends timers and JS so a backgrounded page cannot keep burning CPU. Media
        // keeps playing: onPause on the WebView itself would kill audio, which is wrong
        // for a browser.
        webViewOrNull?.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webViewOrNull?.resumeTimers()
    }

    override fun onDestroy() {
        mediaProbeJob?.cancel()
        mediaProbeJob = null
        fullscreenView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { fullscreenCallback?.onCustomViewHidden() }
            fullscreenView = null
            fullscreenCallback = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setSystemBarsVisible(true)
        }
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
        if (::tabManager.isInitialized) webViewOrNull?.let { tabManager.saveCurrentState(it) }
        if (::normalTabManager.isInitialized && (!::privacy.isInitialized || !privacy.isIncognito)) {
            normalTabManager.saveMetadata(this, NORMAL_TABS_PREFS)
        }

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
        // API 33+ (the minimum supported platform) shows its own copy confirmation.
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
        const val EXIT_CONFIRM_WINDOW_MS = 2_000L
        const val NORMAL_TABS_PREFS = "normal_tabs"
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
