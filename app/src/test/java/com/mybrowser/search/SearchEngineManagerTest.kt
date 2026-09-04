package com.mybrowser.search

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Bounds and persistence tests for user-supplied search engine templates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchEngineManagerTest {

    private lateinit var manager: SearchEngineManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("search_engines", 0).edit().clear().commit()
        manager = SearchEngineManager(context)
    }

    @Test
    fun `valid custom engine is persisted and can be selected`() {
        val added = manager.addCustomEngine("  Site  ", "https://search.example/?q=%s")

        assertEquals("Site", added.name)
        assertTrue(added.isCustom)
        assertEquals(added, manager.getEngineById(added.id))

        manager.setCurrentEngine(added)
        assertEquals(added.id, manager.getCurrentEngine().id)
    }

    @Test
    fun `invalid templates are rejected before persistence`() {
        val invalid = listOf(
            "",
            "search.example/?q=%s",
            "javascript:alert(1)?q=%s",
            "https://search.example/?q=%s&x={query}",
            "https://search.example/?q=query",
            "https://search.example/?q=%s and-more",
        )
        invalid.forEach { template ->
            try {
                manager.addCustomEngine("engine-${template.hashCode()}", template)
                throw AssertionError("accepted invalid template: $template")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(4, manager.getAvailableEngines().size)
    }

    @Test
    fun `duplicate names and excessive engines are bounded`() {
        manager.addCustomEngine("One", "https://search.example/1?q=%s")
        try {
            manager.addCustomEngine(" one ", "https://search.example/2?q=%s")
            throw AssertionError("duplicate name accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        repeat(11) { index ->
            manager.addCustomEngine("Engine $index", "https://search.example/$index?q=%s")
        }
        assertEquals(16, manager.getAvailableEngines().size)
        try {
            manager.addCustomEngine("Overflow", "https://search.example/overflow?q=%s")
            throw AssertionError("engine cap not enforced")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `unknown selected id is removed and falls back to builtin`() {
        manager.setCurrentEngineById("not-a-real-engine")
        assertEquals(SearchEngine.BAIDU.id, manager.getCurrentEngine().id)

        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("search_engines", 0)
        assertFalse(prefs.contains("current_engine_id"))
    }
}
