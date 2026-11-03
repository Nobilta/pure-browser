package com.mybrowser.ui.devtools

import android.app.Application
import android.webkit.ValueCallback
import android.webkit.WebView
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.DomPathEntry
import com.mybrowser.devtools.DomSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DomTreeStateTest {
    private class Fixture : WebView(RuntimeEnvironment.getApplication()) {
        val offsets = mutableListOf<Int>()
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            val response = if (script.contains("a.domRoot(")) {
                JSONObject().put("root", JSONObject().put("id", "root").put("kind", "element")
                    .put("name", "div").put("childCount", 220))
            } else {
                val start = Regex("a.domChildren\\(\"root\",(\\d+),").find(script)!!.groupValues[1].toInt()
                offsets += start
                val nodes = JSONArray()
                for (i in start until minOf(start + 100, 220)) nodes.put(JSONObject()
                    .put("id", "child-$i").put("kind", if (i % 2 == 0) "text" else "element")
                    .put("name", if (i % 2 == 0) "" else "p").put("text", "\n ")
                    .put("formattingWhitespace", i % 2 == 0))
                JSONObject().put("nodes", nodes).put("start", start).put("total", 220)
            }
            resultCallback?.onReceiveValue(JSONObject.quote(response.toString()))
        }
    }

    @Test fun hiddenRowsDoNotChangeLoadMoreOrSearchOffsetsAndTogglingPreservesNodes() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val fixture = Fixture()
            val session = DevToolsSession(fixture)
            mapOf("enabled" to true, "namespace" to "__fixture", "documentId" to 1L).forEach { (name, value) ->
                DevToolsSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val state = DomTreeState(session)
            runBlocking {
                state.load()
                assertEquals(50, state.rows.count { it.node?.id?.startsWith("child-") == true })
                assertEquals(100, state.rows.last().loaded)
                state.loadMore("root")
                assertEquals(listOf(0, 100), fixture.offsets)
                val index = state.reveal(DomSearchResult("child-211", "p", "", "selector",
                    listOf(DomPathEntry("root", 0), DomPathEntry("child-211", 211))))!!
                assertEquals("child-211", state.rows[index].node?.id)
                assertEquals(listOf(0, 100, 200), fixture.offsets)
            }
            state.setWhitespaceVisible(true)
            assertEquals(221, state.rows.size)
            val blank = state.rows.first { it.node?.id == "child-0" }.node!!
            state.select(blank)
            state.setWhitespaceVisible(false)
            assertNull(state.selectedId)
            assertEquals(111, state.rows.size)
            state.setWhitespaceVisible(true)
            assertEquals(blank.id, state.rows[1].node?.id)
            assertEquals(listOf(0, 100, 200), fixture.offsets)
        } finally { Dispatchers.resetMain() }
    }
}
