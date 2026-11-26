package com.mybrowser.ui.devtools

import android.app.Application
import android.webkit.ValueCallback
import android.webkit.WebView
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.DomPathEntry
import com.mybrowser.devtools.DomSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        var attributeValue = "before"
        var textValue = "\n "
        var suspendChildren = false
        val childRequested = CompletableDeferred<Unit>()
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            val response = if (script.contains("a.domRoot(")) {
                JSONObject().put("root", JSONObject().put("id", "root").put("kind", "element")
                    .put("name", "html").put("childCount", 220)
                    .put("attributes", JSONArray().put(JSONObject().put("name", "lang").put("value", attributeValue))))
            } else {
                val start = Regex("a.domChildren\\(\"root\",(\\d+),").find(script)!!.groupValues[1].toInt()
                offsets += start
                if (suspendChildren) {
                    childRequested.complete(Unit)
                    return
                }
                val nodes = JSONArray()
                for (i in start until minOf(start + 100, 220)) nodes.put(JSONObject()
                    .put("id", "child-$i").put("kind", if (i % 2 == 0) "text" else "element")
                    .put("name", if (i % 2 == 0) "" else "p").put("text", textValue)
                    .put("attributes", JSONArray().put(JSONObject().put("name", "title").put("value", attributeValue)))
                    .put("formattingWhitespace", i % 2 == 0 && textValue.isBlank()))
                JSONObject().put("nodes", nodes).put("start", start).put("total", 220)
            }
            resultCallback?.onReceiveValue(JSONObject.quote(response.toString()))
        }
    }

    @Test fun cancelledSourcePagingKeepsTheVisiblePageAndBackHistory() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            var suspendPage = false
            var requested = CompletableDeferred<Unit>()
            val fixture = object : WebView(RuntimeEnvironment.getApplication()) {
                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    val json = if (script.contains("a.sourceInfo(")) {
                        JSONObject().put("totalChars", 30).put("totalLines", 3)
                    } else {
                        val offset = Regex("a.sourcePage\\((\\d+),").find(script)!!.groupValues[1].toInt()
                        if (suspendPage) { requested.complete(Unit); return }
                        JSONObject().put("offset", offset).put("end", offset + 10).put("text", "x".repeat(10))
                            .put("totalChars", 30).put("totalLines", 3).put("next", if (offset < 20) offset + 10 else JSONObject.NULL)
                    }
                    resultCallback?.onReceiveValue(JSONObject.quote(json.toString()))
                }
            }
            val session = DevToolsSession(fixture)
            mapOf("enabled" to true, "namespace" to "__fixture", "documentId" to 1L).forEach { (name, value) ->
                DevToolsSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val state = SourceViewerState(session)
            runBlocking {
                state.loadIfNeeded()
                suspendPage = true
                val next = launch { state.next() }
                requested.await()
                next.cancelAndJoin()
                state.loadIfNeeded()
                assertEquals(0, state.page!!.offset)
                assertFalse(state.canGoBack())
                suspendPage = false
                state.next()
                assertEquals(10, state.page!!.offset)
                suspendPage = true
                requested = CompletableDeferred()
                val previous = launch { state.previous() }
                requested.await()
                previous.cancelAndJoin()
                assertEquals(10, state.page!!.offset)
                assertTrue(state.canGoBack())
                suspendPage = false
                state.previous()
                assertEquals(0, state.page!!.offset)
                assertFalse(state.canGoBack())
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancelledInitialChildrenLoadCanBeOpenedAgain() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val fixture = Fixture().apply { suspendChildren = true }
            val session = DevToolsSession(fixture)
            mapOf("enabled" to true, "namespace" to "__fixture", "documentId" to 1L).forEach { (name, value) ->
                DevToolsSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val state = DomTreeState(session)
            runBlocking {
                val opening = launch { state.load() }
                fixture.childRequested.await()
                opening.cancelAndJoin()
                assertFalse(state.loading)
                fixture.suspendChildren = false
                state.load()
                assertEquals("root", state.rows.first().node?.id)
                assertEquals(50, state.rows.count { it.node?.id?.startsWith("child-") == true })
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun editingSelectedTextToWhitespaceClearsTheHiddenSelection() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val fixture = Fixture().apply { textValue = "visible text" }
            val session = DevToolsSession(fixture)
            mapOf("enabled" to true, "namespace" to "__fixture", "documentId" to 1L).forEach { (name, value) ->
                DevToolsSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val state = DomTreeState(session)
            runBlocking {
                state.load()
                state.select(state.rows.first { it.node?.id == "child-0" }.node!!)
                val siblings = state.rows.filter { it.node?.id != "child-0" }
                fixture.textValue = "\n "
                state.refreshEditedNode("child-0")
                assertNull(state.selectedId)
                assertEquals("", state.selectedName)
                assertEquals(siblings, state.rows.toList())
                state.setWhitespaceVisible(true)
                assertEquals("\n ", state.rows.first { it.node?.id == "child-0" }.node!!.text)
            }
        } finally { Dispatchers.resetMain() }
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
                // Reentering Elements must keep the loaded branch and selection.
                val rowsBeforeReentry = state.rows.toList()
                val revisionBeforeReentry = state.revision
                state.load()
                assertEquals(rowsBeforeReentry, state.rows.toList())
                assertEquals("child-211", state.selectedId)
                assertEquals(revisionBeforeReentry, state.revision)
                assertEquals(listOf(0, 100, 200), fixture.offsets)
                fixture.attributeValue = "after"
                state.refreshEditedNode("child-211")
                assertEquals("after", state.rows.first { it.node?.id == "child-211" }.node!!.attributes.single().value)
                assertEquals("child-211", state.selectedId)
                assertEquals(110, state.rows.count { it.node?.id?.startsWith("child-") == true })
                assertEquals(listOf(0, 100, 200, 211), fixture.offsets)
                state.refreshEditedNode("root")
                assertEquals("after", state.root!!.attributes.single().value)
                assertEquals("after", state.rows.first().node!!.attributes.single().value)
                assertEquals("child-211", state.selectedId)
                assertEquals(110, state.rows.count { it.node?.id?.startsWith("child-") == true })
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
            assertEquals(listOf(0, 100, 200, 211), fixture.offsets)
        } finally { Dispatchers.resetMain() }
    }
}
