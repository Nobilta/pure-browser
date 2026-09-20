package com.mybrowser.backup

import android.content.Context
import com.mybrowser.data.BrowserPreferencesRepository
import com.mybrowser.data.ThemeMode
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettingsRepository
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import com.mybrowser.home.HomeRepository
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchEngineManager
import com.mybrowser.site.SiteSettings
import com.mybrowser.site.SiteSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** What the user sees before applying an import: groups and change counts, no storage writes. */
data class ImportPreview(
    val appVersion: String,
    val exportedAt: String,
    /** Group ids present in the file: browser, home, search, downloads, filtering, sites. */
    val groups: List<String>,
    /** Non-null when the import would switch the incognito wish to this value. */
    val incognitoSwitch: Boolean?,
    /** (new, old) custom engine counts when the file replaces the list. */
    val engineReplaces: Pair<Int, Int>?,
    /** (new, old) custom subscription counts when the file replaces the list. */
    val subscriptionReplaces: Pair<Int, Int>?,
    /** (new, old) site counts when the file replaces migratable site preferences. */
    val siteReplaces: Pair<Int, Int>?,
    val unknownBuiltInIds: List<String>,
    val directoryHintCustom: Boolean,
    /** The normal-mode site store is unreadable; the sites group must not be applied. */
    val siteStoreUnreadable: Boolean,
)

/** Honest per-group outcome: exactly what was written and what failed. */
data class ApplyResult(val applied: List<String>, val failed: List<String>)

/**
 * Collects a whitelist export and applies an import through the existing repositories.
 *
 * Groups are independent: each commits one preference-file edit and confirms the disk
 * result before being reported as applied. There is no cross-group transaction; the
 * result explicitly reports partial success when another group fails.
 */
class SettingsTransfer(
    context: Context,
    private val filter: FilterController,
    private val filterSubscriptions: FilterSubscriptions,
    private val sites: SiteSettingsRepository,
) {
    private val appContext = context.applicationContext

    companion object {
        /** One import at a time, across every SettingsTransfer instance. */
        private val importLock = Mutex()
    }

    fun collect(appVersion: String): SettingsBackup {
        val preferences = BrowserPreferencesRepository(appContext).load()
        val home = HomeRepository(appContext).loadSettings()
        val engines = SearchEngineManager(appContext)
        val downloads = DownloadSettingsRepository(appContext).load()

        return SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = SettingsBackup.SCHEMA_VERSION,
            appVersion = appVersion,
            exportedAt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .format(java.time.Instant.now().atZone(java.time.ZoneOffset.UTC)),
            settings = BackupSettings(
                browser = BackupBrowser(
                    theme = preferences.theme.name,
                    bottomAddressBar = preferences.bottomAddressBar,
                    swipeTabs = preferences.swipeTabs,
                    autoCheckUpdates = preferences.autoCheckUpdates,
                    incognitoEnabled = preferences.incognitoEnabled,
                    searchSuggestionsEnabled = preferences.searchSuggestionsEnabled,
                    privateSearchSuggestionsEnabled = preferences.privateSearchSuggestionsEnabled,
                    browserFullscreenEnabled = preferences.browserFullscreenEnabled,
                    video = BackupVideo(
                        automaticPip = preferences.video.automaticPip,
                        backgroundPlayback = preferences.video.backgroundPlayback,
                        enhancedControls = preferences.video.enhancedControls,
                        verticalGestures = preferences.video.verticalGestures,
                        horizontalSeek = preferences.video.horizontalSeek,
                        holdToBoost = preferences.video.holdToBoost,
                        boostRate = preferences.video.boostRate,
                        landscapeFullscreen = preferences.video.landscapeFullscreen,
                        rememberSpeed = preferences.video.rememberSpeed,
                        preferredSpeed = preferences.video.preferredSpeed,
                    ),
                ),
                home = BackupHome(
                    mode = home.mode.name,
                    fixedUrl = home.fixedUrl,
                    restoreLastSession = home.restoreLastSession,
                ),
                search = BackupSearch(
                    currentEngineId = engines.getCurrentEngine().id,
                    customEngines = engines.getAvailableEngines().filter { it.isCustom }.map {
                        BackupCustomEngine(it.id, it.name, it.searchUrlTemplate, it.suggestUrl)
                    },
                ),
                downloads = BackupDownloads(
                    threadCount = downloads.threadCount,
                    unmeteredOnly = downloads.unmeteredOnly,
                    directoryModeHint = downloads.destinationMode.name,
                ),
                filtering = BackupFiltering(
                    enabled = filter.enabled.value,
                    autoUpdate = filterSubscriptions.autoUpdate.value,
                    builtIns = filterSubscriptions.subscriptions.value
                        .filter { it.builtIn }.map { BackupBuiltInSubscription(it.id, it.enabled) },
                    customSubscriptions = filterSubscriptions.subscriptions.value
                        .filter { !it.builtIn }.map { BackupCustomSubscription(it.name, it.url, it.enabled) },
                ),
                sites = sites.entries.value.map { (origin, settings) ->
                    BackupSite(
                        origin = origin,
                        preferences = BackupSitePreferences(
                            filtering = settings.filtering,
                            javaScript = settings.javaScript,
                            images = settings.images,
                            thirdPartyCookies = settings.thirdPartyCookies,
                            desktop = settings.desktop,
                            textZoom = settings.textZoom,
                            webDarkening = settings.webDarkening,
                            desktopWidth = settings.desktopWidth,
                            enhancedPlayback = BackupOptional.Present(settings.enhancedPlayback),
                        ),
                    )
                },
            ),
        )
    }

    fun preview(backup: SettingsBackup): ImportPreview {
        val preferences = BrowserPreferencesRepository(appContext).load()
        val engines = SearchEngineManager(appContext)
        val settings = backup.settings
        val knownBuiltIns = FilterSubscriptions.BUILT_INS.map { it.id }.toSet()

        return ImportPreview(
            appVersion = backup.appVersion,
            exportedAt = backup.exportedAt,
            groups = buildList {
                settings.browser?.let { add("browser") }
                settings.home?.let { add("home") }
                settings.search?.let { add("search") }
                settings.downloads?.let { add("downloads") }
                settings.filtering?.let { add("filtering") }
                settings.sites?.let { add("sites") }
            },
            incognitoSwitch = settings.browser?.incognitoEnabled
                ?.takeIf { it != preferences.incognitoEnabled },
            engineReplaces = settings.search?.customEngines?.let { new ->
                new.size to engines.getAvailableEngines().count { it.isCustom }
            },
            subscriptionReplaces = settings.filtering?.customSubscriptions?.let { new ->
                new.size to filterSubscriptions.subscriptions.value.count { !it.builtIn }
            },
            siteReplaces = settings.sites?.let { new ->
                new.size to sites.entries.value.size
            },
            unknownBuiltInIds = settings.filtering?.builtIns.orEmpty()
                .map { it.id }.filter { it !in knownBuiltIns },
            directoryHintCustom = settings.downloads?.directoryModeHint == DownloadDestinationMode.CUSTOM_DIRECTORY.name,
            siteStoreUnreadable = sites.needsRepair.value,
        )
    }

    /**
     * Everything the file asks for that pure validation can decide, checked before any
     * storage is touched: engine list rules, the final current-engine reference, and
     * subscription list rules. Returns the group ids that would fail; an empty list
     * means the file is consistent and the writes may begin.
     */
    private fun validateForApply(backup: SettingsBackup): List<String> {
        val invalid = mutableListOf<String>()
        val settings = backup.settings

        settings.search?.let { search ->
            runCatching {
                val manager = SearchEngineManager(appContext)
                val replacement = search.customEngines?.map {
                    SearchEngine(
                        id = it.id, name = it.name, searchUrlTemplate = it.template,
                        suggestUrl = it.suggestUrlTemplate, isCustom = true,
                    )
                }
                if (replacement != null) {
                    require(manager.isValidCustomEngineList(replacement)) { "Invalid custom engine list" }
                }
                // The final current id must resolve no matter whether the file replaced
                // the custom list, pointed at one, or kept the retained engine.
                val finalEngines = SearchEngine.BUILTIN_ENGINES +
                    (replacement ?: manager.getAvailableEngines().filter { it.isCustom })
                val finalId = search.currentEngineId ?: manager.getCurrentEngine().id
                require(finalEngines.any { it.id == finalId }) {
                    "currentEngineId does not resolve: $finalId"
                }
            }.onFailure { invalid += "search" }
        }

        settings.filtering?.let { filtering ->
            runCatching {
                val customs = filtering.customSubscriptions ?: return@runCatching
                FilterSubscriptions.validateCustomLists(customs.map { Triple(it.name, it.url, it.enabled) })
            }.onFailure { invalid += "filtering" }
        }
        return invalid
    }

    suspend fun apply(backup: SettingsBackup): ApplyResult {
        // One import at a time: a second confirmation racing the first must not interleave
        // group writes from two files.
        if (!importLock.tryLock()) {
            val groups = buildList {
                val s = backup.settings
                s.browser?.let { add("browser") }
                s.home?.let { add("home") }
                s.search?.let { add("search") }
                s.downloads?.let { add("downloads") }
                s.filtering?.let { add("filtering") }
                s.sites?.let { add("sites") }
            }
            return ApplyResult(applied = emptyList(), failed = groups)
        }
        try {
            // Nothing is written until every group passes validation: a file with a good
            // browser group and a bad search template must not apply half of itself.
            val invalid = validateForApply(backup)
            if (invalid.isNotEmpty()) {
                return ApplyResult(applied = emptyList(), failed = invalid)
            }
            return withContext(Dispatchers.IO) { applyValidated(backup) }
        } finally {
            importLock.unlock()
        }
    }

    private suspend fun applyValidated(backup: SettingsBackup): ApplyResult {
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val settings = backup.settings

        settings.browser?.let { browser ->
            runCatching {
                val repository = BrowserPreferencesRepository(appContext)
                var next = repository.load()
                browser.theme?.let { next = next.copy(theme = ThemeMode.valueOf(it)) }
                browser.bottomAddressBar?.let { next = next.copy(bottomAddressBar = it) }
                browser.swipeTabs?.let { next = next.copy(swipeTabs = it) }
                browser.autoCheckUpdates?.let { next = next.copy(autoCheckUpdates = it) }
                browser.incognitoEnabled?.let { next = next.copy(incognitoEnabled = it) }
                browser.searchSuggestionsEnabled?.let { next = next.copy(searchSuggestionsEnabled = it) }
                browser.privateSearchSuggestionsEnabled?.let { next = next.copy(privateSearchSuggestionsEnabled = it) }
                browser.browserFullscreenEnabled?.let { next = next.copy(browserFullscreenEnabled = it) }
                browser.video?.let { video ->
                    val current = next.video
                    next = next.copy(video = current.copy(
                        automaticPip = video.automaticPip ?: current.automaticPip,
                        backgroundPlayback = video.backgroundPlayback ?: current.backgroundPlayback,
                        enhancedControls = video.enhancedControls ?: current.enhancedControls,
                        verticalGestures = video.verticalGestures ?: current.verticalGestures,
                        horizontalSeek = video.horizontalSeek ?: current.horizontalSeek,
                        holdToBoost = video.holdToBoost ?: current.holdToBoost,
                        boostRate = video.boostRate ?: current.boostRate,
                        landscapeFullscreen = video.landscapeFullscreen ?: current.landscapeFullscreen,
                        rememberSpeed = video.rememberSpeed ?: current.rememberSpeed,
                        preferredSpeed = video.preferredSpeed ?: current.preferredSpeed,
                    ))
                }
                repository.save(next, confirmed = true)
            }.onSuccess { applied += "browser" }.onFailure { failed += "browser" }
        }

        settings.home?.let { home ->
            runCatching {
                val repository = HomeRepository(appContext)
                repository.importSettings(
                    home.mode?.let(HomepageMode::valueOf), home.fixedUrl, home.restoreLastSession,
                )
            }.onSuccess { applied += "home" }.onFailure { failed += "home" }
        }

        settings.search?.let { search ->
            runCatching {
                val manager = SearchEngineManager(appContext)
                manager.importSettings(search.customEngines?.map {
                    SearchEngine(
                        id = it.id, name = it.name, searchUrlTemplate = it.template,
                        suggestUrl = it.suggestUrlTemplate, isCustom = true,
                    )
                }, search.currentEngineId)
            }.onSuccess { applied += "search" }.onFailure { failed += "search" }
        }

        settings.downloads?.let { downloads ->
            runCatching {
                val repository = DownloadSettingsRepository(appContext)
                repository.importSettings(
                    downloads.threadCount, downloads.unmeteredOnly,
                    downloads.directoryModeHint == DownloadDestinationMode.SYSTEM_DOWNLOADS.name,
                )
            }.onSuccess { applied += "downloads" }.onFailure { failed += "downloads" }
        }

        settings.filtering?.let { filtering ->
            runCatching {
                // List and switches share a single confirmed preference-file edit.
                val known = FilterSubscriptions.BUILT_INS.map { it.id }.toSet()
                val builtInStates = filtering.builtIns.orEmpty()
                    .filter { it.id in known }.associate { it.id to it.enabled }
                // Null (absent in the file) keeps the current custom subscriptions;
                // only an explicit list replaces or clears them.
                val customs = filtering.customSubscriptions
                    ?.map { Triple(it.name, it.url, it.enabled) }
                val outcome = filterSubscriptions.importConfiguration(
                    builtInStates, customs, filtering.enabled, filtering.autoUpdate,
                )
                require(outcome.ok) { "Unable to import filter configuration" }
            }.onSuccess { applied += "filtering" }.onFailure { failed += "filtering" }
        }

        settings.sites?.takeIf { !sites.needsRepair.value }?.let { siteList ->
            runCatching {
                val transforms = siteList.associate { site ->
                    val p = site.preferences
                    site.origin to { current: SiteSettings ->
                        current.copy(
                            filtering = p.filtering ?: current.filtering,
                            javaScript = p.javaScript ?: current.javaScript,
                            images = p.images ?: current.images,
                            thirdPartyCookies = p.thirdPartyCookies ?: current.thirdPartyCookies,
                            desktop = p.desktop ?: current.desktop,
                            textZoom = p.textZoom ?: current.textZoom,
                            webDarkening = p.webDarkening ?: current.webDarkening,
                            desktopWidth = p.desktopWidth ?: current.desktopWidth,
                            enhancedPlayback = when (val enhanced = p.enhancedPlayback) {
                                is BackupOptional.Present -> enhanced.value
                                BackupOptional.Absent -> current.enhancedPlayback
                            },
                        )
                    }
                }
                sites.applyImported(transforms)
            }.onSuccess { applied += "sites" }.onFailure { failed += "sites" }
        } ?: settings.sites?.let { failed += "sites" }

        return ApplyResult(applied, failed)
    }
}
