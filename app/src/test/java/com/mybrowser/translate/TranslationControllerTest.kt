package com.mybrowser.translate

import android.app.Application
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranslationControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        Dispatchers.setMain(StandardTestDispatcher())
        context.getSharedPreferences("translation_settings", 0).edit().clear().commit()
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Page : WebView(RuntimeEnvironment.getApplication()) {
        val applied = mutableListOf<String>()
        var mode = "none"
        var heldMode: ValueCallback<String>? = null
        var holdMode = false
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            val value = when {
                script.startsWith("window.__pureTranslateTranslatedMode") -> {
                    if (holdMode) { heldMode = callback; return }
                    JSONObject.quote(mode)
                }
                script.startsWith("window.__pureTranslateCollect ?") ->
                    JSONObject.quote("""{"items":[{"id":1,"text":"Hello world","version":0}],"total":11}""")
                script.startsWith("window.__pureTranslateReplaceCollectNew") ->
                    JSONObject.quote("""{"items":[],"total":11}""")
                script.startsWith("window.__pureTranslateVisibleIds") -> JSONObject.quote("[1]")
                script.startsWith("window.__pureTranslateApply &&") -> {
                    applied += script; mode = "replace"; "1"
                }
                script.startsWith("window.__pureTranslateRestore &&") -> { mode = "none"; "null" }
                else -> "null"
            }
            callback?.onReceiveValue(value)
        }
    }
    private class Host(override var view: WebView?) : TranslationController.Host {
        override var url = "https://example.com/a"
        override var documentEpoch = 1L
        override var ownerTabId: String? = "A"
        override var selectedTabId: String? = "A"
        override val panelVisible = true
        override fun message(text: String) = Unit
    }

    @Test fun stoppedProviderResultCannotWriteIntoARestartedSessionOrClearItsBusyState() = runTest {
        val page = Page(); val host = Host(page)
        val first = CompletableDeferred<List<String>>()
        val second = CompletableDeferred<List<String>>()
        var calls = 0
        val controller = TranslationController(context, backgroundScope, host) { _, _ ->
            val result = if (calls++ == 0) first else second
            withContext(NonCancellable) { result.await() }
        }
        try {
            assertTrue(controller.translatePage()); runCurrent()
            controller.restore(); runCurrent()
            assertTrue(controller.translatePage()); runCurrent()
            first.complete(listOf("obsolete")); runCurrent()
            assertTrue(page.applied.isEmpty())
            assertTrue(controller.busy)
            second.complete(listOf("current")); runCurrent()
            advanceTimeBy(150); runCurrent()
            assertEquals(1, page.applied.size)
            assertTrue(page.applied.single().contains("current"))
            assertTrue(controller.pageTranslated)
            assertFalse(controller.busy)
        } finally { controller.onPageStarted(); page.destroy() }
    }

    @Test fun retainedTabResumesButReloadedDocumentDoesNotInheritItsSnapshot() = runTest {
        val page = Page(); val other = Page(); val host = Host(page)
        var calls = 0
        val controller = TranslationController(context, backgroundScope, host) { _, _ -> calls++; listOf("translated") }
        fun select(id: String, view: Page) {
            controller.onTabChanging(id, setOf("A", "B"))
            host.selectedTabId = id; host.ownerTabId = id; host.view = view
            host.url = "https://example.com/" + id.lowercase()
            controller.refresh()
        }
        try {
            controller.translatePage(); runCurrent(); advanceTimeBy(150); runCurrent()
            select("B", other); runCurrent()
            assertFalse(controller.pageTranslated)
            select("A", page); runCurrent()
            assertTrue(controller.pageTranslated)
            assertTrue(controller.sessionActive)
            assertEquals(1, calls)
            select("B", other); runCurrent()
            page.mode = "none" // Same URL loaded into a fresh document without translation observers.
            select("A", page); runCurrent()
            assertFalse(controller.pageTranslated)
            assertFalse(controller.sessionActive)
        } finally { controller.onPageStarted(); page.destroy(); other.destroy() }
    }

    @Test fun lateModeReadCannotUpdateADifferentDocumentAtTheSameUrl() = runTest {
        val page = Page().apply { holdMode = true }; val host = Host(page)
        val controller = TranslationController(context, backgroundScope, host)
        try {
            controller.refresh(); runCurrent()
            assertNotNull(page.heldMode)
            host.documentEpoch++
            page.heldMode!!.onReceiveValue(JSONObject.quote("replace")); runCurrent()
            assertFalse(controller.pageTranslated)
            assertFalse(controller.complete)
        } finally { controller.onPageStarted(); page.destroy() }
    }

    @Test fun cancelledModeReadCannotConsumeTheRetainedTabSnapshot() = runTest {
        val page = Page(); val other = Page(); val host = Host(page)
        val controller = TranslationController(context, backgroundScope, host) { _, _ -> listOf("translated") }
        try {
            controller.translatePage(); runCurrent(); advanceTimeBy(150); runCurrent()
            controller.onTabChanging("B", setOf("A", "B"))
            host.ownerTabId = "B"; host.selectedTabId = "B"; host.view = other
            controller.onTabChanging("A", setOf("A", "B"))
            host.ownerTabId = "A"; host.selectedTabId = "A"; host.view = page
            page.holdMode = true
            controller.refresh(); runCurrent()
            controller.refresh(); runCurrent() // Supersede the first pending JS query.
            page.heldMode!!.onReceiveValue(JSONObject.quote("replace")); runCurrent()
            assertTrue(controller.pageTranslated)
            assertTrue(controller.sessionActive)
        } finally { controller.onPageStarted(); page.destroy(); other.destroy() }
    }
}
