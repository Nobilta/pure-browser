package com.mybrowser.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationPageTest {
    @Test
    fun `WebView response preserves source revisions and removed queue ids`() {
        val translator = PageTranslator(RuntimeEnvironment.getApplication())
        val decoder = PageTranslator::class.java.getDeclaredMethod("decodePage", String::class.java)
        decoder.isAccessible = true
        val wire = JSONObject.quote("""{"items":[{"id":2,"text":"Updated","visible":true,"version":4}],"total":7,"truncated":false,"removedIds":[1,3]}""")
        val page = decoder.invoke(translator, wire) as TranslationPage
        assertEquals(listOf(TranslationPageItem(2, "Updated", true, 4)), page.items)
        assertEquals(setOf(1, 3), page.removedIds)
    }

    @Test
    fun `rescan removes excluded pending nodes and inserts their replacement revision`() {
        val pending = linkedMapOf(
            1 to TranslationPageItem(1, "Removed", version = 1),
            2 to TranslationPageItem(2, "Old", version = 1),
        )
        val current = TranslationPageItem(2, "New", version = 2)
        TranslationPage(listOf(current), 3, false, setOf(1)).updatePending(pending)
        assertEquals(mapOf(2 to current), pending)
    }

    @Test
    fun `delayed queue updates cannot replace a newer pending source revision`() {
        val current = TranslationPageItem(1, "Current", version = 3)
        val pending = linkedMapOf(1 to current)
        TranslationPage(listOf(TranslationPageItem(1, "Stale", version = 2)), 5, false)
            .updatePending(pending)
        assertEquals(current, pending[1])
    }
}
