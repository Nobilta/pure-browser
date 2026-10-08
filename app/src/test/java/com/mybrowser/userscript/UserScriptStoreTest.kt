package com.mybrowser.userscript

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserScriptStoreTest {
    private lateinit var context: Context
    private lateinit var store: UserScriptStore
    private val source = "// ==UserScript==\n// @name Test\n// @namespace tests\n// @match *://*/*\n// @grant GM_getValue\n// @grant GM_setValue\n// @grant GM_deleteValue\n// ==/UserScript==\n"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "userscripts").deleteRecursively()
        store = UserScriptStore(context)
    }

    @Test fun installPersistsSourceAndSettings() = runBlocking {
        val script = store.install(source, "https://example.com/test.user.js")
        store.setEnabled(script.metadata.id, false)
        val reopened = UserScriptStore(context).also { it.initialize() }.scripts.value.single()
        assertEquals(source, reopened.source)
        assertEquals("https://example.com/test.user.js", reopened.sourceUrl)
        assertFalse(reopened.enabled)
    }

    @Test fun replacementPreservesValuesAndEnableState() = runBlocking {
        val script = store.install(source, null)
        assertTrue(store.changeValue(script.metadata.id, "set", "count", 17))
        store.setEnabled(script.metadata.id, false)
        store.install(source + "console.log('version two');", null)
        val reopened = UserScriptStore(context).also { it.initialize() }.scripts.value.single()
        assertEquals(17, JSONObject(reopened.values).getInt("count"))
        assertFalse(reopened.enabled)
    }

    @Test fun removingInAPrivateSessionDropsItsValues() = runBlocking {
        val script = store.install(source, null)
        store.beginPrivateSession()
        assertTrue(store.changeValue(script.metadata.id, "set", "count", 41, private = true))
        assertEquals(41, JSONObject(store.valuesFor(script.metadata.id, private = true)).getInt("count"))
        store.remove(script.metadata.id)
        // The script is gone, so the session map must not still hold state for its id: reinstalling
        // the same script in this session starts from nothing.
        assertEquals("{}", store.valuesFor(script.metadata.id, private = true))
        store.endPrivateSession()
    }

    @Test fun disableAndPermissionRevocationStopWrites() = runBlocking {
        val id = store.install(source, null).metadata.id
        store.setEnabled(id, false)
        assertFalse(store.changeValue(id, "set", "secret", "bad"))
        store.setEnabled(id, true)
        store.install(source.replace("// @grant GM_setValue\n", ""), null)
        assertFalse(store.changeValue(id, "set", "secret", "bad"))
        assertEquals("{}", store.scripts.value.single().values)
    }

    @Test fun valuesAreIsolatedAndRemovalClearsThem() = runBlocking {
        val first = store.install(source, null).metadata.id
        val other = store.install(source.replace("@name Test", "@name Other"), null).metadata.id
        assertTrue(store.changeValue(first, "set", "answer", 42))
        assertEquals("{}", store.scripts.value.find { it.metadata.id == other }!!.values)
        store.remove(first)
        assertEquals("{}", store.install(source, null).values)
    }

    @Test fun unsupportedScriptsCannotBeEnabled() = runBlocking {
        val script = store.install(source.replace("GM_getValue", "GM_xmlhttpRequest"), null)
        assertFalse(script.enabled)
        store.setEnabled(script.metadata.id, true)
        assertFalse(store.scripts.value.single().enabled)
    }

    @Test fun storageLimitsPreservePreviousValues() = runBlocking {
        val id = store.install(source, null).metadata.id
        assertTrue(store.changeValue(id, "set", "okay", "kept"))
        assertFalse(store.changeValue(id, "set", "large", "x".repeat(UserScriptStore.MAX_VALUES_BYTES)))
        assertFalse(store.changeValue(id, "set", "k".repeat(257), "bad"))
        assertFalse(store.changeValue(id, "other", "okay", "bad"))
        assertEquals("kept", JSONObject(store.scripts.value.single().values).getString("okay"))
    }

    @Test fun corruptManifestIsPreservedAndNotOverwritten() = runBlocking {
        val file = File(context.filesDir, "userscripts/scripts.json")
        file.parentFile!!.mkdirs()
        file.writeText("{ damaged")
        store.initialize()
        assertTrue(store.loadFailed)
        assertTrue(runCatching { store.install(source, null) }.isFailure)
        assertEquals("{ damaged", file.readText())
    }

    @Test fun privateWritesStayInMemoryAndNeverTouchTheFile() = runBlocking {
        val id = store.install(source, null).metadata.id
        // A private session's write is accepted, but it must not reach the saved values: the whole
        // point is that the next normal session cannot read what a script wrote while private.
        assertTrue(store.changeValue(id, "set", "seen", "private", private = true))
        assertEquals("private", JSONObject(store.valuesFor(id, private = true)).getString("seen"))
        assertEquals("{ }".replace(" ", ""), store.valuesFor(id, private = false))
        assertFalse(File(context.filesDir, "userscripts/$id.values.json").exists())
        // Reopening the store must not resurrect it either.
        val reopened = UserScriptStore(context).also { it.initialize() }
        assertEquals("{}", reopened.valuesFor(id, private = false))
        assertEquals("{}", reopened.valuesFor(id, private = true))
    }

    @Test fun endingAPrivateSessionDropsItsValues() = runBlocking {
        val id = store.install(source, null).metadata.id
        assertTrue(store.changeValue(id, "set", "token", "abc", private = true))
        store.endPrivateSession()
        assertEquals("{}", store.valuesFor(id, private = true))
        // And the normal values were never involved in the first place.
        assertTrue(store.changeValue(id, "set", "kept", "normal"))
        assertTrue(store.changeValue(id, "set", "gone", "private", private = true))
        store.beginPrivateSession()
        assertEquals("normal", JSONObject(store.valuesFor(id, private = false)).getString("kept"))
        assertEquals("{}", store.valuesFor(id, private = true))
    }

    @Test fun aPrivateWriteAnnouncesThatInjectedValuesChanged() = runBlocking {
        val id = store.install(source, null).metadata.id
        val before = store.programs.value
        // The runtime caches one injected program per script and only rebuilds when its inputs
        // change. A private write changes the values but leaves every script equal, so the store has
        // to raise its own signal or the next document keeps the value from before the write.
        assertTrue(store.changeValue(id, "set", "seen", "private", private = true))
        assertEquals(before + 1, store.programs.value)
        // Starting or ending a session changes them too, in the direction of "nothing is stored".
        store.endPrivateSession()
        assertEquals(before + 2, store.programs.value)
        store.beginPrivateSession()
        assertEquals(before + 3, store.programs.value)
        // A normal write changes the script list itself, which the runtime already watches.
        val listed = store.programs.value
        assertTrue(store.changeValue(id, "set", "saved", 1))
        assertEquals(listed, store.programs.value)
    }

    @Test fun privateWritesRespectTheSameGrantsAndLimits() = runBlocking {
        val id = store.install(source, null).metadata.id
        store.setEnabled(id, false)
        assertFalse(store.changeValue(id, "set", "k", "v", private = true))
        store.setEnabled(id, true)
        assertFalse(store.changeValue(id, "set", "k".repeat(257), "v", private = true))
        assertFalse(store.changeValue(id, "set", "large", "x".repeat(UserScriptStore.MAX_VALUES_BYTES), private = true))
        store.install(source.replace("// @grant GM_setValue\n", ""), null)
        assertFalse(store.changeValue(id, "set", "k", "v", private = true))
    }

    @Test fun prototypeKeysAreOrdinaryJsonData() = runBlocking {
        val id = store.install(source, null).metadata.id
        assertTrue(store.changeValue(id, "set", "__proto__", JSONObject().put("safe", true)))
        assertTrue(JSONObject(store.scripts.value.single().values).getJSONObject("__proto__").getBoolean("safe"))
        assertTrue(store.changeValue(id, "delete", "__proto__", null))
        assertEquals("{}", store.scripts.value.single().values)
    }

    @Test fun thePrivateMapIsTheOnlyThingAPrivateWriteTouches() = runBlocking {
        val script = store.install(source, null)
        val id = script.metadata.id
        assertTrue(store.changeValue(id, "set", "count", 3))
        store.beginPrivateSession()
        // The session starts from nothing, not from the saved value, and the write lands only in
        // the session map: the file and the script object the next normal session would read are
        // both untouched, which is the boundary the whole change is about.
        assertEquals("{}", store.valuesFor(id, private = true))
        assertTrue(store.changeValue(id, "set", "count", 7, private = true))
        assertEquals(7, JSONObject(store.valuesFor(id, private = true)).getInt("count"))
        assertEquals(3, JSONObject(store.valuesFor(id, private = false)).getInt("count"))
    }

    @Test fun aPrivateValueDoesNotSurviveTheSession() = runBlocking {
        val script = store.install(source, null)
        val id = script.metadata.id
        store.beginPrivateSession()
        assertTrue(store.changeValue(id, "set", "count", 7, private = true))
        assertEquals("""{"count":7}""", JSONObject(store.valuesFor(id, private = true)).getInt("count").let { """{"count":$it}""" })
        store.endPrivateSession()
        assertTrue(store.valuesFor(id, private = true).contains("{}"))
        // And the normal session's own value is untouched by any of it.
        assertTrue(store.changeValue(id, "set", "count", 3, private = false))
        assertTrue(store.valuesFor(id, private = false).contains("3"))
    }

}
