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
        assertEquals(SearchEngine.BUILTIN_ENGINES.size, manager.getAvailableEngines().size)
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
        assertEquals(SearchEngine.BUILTIN_ENGINES.size + 12, manager.getAvailableEngines().size)
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


    @Test
    fun `metaso is a built-in engine with an encoded query template`() {
        val metaso = SearchEngine.BUILTIN_ENGINES.single { it.id == "metaso" }
        assertEquals("https://metaso.cn/?q=%E4%B8%AD%E6%96%87", metaso.buildSearchUrl("中文"))
    }
    @Test
    fun `image selection and custom templates persist independently of web search`() {
        val context = RuntimeEnvironment.getApplication()
        val images = SearchEngineManager(context, SearchEngineKind.IMAGE)
        assertEquals(SearchEngine.IMAGE_BING, images.getCurrentEngine())
        manager.setCurrentEngine(SearchEngine.GOOGLE)
        val custom = images.addCustomEngine("Images", "https://images.example/search?url={imageUrl}")
        images.setCurrentEngine(custom)
        val reloaded = SearchEngineManager(context, SearchEngineKind.IMAGE)
        assertEquals(custom, reloaded.getCurrentEngine())
        assertEquals(SearchEngine.GOOGLE, manager.getCurrentEngine())
        assertEquals(SearchEngine.BUILTIN_ENGINES, manager.getAvailableEngines())
        val imageUrl = "https://cdn.example/猫.png?token=a+b&size=2#crop"
        assertEquals(imageUrl, android.net.Uri.parse(custom.buildSearchUrl(imageUrl)).getQueryParameter("url"))
        images.removeCustomEngine(custom.id)
        assertEquals(SearchEngine.IMAGE_BING, images.getCurrentEngine())
        assertEquals(SearchEngine.GOOGLE, manager.getCurrentEngine())
    }

    @Test
    fun `wrong placeholder and image suggestion URLs are rejected`() {
        val images = SearchEngineManager(RuntimeEnvironment.getApplication(), SearchEngineKind.IMAGE)
        for (template in listOf("https://example.com/?url={query}",
            "https://example.com/?url={imageUrl}&again=%s", "javascript:{imageUrl}")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                images.addCustomEngine("Images", template)
            }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            images.addCustomEngine("Images", "https://example.com/?url={imageUrl}",
                "https://example.com/suggest?q=%s")
        }
    }

    @Test
    fun `invalid image import does not partially change web settings`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            manager.importSettings(emptyList(), SearchEngine.GOOGLE.id, emptyList(), "missing")
        }
        assertEquals(SearchEngine.BAIDU, manager.getCurrentEngine())
    }

}
