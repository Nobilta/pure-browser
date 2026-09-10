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

    @Test fun prototypeKeysAreOrdinaryJsonData() = runBlocking {
        val id = store.install(source, null).metadata.id
        assertTrue(store.changeValue(id, "set", "__proto__", JSONObject().put("safe", true)))
        assertTrue(JSONObject(store.scripts.value.single().values).getJSONObject("__proto__").getBoolean("safe"))
        assertTrue(store.changeValue(id, "delete", "__proto__", null))
        assertEquals("{}", store.scripts.value.single().values)
    }
}
