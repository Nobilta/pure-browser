package com.mybrowser.filter

import android.app.Application
import android.content.Context
import com.mybrowser.core.TextDownloader
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
class FilterSubscriptionsTest {
    @Test fun importedEnabledListsFetchFirstRulesAndFailedDownloadsRemainRetryable() = runBlocking {
        val fetched = mutableListOf<String>()
        var failDownload = true
        val filter = FilterController(context)
        try {
            val lists = FilterSubscriptions(context, filter, builtIns = emptyList()) { url, _, _ ->
                fetched += url
                if (failDownload) throw IOException("offline")
                TextDownloader.Response("! imported\nfixture.test###imported-rule\n")
            }
            val outcome = lists.importConfiguration(emptyMap(), listOf(
                Triple("Enabled", "https://example.test/enabled", true),
                Triple("Disabled", "https://example.test/disabled", false),
            ))
            assertEquals(1, outcome.pendingUpdates)
            assertFalse(lists.updateMissing())
            assertNull(lists.subscriptions.value.first().file)
            assertNotNull(lists.subscriptions.value.first().error)
            failDownload = false
            assertTrue(lists.updateMissing())
            assertTrue(filter.cosmeticCss("https://fixture.test/page").contains("#imported-rule"))
            assertTrue(lists.updateMissing())
            assertEquals(listOf("https://example.test/enabled", "https://example.test/enabled"), fetched)
            assertNull(lists.subscriptions.value.single { !it.enabled }.file)
        } finally { filter.close() }
    }
    @Test fun completedSubscriptionChangesAreAlreadyActiveInTheEngine() = runBlocking {
        val filter = FilterController(context)
        try {
            val subscriptions = FilterSubscriptions(context, filter, builtIns = emptyList()) { _, _, _ ->
                TextDownloader.Response("! fixture\n||ads.example.com^\nfixture.test###fixture-ad\n")
            }
            subscriptions.initialize()
            assertTrue(filter.isReady)
            assertTrue(subscriptions.add("Fixture", "https://fixture.test/rules"))
            assertFalse(subscriptions.busy.value)
            assertTrue(filter.cosmeticCss("https://fixture.test/page").contains("#fixture-ad"))
            assertTrue(subscriptions.setEnabled(subscriptions.subscriptions.value.single().id, false))
            assertEquals("", filter.cosmeticCss("https://fixture.test/page"))
        } finally {
            filter.close()
        }
    }

    private lateinit var context: Context
    private var response = TextDownloader.Response("! test\n||ads.example.com^\n", "\"version1\"", "yesterday")
    private var failure = false
    private val requests = mutableListOf<Pair<String?, String?>>()
    private fun controller() = FilterSubscriptions(context, builtIns = emptyList()) { _, etag, modified ->
        requests += etag to modified
        if (failure) throw IOException("offline")
        response
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "filter_subscriptions").deleteRecursively()
        File(context.cacheDir, "filter_lists").deleteRecursively()
        context.getSharedPreferences("custom_filters", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("filter_settings", Context.MODE_PRIVATE).edit().clear().commit()
        requests.clear()
        failure = false
    }

    @Test fun bundledSnapshotsWorkOfflineWithExplicitDefaults() = runBlocking {
        val controller = FilterSubscriptions(context)
        controller.initialize()
        val lists = controller.subscriptions.value
        assertEquals(listOf("easylist", "easyprivacy", "easylist-china", "cjx-annoyance", "adguard-mobile-ads"),
            lists.filter { it.enabled }.map { it.id })
        assertEquals(listOf("adguard-mobile-app-banners", "easylist-cookie"), lists.filterNot { it.enabled }.map { it.id })
        assertTrue(lists.all { it.builtIn && it.ruleCount > 1000 && it.error == null })
    }

    @Test fun bundledSwitchesSurviveRestartAndOlderBackupImports() = runBlocking {
        val controller = FilterSubscriptions(context)
        controller.initialize()
        assertTrue(controller.setEnabled("cjx-annoyance", false))
        assertTrue(controller.setEnabled("easylist-cookie", true))
        val reopened = FilterSubscriptions(context).also { it.initialize() }
        assertTrue(reopened.importConfiguration(mapOf("easylist" to false), null).ok)
        val lists = reopened.subscriptions.value.associateBy { it.id }
        assertFalse(lists.getValue("easylist").enabled)
        assertFalse(lists.getValue("cjx-annoyance").enabled)
        assertTrue(lists.getValue("easylist-cookie").enabled)
        assertFalse(lists.getValue("adguard-mobile-app-banners").enabled)
    }

    @Test fun aMissingNewDefaultUsesItsDefaultWithoutChangingExistingChoices() = runBlocking {
        val prefs = context.getSharedPreferences("filter_settings", Context.MODE_PRIVATE)
        val prior = JSONArray()
            .put(JSONObject().put("id", "easylist").put("enabled", false))
            .put(JSONObject().put("id", "cjx-annoyance").put("enabled", false))
            .toString()
        prefs.edit().putString("subscriptions_manifest", prior).commit()
        val lists = FilterSubscriptions(context).also { it.initialize() }.subscriptions.value.associateBy { it.id }
        assertFalse(lists.getValue("easylist").enabled)
        assertFalse(lists.getValue("cjx-annoyance").enabled)
        assertTrue(lists.getValue("adguard-mobile-ads").enabled)
        assertFalse(lists.getValue("adguard-mobile-app-banners").enabled)
        assertFalse(lists.getValue("easylist-cookie").enabled)
    }

    @Test fun anOldCustomListMatchingANewBuiltInMovesToThatBuiltInIdentity() = runBlocking {
        val source = FilterSubscriptions.BUILT_INS.first { it.id == "adguard-mobile-ads" }
        val customId = "1234567890abcdef"
        val payload = "||fixture-ad.example^\n"
        val file = File(context.filesDir, "filter_subscriptions")
        file.mkdirs()
        file.resolve("$customId-${com.mybrowser.core.TextDownloader.sha256(payload)}.txt").writeText(payload)
        val manifest = JSONArray().put(JSONObject()
            .put("id", customId).put("name", "My AdGuard Mobile")
            .put("url", source.url).put("enabled", false)
            .put("file", "$customId-${com.mybrowser.core.TextDownloader.sha256(payload)}.txt"))
        context.getSharedPreferences("filter_settings", Context.MODE_PRIVATE).edit()
            .putString("subscriptions_manifest", manifest.toString()).commit()
        val upgraded = FilterSubscriptions(context).also { it.initialize() }.subscriptions.value
        assertEquals(FilterSubscriptions.BUILT_INS.size, upgraded.size)
        val migrated = upgraded.single { it.id == source.id }
        assertFalse(migrated.enabled)
        assertNull(migrated.file)
        assertTrue(migrated.ruleCount > 1000)
        assertNull(migrated.error)
    }

    @Test fun newBundledListsPreserveExistingSubscriptionChoices() = runBlocking {
        val old = FilterSubscriptions(context, builtIns = FilterSubscriptions.BUILT_INS.take(3))
        old.initialize()
        assertTrue(old.setEnabled("easyprivacy", false))
        val upgraded = FilterSubscriptions(context).also { it.initialize() }
        val lists = upgraded.subscriptions.value.associateBy { it.id }
        assertFalse(lists.getValue("easyprivacy").enabled)
        assertTrue(lists.getValue("cjx-annoyance").enabled)
        assertTrue(lists.getValue("adguard-mobile-ads").enabled)
        assertFalse(lists.getValue("easylist-cookie").enabled)
    }

    @Test fun previouslyCustomBundledSourceKeepsItsSwitchWithoutDuplication() = runBlocking {
        val source = FilterSubscriptions.BUILT_INS.first { it.id == "cjx-annoyance" }
        val old = controller()
        assertTrue(old.add("My CJX", source.url))
        assertTrue(old.setEnabled(old.subscriptions.value.single().id, false))
        val upgraded = FilterSubscriptions(context).also { it.initialize() }
        assertEquals(7, upgraded.subscriptions.value.size)
        val list = upgraded.subscriptions.value.single { it.url == source.url }
        assertTrue(list.builtIn)
        assertFalse(list.enabled)
        assertTrue(list.ruleCount > 1000)
        assertNull(list.error)
        assertTrue(upgraded.setEnabled(list.id, true))
        val reopened = FilterSubscriptions(context).also { it.initialize() }
        assertEquals(7, reopened.subscriptions.value.size)
        assertTrue(reopened.subscriptions.value.single { it.id == source.id }.enabled)
    }

    @Test fun optionalBundledRulesEnterAndLeaveTheEngineWithoutDownloading() = runBlocking {
        val source = FilterSubscriptions.BUILT_INS.first { it.id == "adguard-mobile-app-banners" }
        val filter = FilterController(context)
        try {
            val lists = FilterSubscriptions(context, filter, builtIns = listOf(source)) { _, _, _ ->
                error("Packaged rules must work offline")
            }
            lists.initialize()
            assertFalse(filter.cosmeticCss("https://example.org/page").contains("amp-app-banner"))
            assertTrue(lists.setEnabled(source.id, true))
            assertTrue(filter.cosmeticCss("https://example.org/page").contains("amp-app-banner"))
            assertTrue(lists.setEnabled(source.id, false))
            assertFalse(filter.cosmeticCss("https://example.org/page").contains("amp-app-banner"))
        } finally { filter.close() }
    }

    @Test fun allBundledSourcesFitTheRealNativeEngineTogether() = runBlocking {
        val filter = FilterController(context)
        try {
            val lists = FilterSubscriptions(context, filter)
            lists.initialize()
            assertTrue(lists.setEnabled("adguard-mobile-app-banners", true))
            assertTrue(lists.setEnabled("easylist-cookie", true))
            assertEquals(0, filter.refusedCount.value)
            val css = filter.cosmeticCss("https://example.org/page")
            for (selector in listOf("#sign-ad", ".happy-header-mobile", "amp-app-banner", "#a-cookie")) {
                assertTrue("Missing bundled selector: $selector", css.contains(selector))
            }
        } finally { filter.close() }
    }

    @Test fun customRulesPersistOutsideCache() = runBlocking {
        val controller = controller()
        assertTrue(controller.add("Example", "https://example.com/list.txt"))
        val list = controller.subscriptions.value.single()
        assertTrue(File(context.filesDir, "filter_subscriptions/" + list.file).isFile)
        File(context.cacheDir, "filter_lists").deleteRecursively()
        val reopened = controller().also { it.initialize() }.subscriptions.value.single()
        assertEquals(list.file, reopened.file)
        assertEquals(1, reopened.ruleCount)
    }

    @Test fun failedAndInvalidUpdatesPreserveContentAndTimestamps() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        val before = controller.subscriptions.value.single()
        failure = true
        assertFalse(controller.update())
        assertNotNull(controller.subscriptions.value.single().error)
        failure = false
        for (bad in listOf("", "! only comments", "<html>an error</html>", "[Adblock Plus 2.0]\n<html>error</html>")) {
            response = TextDownloader.Response(bad)
            assertFalse(controller.update())
            val after = controller.subscriptions.value.single()
            assertEquals(before.file, after.file)
            assertEquals(before.updatedAt, after.updatedAt)
            assertEquals(before.checkedAt, after.checkedAt)
        }
    }

    @Test fun conditionalUpdatesReuseValidatedSnapshots() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        val file = controller.subscriptions.value.single().file
        failure = true
        controller.update()
        failure = false
        response = TextDownloader.Response(null, "\"version1\"", "yesterday")
        assertTrue(controller.update())
        assertEquals("\"version1\"" to "yesterday", requests.last())
        assertEquals(file, controller.subscriptions.value.single().file)
        assertNull(controller.subscriptions.value.single().error)
    }

    @Test fun corruptSnapshotsClearValidatorsAndReject304() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        val list = controller.subscriptions.value.single()
        File(context.filesDir, "filter_subscriptions/" + list.file).writeText("damaged")
        val reopened = controller().also { it.initialize() }
        response = TextDownloader.Response(null)
        assertFalse(reopened.update())
        assertEquals(null to null, requests.last())
        assertEquals(0, reopened.subscriptions.value.single().ruleCount)
    }

    @Test fun disabledSourcesPersistAndUpdateAllSkipsThem() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        val id = controller.subscriptions.value.single().id
        controller.setEnabled(id, false)
        val reopened = controller().also { it.initialize() }
        assertFalse(reopened.subscriptions.value.single().enabled)
        requests.clear()
        assertTrue(reopened.update())
        assertTrue(requests.isEmpty())
        assertTrue(reopened.update(id))
        assertEquals(1, requests.size)
    }

    @Test fun duplicateAndInvalidUrlsDoNotFetchOrReplaceData() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        requests.clear()
        assertFalse(controller.add("Again", "https://example.com/list.txt"))
        assertFalse(controller.add("File", "file:///tmp/list.txt"))
        assertTrue(requests.isEmpty())
        assertEquals(1, controller.subscriptions.value.size)
    }

    @Test fun legacyRulesMigrateOutOfCache() = runBlocking {
        val id = "1234567890abcdef"
        val old = File(context.cacheDir, "filter_lists/" + id + ".txt")
        old.parentFile!!.mkdirs()
        old.writeText("||legacy.example.com^")
        val array = JSONArray().put(JSONObject().put("id", id).put("name", "Legacy").put("url", "https://legacy.example/list.txt"))
        context.getSharedPreferences("custom_filters", Context.MODE_PRIVATE).edit().putString("lists", array.toString()).commit()
        val controller = controller()
        controller.initialize()
        old.delete()
        val reopened = controller().also { it.initialize() }
        assertEquals(id, reopened.subscriptions.value.single().id)
        assertEquals(1, reopened.subscriptions.value.single().ruleCount)
    }

    @Test fun removingCustomListDeletesSnapshot() = runBlocking {
        val controller = controller()
        controller.add("Example", "https://example.com/list.txt")
        val list = controller.subscriptions.value.single()
        assertTrue(controller.remove(list.id))
        assertFalse(File(context.filesDir, "filter_subscriptions/" + list.file).exists())
        assertTrue(controller().also { it.initialize() }.subscriptions.value.isEmpty())
    }

    @Test fun globalFilteringPreferencePersists() {
        val first = FilterController(context)
        first.setEnabled(false)
        val second = FilterController(context)
        assertFalse(second.enabled.value)
        first.close()
        second.close()
    }
    @Test fun legacyManifestMigratesOnTheFirstSuccessfulWriteKeepingSnapshotsAndValidators() = runBlocking {
        val first = controller()
        assertTrue(first.add("Migrated", "https://example.com/list.txt"))
        val before = first.subscriptions.value.single()
        val prefs = context.getSharedPreferences("filter_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("subscriptions_manifest", null)!!
        val legacy = File(context.filesDir, "filter_subscriptions/subscriptions.json")
        legacy.writeText(saved)
        prefs.edit().remove("subscriptions_manifest").putBoolean("auto_update", false).commit()
        val migrated = controller()
        migrated.initialize()
        assertEquals(before.file, migrated.subscriptions.value.single().file)
        assertFalse(migrated.autoUpdate.value)
        assertTrue(migrated.setEnabled(before.id, false))
        assertFalse(legacy.exists())
        val reopened = controller()
        reopened.initialize()
        val after = reopened.subscriptions.value.single()
        assertEquals(before.file, after.file)
        assertEquals(before.etag, after.etag)
        assertEquals(before.modified, after.modified)
        assertEquals(before.updatedAt, after.updatedAt)
        assertFalse(after.enabled)
        assertFalse(reopened.autoUpdate.value)
        assertTrue(File(context.filesDir, "filter_subscriptions/" + before.file).isFile)
    }

    @Test fun customImportCannotDuplicateBuiltInsOrNormalizedUrls() = runBlocking {
        val controller = FilterSubscriptions(context)
        controller.initialize()
        val before = controller.subscriptions.value
        for (lists in listOf(
            listOf(Triple("Copy", FilterSubscriptions.BUILT_INS.first().url, true)),
            listOf(Triple("A", "https://example.com/list", true), Triple("B", " https://example.com/list ", false)),
        )) {
            assertFalse(controller.importConfiguration(emptyMap(), lists, enabled = false, autoUpdate = false).ok)
            assertEquals(before, controller.subscriptions.value)
            assertTrue(controller.autoUpdate.value)
        }
    }

}
