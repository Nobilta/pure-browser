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

    @Test
    fun `optional suggest template round-trips and invalid ones are rejected`() {
        val withSuggest = manager.addCustomEngine(
            "Suggested", "https://search.example/?q=%s",
            "https://search.example/suggest?q={query}",
        )
        assertEquals("https://search.example/suggest?q={query}", withSuggest.suggestUrl)

        // A new manager instance re-reads the persisted JSON, including the suggest URL.
        val reloaded = SearchEngineManager(RuntimeEnvironment.getApplication())
            .getEngineById(withSuggest.id)
        assertEquals(withSuggest, reloaded)

        // Blank means "no suggestions", which is always allowed.
        val withoutSuggest = manager.addCustomEngine("Plain", "https://search.example/p?q=%s", "  ")
        assertEquals(null, withoutSuggest.suggestUrl)

        val invalidSuggests = listOf(
            "http://search.example/suggest?q={query}",       // not HTTPS
            "https://search.example/suggest",                // no placeholder
            "https://search.example/s?q={query}&r={query}",  // two placeholders
            "https:///suggest?q={query}",                    // no host
        )
        invalidSuggests.forEach { suggest ->
            try {
                manager.addCustomEngine("engine-${suggest.hashCode()}", "https://search.example/x?q=%s", suggest)
                throw AssertionError("accepted invalid suggest template: $suggest")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
    @Test fun addingEnginesCannotWriteAnUnreadableAggregate() {
        val manager = SearchEngineManager(org.robolectric.RuntimeEnvironment.getApplication())
        val template = "https://example.com/?q={query}&x=" + "\"".repeat(1990)
        var accepted = 0
        repeat(12) { index ->
            val before = manager.getAvailableEngines()
            try {
                manager.addCustomEngine("Size $index", template, template)
                accepted++
            } catch (_: IllegalArgumentException) {
                assertEquals(before, manager.getAvailableEngines())
            }
        }
        assertTrue(accepted in 1..11)
        assertEquals(accepted, manager.getAvailableEngines().count { it.isCustom })
    }

}
