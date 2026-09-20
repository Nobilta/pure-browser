package com.mybrowser.backup

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import android.content.Context
import com.mybrowser.core.TextDownloader
import com.mybrowser.data.BookmarkFolders
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.BrowserPreferencesRepository
import com.mybrowser.data.HistoryManager
import com.mybrowser.data.ImportedBookmark
import com.mybrowser.data.ImportedHistory
import com.mybrowser.data.ThemeMode
import com.mybrowser.download.DownloadSettingsRepository
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import com.mybrowser.home.HomeRepository
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngineManager
import com.mybrowser.site.SiteCapability
import com.mybrowser.site.SitePermission
import com.mybrowser.site.SiteSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsTransferTest {
    @Test fun exportWaitsForSavedSubscriptionConfigurationWithoutExplicitInitialization() = runBlocking {
        val warm = FilterSubscriptions(context)
        assertTrue(warm.importConfiguration(mapOf("easylist" to false),
            listOf(Triple("Saved", "https://example.test/list", true))).ok)
        val cold = FilterSubscriptions(context)
        val filter = FilterController(context)
        try {
            val result = transfer(filter, cold, SiteSettingsRepository(context)).collect("0.11")
            val settings = result.backup.settings
            assertEquals("Saved", settings.filtering!!.customSubscriptions!!.single().name)
            assertFalse(settings.filtering!!.builtIns!!.single { it.id == "easylist" }.enabled)
            assertEquals(0, result.omittedBookmarks)
            assertEquals(0, result.omittedHistory)
        } finally { filter.close() }
    }

    @Test fun successfulImportRequestsFirstDownloadsAndReportsTheirCount() = runBlocking {
        val filter = FilterController(context)
        val subscriptions = FilterSubscriptions(context)
        var scheduled = 0
        try {
            val settingsTransfer = transfer(filter, subscriptions, SiteSettingsRepository(context)) { scheduled++ }
            val backup = SettingsBackupCodec.decode("""{"format":"pure-browser-settings","schemaVersion":1,"settings":{
                "filtering":{"customSubscriptions":[{"name":"Imported","url":"https://example.test/list","enabled":true}]}
            }}""")
            val result = settingsTransfer.apply(backup)
            assertEquals(listOf("filtering"), result.applied)
            assertTrue(result.failed.isEmpty())
            assertEquals(1, result.pendingFilterUpdates)
            assertEquals(1, scheduled)
        } finally { filter.close() }
    }

    private lateinit var context: Context

    private var library: BookmarkManager? = null
    private var visits: HistoryManager? = null

    /** A transfer wired to real stores for this test's context, including the library. */
    private fun transfer(
        filter: FilterController,
        subscriptions: FilterSubscriptions,
        sites: SiteSettingsRepository,
        onPendingFilterUpdates: () -> Unit = {},
    ): SettingsTransfer {
        val bookmarks = library ?: BookmarkManager(context).also { library = it }
        val history = visits ?: HistoryManager(context).also { visits = it }
        return SettingsTransfer(context, filter, subscriptions, sites, bookmarks, history, onPendingFilterUpdates)
    }

    @After fun releaseLibraryManagers() {
        // BrowserDatabase is process-wide: a reference left open would hand the next test
        // class this test's database instead of letting it open a fresh one.
        library?.close()
        library = null
        visits?.close()
        visits = null
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf(
            "browser_preferences", "browser_settings", "search_engines", "download_settings",
            "filter_settings", "custom_filters", "site_settings",
        )) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, "filter_subscriptions").deleteRecursively()
        File(context.cacheDir, "filter_lists").deleteRecursively()
        clearLibrary()
    }

    @Test fun roundTripRestoresEveryGroupOnAFreshDevice() = runBlocking {
        val filter = FilterController(context)
        val subscriptions = FilterSubscriptions(context, filter) { _, _, _ ->
            TextDownloader.Response("! fixture\n||ads.example.com^\n", "\"v1\"", "yesterday")
        }
        val sites = SiteSettingsRepository(context)
        val preferences = BrowserPreferencesRepository(context)
        val encoded: String
        try {
            // --- Source device -------------------------------------------------
            preferences.save(preferences.load().copy(
                theme = ThemeMode.DARK,
                searchSuggestionsEnabled = false,
                privateSearchSuggestionsEnabled = true,
                incognitoEnabled = true,
                browserFullscreenEnabled = true,
                video = preferences.load().video.copy(landscapeFullscreen = false),
            ))
            val home = HomeRepository(context)
            home.saveMode(HomepageMode.FIXED_URL)
            home.saveFixedUrl("https://home.example.com/")
            home.saveRestoreLastSession(true)
            val engines = SearchEngineManager(context)
            val custom = engines.addCustomEngine(
                "Mine", "https://search.example.com/?q={query}", "https://suggest.example.com/complete?q={query}")
            engines.setCurrentEngineById(custom.id)
            DownloadSettingsRepository(context).apply {
                setThreadCount(3)
                setUnmeteredOnly(true)
            }
            filter.setEnabled(false)
            subscriptions.initialize()
            subscriptions.setAutoUpdate(false)
            subscriptions.setEnabled("easylist", false)
            assertTrue(subscriptions.add("Fixture", "https://filters.example.com/list.txt"))
            sites.update("https://site.example.com/", { it.copy(filtering = false, desktop = true, textZoom = 140) })
            sites.update("https://other.example.com/", {
                it.withPermission(SiteCapability.CAMERA, SitePermission.ALLOW)
            })
            // The library travels too: a folder, a bookmark and one visit.
            BookmarkManager(context).also { library ->
                assertEquals(1, library.importBookmarks(
                    listOf(ImportedBookmark("Docs", "https://docs.example.com/", listOf("Reading"))),
                ))
                library.close()
            }
            HistoryManager(context).also { visits ->
                visits.addHistory("Visited", "https://visited.example.com/")
                visits.close()
            }

            encoded = SettingsBackupCodec.encode(transfer(filter, subscriptions, sites).collect("9.9.9").backup)

            // --- Target device: the same app with empty storage ----------------
            wipeStorage()
        } finally {
            filter.close()
        }
        val targetFilter = FilterController(context)
        val targetSubscriptions = FilterSubscriptions(context, targetFilter) { _, _, _ ->
            throw IOException("The import path must not fetch anything")
        }
        val targetSites = SiteSettingsRepository(context)
        val result = transfer(targetFilter, targetSubscriptions, targetSites)
            .apply(SettingsBackupCodec.decode(encoded))
        try {
            assertEquals(
                listOf("browser", "home", "search", "downloads", "filtering", "sites", "bookmarks", "history"),
                result.applied,
            )
            assertTrue(result.failed.isEmpty())
            assertEquals(1, result.importedBookmarks)
            assertEquals(1, result.importedHistory)

            // The library survives with its folder path and its visit time.
            val library = BookmarkManager(context)
            try {
                val restored = library.getAllBookmarks().single()
                assertEquals("https://docs.example.com/", restored.url)
                assertEquals("Docs", restored.title)
                assertEquals(
                    listOf("Reading"),
                    BookmarkFolders.path(restored.folderId, library.getFolders()).map { it.title },
                )
            } finally { library.close() }
            HistoryManager(context).also { visits ->
                assertEquals("https://visited.example.com/", visits.getAllHistory().single().url)
                visits.close()
            }

            val restored = BrowserPreferencesRepository(context).load()
            assertEquals(ThemeMode.DARK, restored.theme)
            assertFalse(restored.searchSuggestionsEnabled)
            assertTrue(restored.privateSearchSuggestionsEnabled)
            assertFalse(restored.video.landscapeFullscreen)
            assertTrue(restored.browserFullscreenEnabled)

            val home = HomeRepository(context).loadSettings()
            assertEquals(HomepageMode.FIXED_URL, home.mode)
            assertEquals("https://home.example.com/", home.fixedUrl)
            assertTrue(home.restoreLastSession)

            val engines = SearchEngineManager(context)
            val engine = engines.getAvailableEngines().single { it.name == "Mine" }
            assertEquals("https://suggest.example.com/complete?q={query}", engine.suggestUrl)
            assertEquals(engine.id, engines.getCurrentEngine().id)

            val downloads = DownloadSettingsRepository(context).load()
            assertEquals(3, downloads.threadCount)
            assertTrue(downloads.unmeteredOnly)

            assertFalse(targetFilter.enabled.value)
            assertFalse(targetSubscriptions.autoUpdate.value)
            assertFalse(targetSubscriptions.subscriptions.value.single { it.id == "easylist" }.enabled)
            val importedList = targetSubscriptions.subscriptions.value.single { !it.builtIn }
            assertEquals("Fixture", importedList.name)
            assertEquals("https://filters.example.com/list.txt", importedList.url)
            assertTrue(importedList.enabled)

            // Permissions never leave the device; only migratable preferences return.
            val site = targetSites.entries.value.getValue("https://site.example.com")
            assertFalse(site.filtering)
            assertTrue(site.desktop)
            assertEquals(140, site.textZoom)
            assertEquals(SitePermission.ASK, site.camera)
            assertFalse(targetSites.entries.value.containsKey("https://other.example.com"))
        } finally {
            targetFilter.close()
        }
    }

    @Test fun absentGroupsKeepCurrentSettings() = runBlocking {
        val preferences = BrowserPreferencesRepository(context)
        preferences.save(preferences.load().copy(theme = ThemeMode.DARK, bottomAddressBar = true))
        HomeRepository(context).saveFixedUrl("https://current.example.com/")
        val engines = SearchEngineManager(context)
        val existing = engines.addCustomEngine("Existing", "https://existing.example.com/?q={query}")
        engines.setCurrentEngineById(existing.id)
        val filter = FilterController(context)
        try {
            val backup = SettingsBackup(
                format = SettingsBackup.FORMAT_ID,
                schemaVersion = SettingsBackup.SCHEMA_VERSION,
                appVersion = "9.9.9",
                exportedAt = "2026-09-20T00:00:00Z",
                settings = BackupSettings(browser = BackupBrowser(theme = "LIGHT")),
            )
            val result = transfer(filter, FilterSubscriptions(context, filter), SiteSettingsRepository(context))
                .apply(backup)
            assertEquals(listOf("browser"), result.applied)
            assertTrue(result.failed.isEmpty())

            val restored = BrowserPreferencesRepository(context).load()
            assertEquals(ThemeMode.LIGHT, restored.theme)
            assertTrue(restored.bottomAddressBar)
            assertEquals("https://current.example.com/", HomeRepository(context).loadSettings().fixedUrl)
            val currentEngines = SearchEngineManager(context)
            assertEquals(existing.id, currentEngines.getCurrentEngine().id)
            assertEquals(1, currentEngines.getAvailableEngines().count { it.isCustom })
        } finally {
            filter.close()
        }
    }

    @Test fun sitesImportResetsUnlistedOriginsAndKeepsPermissions() = runBlocking {
        val sites = SiteSettingsRepository(context)
        sites.update("https://a.example.com/", {
            it.copy(filtering = false).withPermission(SiteCapability.CAMERA, SitePermission.ALLOW)
        })
        sites.update("https://b.example.com/", { it.copy(desktop = true, javaScript = false) })
        val backup = SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = SettingsBackup.SCHEMA_VERSION,
            appVersion = "9.9.9",
            exportedAt = "2026-09-20T00:00:00Z",
            settings = BackupSettings(sites = listOf(
                BackupSite("https://a.example.com", BackupSitePreferences(filtering = true)),
                BackupSite("https://c.example.com", BackupSitePreferences(javaScript = false)),
            )),
        )
        val filter = FilterController(context)
        try {
            val result = transfer(filter, FilterSubscriptions(context, filter), sites).apply(backup)
            assertEquals(listOf("sites"), result.applied)
            assertTrue(result.failed.isEmpty())

            val a = sites.entries.value.getValue("https://a.example.com")
            assertTrue(a.filtering)
            assertEquals(SitePermission.ALLOW, a.camera)
            // b is unlisted: migratable choices reset, the entry becomes all-default and drops.
            assertFalse(sites.entries.value.containsKey("https://b.example.com"))
            val c = sites.entries.value.getValue("https://c.example.com")
            assertFalse(c.javaScript)
            assertEquals(SitePermission.ASK, c.camera)
        } finally {
            filter.close()
        }
    }

    @Test fun unreadableSiteStoreBlocksOnlyTheSitesGroup() = runBlocking {
        val sites = SiteSettingsRepository(context)
        sites.update("https://a.example.com/", { it.copy(filtering = false) })
        val corrupt = "{not json"
        assertTrue(context.getSharedPreferences("site_settings", Context.MODE_PRIVATE)
            .edit().putString("sites", corrupt).commit())

        val brokenSites = SiteSettingsRepository(context)
        assertTrue(brokenSites.needsRepair.value)
        val backup = SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = SettingsBackup.SCHEMA_VERSION,
            appVersion = "9.9.9",
            exportedAt = "2026-09-20T00:00:00Z",
            settings = BackupSettings(
                browser = BackupBrowser(theme = "LIGHT"),
                sites = listOf(BackupSite("https://a.example.com", BackupSitePreferences(filtering = true))),
            ),
        )
        val filter = FilterController(context)
        try {
            val result = transfer(filter, FilterSubscriptions(context, filter), brokenSites).apply(backup)
            assertEquals(listOf("browser"), result.applied)
            assertEquals(listOf("sites"), result.failed)
            assertTrue(brokenSites.needsRepair.value)
            // The corrupted bytes stay untouched for the user's explicit repair decision.
            assertEquals(corrupt, context.getSharedPreferences("site_settings", Context.MODE_PRIVATE)
                .getString("sites", null))
            assertEquals(ThemeMode.LIGHT, BrowserPreferencesRepository(context).load().theme)
        } finally {
            filter.close()
        }
    }

    @Test fun danglingCurrentEngineFailsTheSearchGroupWithoutWriting() = runBlocking {
        val engines = SearchEngineManager(context)
        val existing = engines.addCustomEngine("Existing", "https://existing.example.com/?q={query}")
        engines.setCurrentEngineById(existing.id)
        val backup = SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = SettingsBackup.SCHEMA_VERSION,
            appVersion = "9.9.9",
            exportedAt = "2026-09-20T00:00:00Z",
            settings = BackupSettings(search = BackupSearch(
                currentEngineId = "custom_ghost",
                customEngines = listOf(
                    BackupCustomEngine("custom_1_1", "Other", "https://other.example.com/?q={query}"),
                ),
            )),
        )
        val filter = FilterController(context)
        try {
            val result = transfer(filter, FilterSubscriptions(context, filter), SiteSettingsRepository(context))
                .apply(backup)
            assertTrue(result.applied.isEmpty())
            assertEquals(listOf("search"), result.failed)

            val current = SearchEngineManager(context)
            assertEquals(existing.id, current.getCurrentEngine().id)
            assertEquals(listOf(existing), current.getAvailableEngines().filter { it.isCustom })
        } finally {
            filter.close()
        }
    }

    @Test fun previewReportsWhatTheImportWouldChange() = runBlocking {
        val sites = SiteSettingsRepository(context)
        sites.update("https://a.example.com/", { it.copy(filtering = false) })
        val filter = FilterController(context)
        val subscriptions = FilterSubscriptions(context, filter)
        try {
            subscriptions.initialize()
            val backup = SettingsBackup(
                format = SettingsBackup.FORMAT_ID,
                schemaVersion = SettingsBackup.SCHEMA_VERSION,
                appVersion = "9.9.9",
                exportedAt = "2026-09-20T00:00:00Z",
                settings = BackupSettings(
                    browser = BackupBrowser(incognitoEnabled = true),
                    search = BackupSearch(customEngines = listOf(
                        BackupCustomEngine("custom_1_1", "Mine", "https://search.example.com/?q={query}"),
                    )),
                    filtering = BackupFiltering(
                        builtIns = listOf(
                            BackupBuiltInSubscription("easylist", false),
                            BackupBuiltInSubscription("ghost-list", true),
                        ),
                        customSubscriptions = listOf(
                            BackupCustomSubscription("Fixture", "https://filters.example.com/list.txt", true),
                        ),
                    ),
                    sites = listOf(BackupSite("https://a.example.com", BackupSitePreferences(filtering = true))),
                ),
            )
            val preview = transfer(filter, subscriptions, sites).preview(backup)
            assertEquals("9.9.9", preview.appVersion)
            assertEquals(listOf("browser", "search", "filtering", "sites"), preview.groups)
            assertEquals(true, preview.incognitoSwitch)
            assertEquals(1 to 0, preview.engineReplaces)
            assertEquals(1 to 0, preview.subscriptionReplaces)
            assertEquals(1 to 1, preview.siteReplaces)
            assertEquals(listOf("ghost-list"), preview.unknownBuiltInIds)
            assertFalse(preview.directoryHintCustom)
            assertFalse(preview.siteStoreUnreadable)
        } finally {
            filter.close()
        }
    }

    @Test fun subscriptionImportKeepsCustomsWhenAbsentAndClearsWhenEmptyList() = runBlocking {
        val filter = FilterController(context)
        val subscriptions = FilterSubscriptions(context, filter) { _, _, _ ->
            TextDownloader.Response("! fixture\n||ads.example.com^\n", "\"v1\"", "yesterday")
        }
        try {
            subscriptions.initialize()
            assertTrue(subscriptions.add("Kept", "https://kept.example.com/list.txt"))

            // Absent customSubscriptions: the file only flips built-in switches; the
            // user's own lists survive.
            val keepBackup = SettingsBackup(
                format = SettingsBackup.FORMAT_ID,
                schemaVersion = SettingsBackup.SCHEMA_VERSION,
                appVersion = "9.9.9",
                exportedAt = "2026-09-20T00:00:00Z",
                settings = BackupSettings(filtering = BackupFiltering(
                    builtIns = listOf(BackupBuiltInSubscription("easylist", false)),
                )),
            )
            var result = transfer(filter, subscriptions, SiteSettingsRepository(context)).apply(keepBackup)
            assertEquals(listOf("filtering"), result.applied)
            assertTrue(result.failed.isEmpty())
            val kept = subscriptions.subscriptions.value.single { !it.builtIn }
            assertEquals("Kept", kept.name)
            assertFalse(subscriptions.subscriptions.value.single { it.id == "easylist" }.enabled)

            // An explicit empty list clears every custom list — that is a deliberate
            // choice in the file, not an omission.
            val clearBackup = keepBackup.copy(settings = keepBackup.settings.copy(
                filtering = keepBackup.settings.filtering?.copy(customSubscriptions = emptyList()),
            ))
            result = transfer(filter, subscriptions, SiteSettingsRepository(context)).apply(clearBackup)
            assertEquals(listOf("filtering"), result.applied)
            assertTrue(result.failed.isEmpty())
            assertTrue(subscriptions.subscriptions.value.none { !it.builtIn })
        } finally {
            filter.close()
        }
    }

    @Test fun siteEnhancedPlaybackTriStateRestoresNullExplicitAndKeepsAbsent() = runBlocking {
        val sites = SiteSettingsRepository(context)
        sites.update("https://null.example.com/", { it.copy(enhancedPlayback = false) })
        sites.update("https://kept.example.com/", { it.copy(enhancedPlayback = true) })
        val backup = SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = SettingsBackup.SCHEMA_VERSION,
            appVersion = "9.9.9",
            exportedAt = "2026-09-20T00:00:00Z",
            settings = BackupSettings(sites = listOf(
                // Explicit null in the file resets the override to follow the global
                // switch; desktop keeps the entry itself alive after the reset.
                BackupSite("https://null.example.com", BackupSitePreferences(
                    desktop = true, enhancedPlayback = BackupOptional.Present(null),
                )),
                // Absent keeps whatever the device already chose.
                BackupSite("https://kept.example.com", BackupSitePreferences()),
                // Explicit false applies normally.
                BackupSite("https://off.example.com", BackupSitePreferences(enhancedPlayback = BackupOptional.Present(false))),
            )),
        )
        val filter = FilterController(context)
        try {
            val result = transfer(filter, FilterSubscriptions(context, filter), sites).apply(backup)
            assertEquals(listOf("sites"), result.applied)
            assertTrue(result.failed.isEmpty())
            val entries = sites.entries.value
            assertEquals(null, entries.getValue("https://null.example.com").enhancedPlayback)
            assertEquals(true, entries.getValue("https://kept.example.com").enhancedPlayback)
            assertEquals(false, entries.getValue("https://off.example.com").enhancedPlayback)
        } finally {
            filter.close()
        }
    }

    /** Simulates Android's in-memory update even when a disk commit reports failure. */
    private class ObservedContext(base: Context, private val failStore: String? = null) : ContextWrapper(base) {
        val commits = mutableMapOf<String, MutableList<Set<String>>>()
        private val stores = mutableMapOf<String, SharedPreferences>()
        private var failed = false
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val actual = super.getSharedPreferences(name, mode)
            object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = actual.edit()
                    val keys = mutableSetOf<String>()
                    return Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { proxy, method, args ->
                        if (method.name.startsWith("put") || method.name == "remove") keys += args!![0] as String
                        if (method.name == "commit") {
                            commits.getOrPut(name) { mutableListOf() }.add(keys.toSet())
                            if (name == failStore && !failed) {
                                failed = true
                                delegate.apply()
                                return@newProxyInstance false
                            }
                        }
                        val result = method.invoke(delegate, *(args ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    } as SharedPreferences.Editor
                }
            }
        }
    }

    private fun allGroupsBackup() = SettingsBackup(
        SettingsBackup.FORMAT_ID, SettingsBackup.SCHEMA_VERSION, "test", "2026-09-20T00:00:00Z",
        BackupSettings(
            browser = BackupBrowser(theme = "DARK", incognitoEnabled = true, browserFullscreenEnabled = true),
            home = BackupHome(HomepageMode.FIXED_URL.name, "https://home.test/", true),
            search = BackupSearch("custom_fixture", listOf(BackupCustomEngine("custom_fixture", "Fixture", "https://search.test/?q={query}"))),
            downloads = BackupDownloads(threadCount = 8, unmeteredOnly = true, directoryModeHint = "SYSTEM_DOWNLOADS"),
            filtering = BackupFiltering(enabled = false, autoUpdate = false,
                customSubscriptions = listOf(BackupCustomSubscription("Fixture", "https://filter.test/list", true))),
            sites = listOf(BackupSite("https://site.test", BackupSitePreferences(javaScript = false))),
        ),
    )

    @Test fun everyGroupCommitsOnceIncludingFilterListAndBothSwitches() = runBlocking {
        val observed = ObservedContext(context)
        val filter = FilterController(observed)
        val subscriptions = FilterSubscriptions(observed, filter, builtIns = emptyList())
        val observedLibrary = BookmarkManager(observed)
        val observedVisits = HistoryManager(observed)
        try {
            val result = SettingsTransfer(observed, filter, subscriptions, SiteSettingsRepository(observed), observedLibrary, observedVisits).apply(allGroupsBackup())
            assertEquals(listOf("browser", "home", "search", "downloads", "filtering", "sites"), result.applied)
            assertTrue(result.failed.isEmpty())
            assertEquals(6, observed.commits.size)
            assertTrue(observed.commits.values.all { it.size == 1 })
            assertEquals(setOf("subscriptions_manifest", "enabled", "auto_update"), observed.commits.getValue("filter_settings").single())
            assertEquals(setOf("current_engine_id", "custom_engines"), observed.commits.getValue("search_engines").single())
            assertFalse(filter.enabled.value)
            assertFalse(subscriptions.autoUpdate.value)
            val reopened = FilterSubscriptions(context, builtIns = emptyList())
            reopened.initialize()
            assertEquals("Fixture", reopened.subscriptions.value.single().name)
            assertFalse(reopened.autoUpdate.value)
        } finally {
            observedLibrary.close()
            observedVisits.close()
            filter.close()
        }
    }

    @Test fun failedCommitsRestoreTheWholeGroupAndAreNeverReportedAsApplied() = runBlocking {
        for ((group, store) in mapOf(
            "browser" to "browser_preferences", "home" to "browser_settings", "search" to "search_engines",
            "downloads" to "download_settings", "filtering" to "filter_settings", "sites" to "site_settings",
        )) {
            wipeStorage()
            val observed = ObservedContext(context, store)
            val filter = FilterController(observed)
            val subscriptions = FilterSubscriptions(observed, filter, builtIns = emptyList())
            val sites = SiteSettingsRepository(observed)
            val observedLibrary = BookmarkManager(observed)
            val observedVisits = HistoryManager(observed)
            try {
                subscriptions.initialize()
                val before = context.getSharedPreferences(store, Context.MODE_PRIVATE).all.toMap()
                val result = SettingsTransfer(observed, filter, subscriptions, sites, observedLibrary, observedVisits).apply(allGroupsBackup())
                assertEquals("failure group $group", listOf(group), result.failed)
                assertFalse(result.applied.contains(group))
                assertEquals(5, result.applied.size)
                assertEquals("storage restored for $group", before, context.getSharedPreferences(store, Context.MODE_PRIVATE).all)
                assertEquals(2, observed.commits.getValue(store).size) // attempted commit, then rollback
                if (group == "filtering") {
                    assertTrue(filter.enabled.value)
                    assertTrue(subscriptions.autoUpdate.value)
                    assertTrue(subscriptions.subscriptions.value.isEmpty())
                }
                if (group == "sites") assertTrue(sites.entries.value.isEmpty())
            } finally {
                observedLibrary.close()
                observedVisits.close()
                filter.close()
            }
        }
    }

    @Test fun invalidSubscriptionsRejectTheWholeFileBeforeAnyGroupChanges() = runBlocking {
        val urls = listOf(
            "https://example.com/list#fragment", "https://user:pass@example.com/list",
            "https://example.com/" + "a".repeat(2048), FilterSubscriptions.BUILT_INS.first().url,
        )
        val filter = FilterController(context)
        try {
            for (url in urls) {
                val backup = allGroupsBackup().copy(settings = BackupSettings(
                    browser = BackupBrowser(theme = "DARK"),
                    filtering = BackupFiltering(customSubscriptions = listOf(BackupCustomSubscription("Invalid", url, true))),
                ))
                org.junit.Assert.assertThrows(SettingsBackupException::class.java) {
                    SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup))
                }
                val result = transfer(filter, FilterSubscriptions(context, filter), SiteSettingsRepository(context)).apply(backup)
                assertTrue(result.applied.isEmpty())
                assertEquals(listOf("filtering"), result.failed)
                assertEquals(ThemeMode.SYSTEM, BrowserPreferencesRepository(context).load().theme)
            }
        } finally { filter.close() }
    }

    @Test fun oversizedSerializedEngineListIsRejectedBeforeOtherGroupsAreApplied() = runBlocking {
        val template = "https://example.com/?q={query}&x=" + "\"".repeat(1990)
        val engines = (1..12).map { BackupCustomEngine("custom_$it", "Engine $it", template, template) }
        val backup = allGroupsBackup().copy(settings = BackupSettings(
            browser = BackupBrowser(theme = "DARK"), search = BackupSearch("custom_1", engines),
        ))
        val filter = FilterController(context)
        try {
            val result = transfer(filter, FilterSubscriptions(context, filter), SiteSettingsRepository(context))
                .apply(SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup)))
            assertTrue(result.applied.isEmpty())
            assertEquals(listOf("search"), result.failed)
            assertEquals(ThemeMode.SYSTEM, BrowserPreferencesRepository(context).load().theme)
            assertFalse(context.getSharedPreferences("search_engines", Context.MODE_PRIVATE).contains("custom_engines"))
        } finally { filter.close() }
    }

    @Test fun nestedWrongContainerTypesAreRejectedInsteadOfTreatedAsAbsent() {
        for (settings in listOf(
            """{"browser":{"video":3}}""", """{"search":{"customEngines":{}}}""",
            """{"filtering":{"builtIns":{}}}""", """{"filtering":{"customSubscriptions":{}}}""",
        )) {
            org.junit.Assert.assertThrows(SettingsBackupException::class.java) {
                SettingsBackupCodec.decode("""{"format":"pure-browser-settings","schemaVersion":1,"settings":$settings}""")
            }
        }
    }

    @Test fun libraryImportMergesWithoutDeletingAndStaysIdempotent() = runBlocking {
        val filter = FilterController(context)
        val subscriptions = FilterSubscriptions(context, filter)
        val existing = "https://keep.example.com/"
        val incoming = SettingsBackup(
            SettingsBackup.FORMAT_ID, SettingsBackup.SCHEMA_VERSION, "test", "2026-09-20T00:00:00Z",
            BackupSettings(
                bookmarks = BackupBookmarks(
                    folders = listOf(listOf("Empty folder")),
                    entries = listOf(
                        BackupBookmark("Remote title", existing),
                        BackupBookmark("New", "https://new.example.com/", listOf("Reading")),
                    ),
                ),
                history = listOf(
                    BackupHistoryEntry("Remote", existing, 1_500_000_000_000, 7),
                    BackupHistoryEntry("Fresh", "https://fresh.example.com/", 1_600_000_000_000, 3),
                ),
            ),
        )
        try {
            BookmarkManager(context).also { library ->
                library.addBookmark("Local title", existing)
                library.close()
            }
            HistoryManager(context).also { visits ->
                visits.addHistory("Local", existing)
                visits.close()
            }

            val first = transfer(filter, subscriptions, SiteSettingsRepository(context)).apply(incoming)
            assertTrue(first.failed.isEmpty())
            assertEquals(listOf("bookmarks", "history"), first.applied)
            // Only the URL this device did not have is inserted.
            assertEquals(1, first.importedBookmarks)
            assertEquals(1, first.importedHistory)

            val library = BookmarkManager(context)
            try {
                // The existing bookmark keeps its identity and its local title.
                assertEquals("Local title", library.getAllBookmarks().single { it.url == existing }.title)
                assertEquals(2, library.countBookmarks())
                assertEquals(
                    setOf("Reading", "Empty folder"),
                    library.getFolders().map { it.title }.toSet(),
                )
            } finally { library.close() }
            HistoryManager(context).also { visits ->
                val merged = visits.getAllHistory().single { it.url == existing }
                // The local visit is the later one; the imported count is the larger one.
                assertTrue(merged.visitTime > 1_500_000_000_000)
                assertEquals(7, merged.visitCount)
                assertEquals(2, visits.countHistory())
                visits.close()
            }

            // Re-importing the same file must not duplicate rows or inflate counters.
            val second = transfer(filter, subscriptions, SiteSettingsRepository(context)).apply(incoming)
            assertEquals(0, second.importedBookmarks)
            assertEquals(0, second.importedHistory)
            BookmarkManager(context).also { bookmarks ->
                assertEquals(2, bookmarks.countBookmarks())
                bookmarks.close()
            }
            HistoryManager(context).also { visits ->
                assertEquals(2, visits.countHistory())
                assertEquals(7, visits.getAllHistory().single { it.url == existing }.visitCount)
                visits.close()
            }
        } finally { filter.close() }
        Unit
    }

    @Test fun libraryReadersCapRowsAndReportTheTrueTotal() {
        BookmarkManager(context).also { library ->
            assertEquals(3, library.importBookmarks(listOf(
                ImportedBookmark("A", "https://a.example.com/"),
                ImportedBookmark("B", "https://b.example.com/"),
                ImportedBookmark("C", "https://c.example.com/"),
            )))
            assertEquals(2, library.backupBookmarks(2).size)
            assertEquals(3, library.countBookmarks())
            library.close()
        }
        HistoryManager(context).also { visits ->
            assertEquals(3, visits.importHistory(listOf(
                ImportedHistory("A", "https://a.example.com/", 1_600_000_000_000, 2),
                ImportedHistory("B", "https://b.example.com/", 1_600_000_000_001, 1),
                ImportedHistory("C", "https://c.example.com/", 1_600_000_000_002, 1),
            )))
            assertEquals(3, visits.countHistory())
            // Capped reads keep the newest rows, and the count still reports everything.
            assertEquals("https://c.example.com/", visits.backupHistory(1).single().url)
            visits.close()
        }
    }

    private fun wipeStorage() {
        for (name in listOf(
            "browser_preferences", "browser_settings", "search_engines", "download_settings",
            "filter_settings", "custom_filters", "site_settings",
        )) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, "filter_subscriptions").deleteRecursively()
        File(context.cacheDir, "filter_lists").deleteRecursively()
        clearLibrary()
    }

    /** Balanced open/close: the shared SQLite helper must drop back to zero references. */
    private fun clearLibrary() {
        BookmarkManager(context).also { it.clearAll(); it.close() }
        HistoryManager(context).also { it.clearAll(); it.close() }
    }
}
