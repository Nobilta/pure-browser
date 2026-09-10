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

    @Test fun threeMainstreamSnapshotsWorkOffline() = runBlocking {
        val controller = FilterSubscriptions(context)
        controller.initialize()
        assertEquals(listOf("EasyList", "EasyPrivacy", "EasyList China"), controller.subscriptions.value.map { it.name })
        assertTrue(controller.subscriptions.value.all { it.enabled && it.builtIn && it.ruleCount > 1000 && it.error == null })
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
}
