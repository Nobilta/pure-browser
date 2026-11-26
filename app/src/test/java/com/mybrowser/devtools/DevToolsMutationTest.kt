package com.mybrowser.devtools

import android.app.Application
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DevToolsMutationTest {
    private class Page : WebView(RuntimeEnvironment.getApplication()) {
        val scripts = mutableListOf<String>()
        var response = "true"
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            scripts += script
            callback?.onReceiveValue(response)
        }
    }

    @Test fun malformedPageOwnedDomResponsesAreFailuresAndDoNotPoisonLaterReads() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val page = Page()
        val session = DevToolsSession(page)
        try {
            mapOf("enabled" to true, "namespace" to "__fixture", "documentId" to 1L).forEach { (name, value) ->
                DevToolsSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            runBlocking {
                for (json in listOf("{}", """{"id":"node"}""")) {
                    page.response = JSONObject.quote(json)
                    assertTrue(session.dom.picked() is PageResult.Failed)
                }
                for (json in listOf("{}",
                    """{"doc":"page","kind":"element","supportsInlineStyle":true,"attributes":[1]}""",
                    """{"doc":"page","kind":"element","supportsInlineStyle":{}}""") +
                    listOf("attributes", "styles", "computed").map { group ->
                        """{"doc":"page","kind":"element","supportsInlineStyle":true,"$group":[{"name":"color","value":"red"},{"name":"color","value":"blue"}]}"""
                    }) {
                    page.response = JSONObject.quote(json)
                    assertTrue(session.dom.details("node") is PageResult.Failed)
                }
                page.response = JSONObject.quote("""{"doc":"page","kind":"element","supportsInlineStyle":true,"styles":[{"name":"color","value":"red"}],"computed":[{"name":"color","value":"red"}]}""")
                assertTrue(session.dom.details("node") is PageResult.Ok)
                page.response = JSONObject.quote("""{"id":"node","name":"p"}""")
                assertTrue(session.dom.picked() is PageResult.Ok)
            }
        } finally { session.close(); page.destroy(); Dispatchers.resetMain() }
    }

    @Test fun navigationBeforeMainThreadDispatchPreventsTheWriteItself() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val page = Page()
        val session = DevToolsSession(page)
        try {
            session.onDocumentStarted("https://example.test/first")
            val oldDocument = session.documentId
            val result = CoroutineScope(Dispatchers.Unconfined).async {
                session.evaluateInDocument("localStorage.clear()", oldDocument)
            }
            session.onDocumentStarted("https://example.test/second")
            main.scheduler.runCurrent()
            assertNull(runBlocking { result.await() })
            assertTrue("The old edit must never reach WebView", page.scripts.isEmpty())
        } finally { session.close(); page.destroy(); Dispatchers.resetMain() }
    }
}
