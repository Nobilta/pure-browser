package com.mybrowser.backup

import android.app.Application
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.MAX_DOWNLOAD_THREADS
import com.mybrowser.site.SiteSettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsBackupCodecTest {

    private lateinit var encoded: String

    @Before fun setup() {
        encoded = SettingsBackupCodec.encode(fullBackup())
    }

    @Test fun roundTripPreservesEveryGroupAndField() {
        val decoded = SettingsBackupCodec.decode(encoded)
        assertEquals(fullBackup(), decoded)
    }

    @Test fun nullGroupsAndFieldsMeanKeepCurrent() {
        val text = JSONObject()
            .put("format", SettingsBackup.FORMAT_ID)
            .put("schemaVersion", 1)
            .put("appVersion", "1.0")
            .put("exportedAt", "2026-09-20T00:00:00Z")
            .put("settings", JSONObject().put("browser", JSONObject().put("theme", "LIGHT")))
            .toString()
        val decoded = SettingsBackupCodec.decode(text)
        assertEquals("LIGHT", decoded.settings.browser?.theme)
        assertNull(decoded.settings.browser?.bottomAddressBar)
        assertNull(decoded.settings.browser?.video)
        assertNull(decoded.settings.home)
        assertNull(decoded.settings.search)
        assertNull(decoded.settings.downloads)
        assertNull(decoded.settings.filtering)
        assertNull(decoded.settings.sites)
    }

    @Test fun explicitDefaultsAreValuesNotMissingFields() {
        // false, 1 and empty lists are deliberate choices and must survive decoding.
        val text = JSONObject()
            .put("format", SettingsBackup.FORMAT_ID)
            .put("schemaVersion", 1)
            .put("appVersion", "1.0")
            .put("exportedAt", "2026-09-20T00:00:00Z")
            .put("settings", JSONObject()
                .put("browser", JSONObject().put("swipeTabs", false))
                .put("downloads", JSONObject().put("threadCount", 1).put("unmeteredOnly", false))
                .put("search", JSONObject().put("customEngines", JSONArray()))
                .put("filtering", JSONObject()
                    .put("builtIns", JSONArray())
                    .put("customSubscriptions", JSONArray()))
                .put("sites", JSONArray()))
            .toString()
        val decoded = SettingsBackupCodec.decode(text)
        assertEquals(false, decoded.settings.browser?.swipeTabs)
        assertEquals(1, decoded.settings.downloads?.threadCount)
        assertEquals(false, decoded.settings.downloads?.unmeteredOnly)
        assertEquals(emptyList<BackupCustomEngine>(), decoded.settings.search?.customEngines)
        assertEquals(emptyList<BackupBuiltInSubscription>(), decoded.settings.filtering?.builtIns)
        assertEquals(emptyList<BackupCustomSubscription>(), decoded.settings.filtering?.customSubscriptions)
        assertEquals(emptyList<BackupSite>(), decoded.settings.sites)
    }

    @Test fun unknownFieldsAreIgnored() {
        val root = JSONObject(encoded)
        root.put("futureField", "value")
        root.getJSONObject("settings").getJSONObject("browser").put("futureToggle", true)
        root.getJSONObject("settings").getJSONObject("browser")
            .getJSONObject("video").put("futureRate", 3.0)
        val decoded = SettingsBackupCodec.decode(root.toString())
        assertEquals(fullBackup(), decoded)
    }

    @Test fun rejectsNonJsonAndWrongEnvelope() {
        reject("not json at all")
        reject(JSONObject().put("format", "other-app").put("schemaVersion", 1).toString())
        reject(JSONObject()
            .put("format", SettingsBackup.FORMAT_ID)
            .put("schemaVersion", 2)
            .put("settings", JSONObject())
            .toString())
        reject(JSONObject()
            .put("format", SettingsBackup.FORMAT_ID)
            .put("schemaVersion", 1)
            .toString())
    }

    @Test fun rejectsWrongTypesInsideGroups() {
        reject(edit { it.getJSONObject("settings").getJSONObject("browser").put("incognitoEnabled", "yes") })
        reject(edit { it.getJSONObject("settings").getJSONObject("browser").put("theme", false) })
        reject(edit { it.getJSONObject("settings").getJSONObject("downloads").put("threadCount", "eight") })
        reject(edit { it.getJSONObject("settings").getJSONObject("downloads").put("threadCount", 1.5) })
        reject(edit { it.getJSONObject("settings").getJSONArray("sites").put(0, "https://example.com") })
    }

    @Test fun rejectsInvalidEnumValues() {
        reject(edit { it.getJSONObject("settings").getJSONObject("browser").put("theme", "BLUE") })
        reject(edit { it.getJSONObject("settings").getJSONObject("home").put("mode", "WIDGET") })
        reject(edit { it.getJSONObject("settings").getJSONObject("downloads").put("directoryModeHint", "SD_CARD") })
    }

    @Test fun rejectsOutOfRangeValues() {
        reject(edit { it.getJSONObject("settings").getJSONObject("home").put("fixedUrl", "ftp://example.com/") })
        reject(edit { it.getJSONObject("settings").getJSONObject("downloads").put("threadCount", MAX_DOWNLOAD_THREADS + 1) })
        reject(edit {
            it.getJSONObject("settings").getJSONObject("browser").getJSONObject("video").put("boostRate", 99.0)
        })
        reject(edit {
            it.getJSONObject("settings").getJSONArray("sites").getJSONObject(0)
                .getJSONObject("preferences").put("textZoom", 300)
        })
        reject(edit {
            it.getJSONObject("settings").getJSONArray("sites").getJSONObject(0)
                .getJSONObject("preferences").put("desktopWidth", 800)
        })
    }

    @Test fun rejectsInvalidSearchEngines() {
        reject(edit {
            it.getJSONObject("settings").getJSONObject("search")
                .getJSONArray("customEngines").getJSONObject(0).put("id", "Not Valid!")
        })
        reject(edit {
            it.getJSONObject("settings").getJSONObject("search")
                .getJSONArray("customEngines").getJSONObject(0).put("name", "")
        })
        reject(edit {
            it.getJSONObject("settings").getJSONObject("search")
                .getJSONArray("customEngines").getJSONObject(0)
                .put("suggestUrlTemplate", "javascript:alert(1)")
        })
        val dupId = JSONObject(encoded)
        val search = dupId.getJSONObject("settings").getJSONObject("search")
        val engines = search.getJSONArray("customEngines")
        engines.put(engines.getJSONObject(0)) // exact duplicate id and name
        reject(dupId.toString())
    }

    @Test fun rejectsInvalidFilterSubscriptions() {
        reject(edit {
            it.getJSONObject("settings").getJSONObject("filtering")
                .getJSONArray("customSubscriptions").getJSONObject(0).put("url", "ftp://filters.example.com/list")
        })
        val dupUrl = JSONObject(encoded)
        val filtering = dupUrl.getJSONObject("settings").getJSONObject("filtering")
        val subs = filtering.getJSONArray("customSubscriptions")
        subs.put(subs.getJSONObject(0))
        reject(dupUrl.toString())
    }

    @Test fun rejectsInvalidSites() {
        reject(edit {
            it.getJSONObject("settings").getJSONArray("sites").getJSONObject(0).put("origin", "https://EXAMPLE.com")
        })
        reject(edit {
            it.getJSONObject("settings").getJSONArray("sites").getJSONObject(0).put("origin", "not a url")
        })
        val dupOrigin = JSONObject(encoded)
        val sites = dupOrigin.getJSONObject("settings").getJSONArray("sites")
        sites.put(sites.getJSONObject(0))
        reject(dupOrigin.toString())
    }

    @Test fun rejectsOverLimitLists() {
        val tooManyEngines = JSONObject(encoded)
        val engines = tooManyEngines.getJSONObject("settings").getJSONObject("search").getJSONArray("customEngines")
        while (engines.length() <= 12) engines.put(engines.getJSONObject(0))
        reject(tooManyEngines.toString())

        val tooManySites = JSONObject(encoded)
        val sites = tooManySites.getJSONObject("settings").getJSONArray("sites")
        while (sites.length() <= SiteSettingsRepository.MAX_SITES) {
            sites.put(JSONObject(sites.getJSONObject(0).toString()).put("origin", "https://site${sites.length()}.example.com"))
        }
        reject(tooManySites.toString())
    }

    @Test fun rejectsWrongGroupTypesInsteadOfSkippingThem() {
        // A group that is not an object — or sites that is not an array — is a type
        // error, not an absent group: silently skipping it would report the import as
        // applied while the user's file asked for changes.
        reject(edit { it.getJSONObject("settings").put("browser", "LIGHT") })
        reject(edit { it.getJSONObject("settings").put("home", 3) })
        reject(edit { it.getJSONObject("settings").put("filtering", JSONArray()) })
        reject(edit { it.getJSONObject("settings").put("sites", JSONObject()) })
        reject(edit { it.put("settings", JSONArray()) })
        reject(JSONObject()
            .put("format", SettingsBackup.FORMAT_ID)
            .put("appVersion", "1.0")
            .put("exportedAt", "2026-09-20T00:00:00Z")
            .put("settings", JSONObject())
            .toString())
    }

    @Test fun enhancedPlaybackTriStateRoundTripsThroughJson() {
        val base = fullBackup()
        val backup = base.copy(settings = base.settings.copy(
            sites = listOf(
                BackupSite("https://null.example.com", BackupSitePreferences(
                    desktop = true, enhancedPlayback = BackupOptional.Present(null),
                )),
                BackupSite("https://unset.example.com", BackupSitePreferences()),
                BackupSite("https://off.example.com", BackupSitePreferences(
                    enhancedPlayback = BackupOptional.Present(false),
                )),
            ),
        ))
        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup))
        val sites = decoded.settings.sites!!
        assertEquals(BackupOptional.Present(null), sites[0].preferences.enhancedPlayback)
        assertEquals(BackupOptional.Absent, sites[1].preferences.enhancedPlayback)
        assertEquals(BackupOptional.Present(false), sites[2].preferences.enhancedPlayback)
    }

    private fun reject(text: String) {
        try {
            SettingsBackupCodec.decode(text)
            throw AssertionError("decode must reject this file")
        } catch (_: SettingsBackupException) {
        }
    }

    @Test fun rejectsOversizedAndWronglyTypedPreviewMetadata() {
        listOf("appVersion" to SettingsBackupCodec.MAX_APP_VERSION_CHARS,
            "exportedAt" to SettingsBackupCodec.MAX_EXPORTED_AT_CHARS).forEach { (key, limit) ->
            reject(edit { it.put(key, "x".repeat(limit + 1)) })
            reject(edit { it.put(key, 123) })
            reject(edit { it.put(key, JSONObject()) })
        }
        val crafted = edit { it.put("appVersion", "v".repeat(1_500_000)) }
        assertTrue(crafted.toByteArray().size < SettingsBackupCodec.MAX_FILE_BYTES)
        reject(crafted)
    }

    @Test fun acceptsMetadataBoundariesAndMissingOptionalMetadata() {
        val boundary = SettingsBackupCodec.decode(edit {
            it.put("appVersion", "v".repeat(SettingsBackupCodec.MAX_APP_VERSION_CHARS))
            it.put("exportedAt", "t".repeat(SettingsBackupCodec.MAX_EXPORTED_AT_CHARS))
        })
        assertEquals(SettingsBackupCodec.MAX_APP_VERSION_CHARS, boundary.appVersion.length)
        assertEquals(SettingsBackupCodec.MAX_EXPORTED_AT_CHARS, boundary.exportedAt.length)
        val absent = SettingsBackupCodec.decode(edit { it.remove("appVersion"); it.put("exportedAt", JSONObject.NULL) })
        assertEquals("", absent.appVersion)
        assertEquals("", absent.exportedAt)
    }

    @Test fun boundsBuiltinIdsAndCountWhileKeepingUnknownIdsCompatible() {
        fun withIds(ids: List<String>) = edit { root ->
            val array = JSONArray()
            ids.forEach { array.put(JSONObject().put("id", it).put("enabled", true)) }
            root.getJSONObject("settings").getJSONObject("filtering").put("builtIns", array)
        }
        reject(withIds(listOf("x".repeat(SettingsBackupCodec.MAX_BUILT_IN_ID_CHARS + 1))))
        reject(withIds(listOf(" ")))
        reject(withIds(List(SettingsBackupCodec.MAX_BUILT_IN_ENTRIES + 1) { "future_$it" }))
        val allowed = List(SettingsBackupCodec.MAX_BUILT_IN_ENTRIES) { "$it".padEnd(SettingsBackupCodec.MAX_BUILT_IN_ID_CHARS, 'x') }
        assertEquals(allowed, SettingsBackupCodec.decode(withIds(allowed)).settings.filtering!!.builtIns!!.map { it.id })
    }

    /** Re-encodes the full sample with one edit applied, so rejections target a real field. */
    private fun edit(block: (JSONObject) -> Unit): String {
        val root = JSONObject(encoded)
        block(root)
        return root.toString()
    }

    private fun fullBackup() = SettingsBackup(
        format = SettingsBackup.FORMAT_ID,
        schemaVersion = SettingsBackup.SCHEMA_VERSION,
        appVersion = "1.2.3",
        exportedAt = "2026-09-20T00:00:00Z",
        settings = BackupSettings(
            browser = BackupBrowser(
                theme = "DARK",
                bottomAddressBar = true,
                swipeTabs = false,
                autoCheckUpdates = true,
                incognitoEnabled = true,
                searchSuggestionsEnabled = false,
                privateSearchSuggestionsEnabled = true,
                browserFullscreenEnabled = true,
                video = BackupVideo(
                    automaticPip = true,
                    backgroundPlayback = false,
                    enhancedControls = true,
                    verticalGestures = true,
                    horizontalSeek = false,
                    holdToBoost = true,
                    boostRate = 2f,
                    landscapeFullscreen = false,
                    rememberSpeed = true,
                    preferredSpeed = 1.5f,
                ),
            ),
            home = BackupHome(
                mode = "FIXED_URL",
                fixedUrl = "https://home.example.com/",
                restoreLastSession = true,
            ),
            search = BackupSearch(
                currentEngineId = "custom_1690000000000_1234",
                customEngines = listOf(
                    BackupCustomEngine(
                        id = "custom_1690000000000_1234",
                        name = "Mine",
                        template = "https://search.example.com/?q={query}",
                        suggestUrlTemplate = "https://suggest.example.com/complete?q={query}",
                    ),
                ),
            ),
            downloads = BackupDownloads(
                threadCount = 3,
                unmeteredOnly = true,
                directoryModeHint = DownloadDestinationMode.CUSTOM_DIRECTORY.name,
            ),
            filtering = BackupFiltering(
                enabled = false,
                autoUpdate = true,
                builtIns = listOf(BackupBuiltInSubscription("easylist", true)),
                customSubscriptions = listOf(
                    BackupCustomSubscription("Fixture", "https://filters.example.com/list.txt", false),
                ),
            ),
            sites = listOf(
                BackupSite(
                    origin = "https://example.com",
                    preferences = BackupSitePreferences(
                        filtering = false,
                        javaScript = false,
                        images = true,
                        thirdPartyCookies = false,
                        desktop = true,
                        textZoom = 130,
                        webDarkening = false,
                        desktopWidth = 1280,
                        enhancedPlayback = BackupOptional.Present(false),
                    ),
                ),
            ),
        ),
    )

    @Test fun sampleFileStaysUnderTheSizeCap() {
        assertTrue(encoded.toByteArray().size < SettingsBackupCodec.MAX_FILE_BYTES)
    }
}
