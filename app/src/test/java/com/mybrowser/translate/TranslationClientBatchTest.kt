package com.mybrowser.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationClientBatchTest {
    @Test
    fun `combined Chinese nodes stay within the encoded URL budget`() = runBlocking {
        assertBoundedBatch(listOf("中".repeat(600), "文".repeat(100)))
    }

    @Test
    fun `URL budget includes separators and preserves split emoji text`() = runBlocking {
        assertBoundedBatch(List(30) { "文".repeat(25) } + "😀".repeat(900) + "")
    }

    private suspend fun assertBoundedBatch(texts: List<String>) {
        val requests = mutableListOf<String>()
        val client = TranslationClient(TranslationSettings(provider = TranslationProvider.GOOGLE))
        val output = client.translateBatched(texts, "en", 1_800, 1_900, 6_000,
            translateOne = { text, _ -> requests += text; text }, onProgress = { _, _ -> })
        assertTrue("Encoded request sizes: ${requests.map { java.net.URLEncoder.encode(it, "UTF-8").length }}",
            requests.all { java.net.URLEncoder.encode(it, "UTF-8").length <= 6_000 })
        assertEquals(texts.map { it.replace(" ", "") }, output.map { it.replace(" ", "") })
    }

    @Test
    fun `long text is split at a boundary and never inside a surrogate pair`() {
        val text = "short words that should wrap cleanly ".repeat(30)
        val parts = TranslationClient(
            TranslationSettings(provider = TranslationProvider.GOOGLE),
        ).splitForProvider(text, 80)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 80 })
        assertTrue(parts.all { it.isNotBlank() })

        val emoji = "a".repeat(49) + "😀" + "b".repeat(50)
        val emojiParts = TranslationClient(
            TranslationSettings(provider = TranslationProvider.GOOGLE),
        ).splitForProvider(emoji, 50)
        assertTrue(emojiParts.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test
    fun `marked translation is split back into one value per text node`() {
        val client = TranslationClient(TranslationSettings(provider = TranslationProvider.MYMEMORY))
        val raw = "你好\n[[[ 0 ]]]\n世界\n[[[1]]]\n早上好"
        assertEquals(listOf("你好", "世界", "早上好"), client.parseMarkedTranslation(raw, 3))
        assertEquals(listOf("single"), client.parseMarkedTranslation("  single  ", 1))
    }

    @Test(expected = TranslationException::class)
    fun `a provider that drops a marker is rejected`() {
        val client = TranslationClient(TranslationSettings(provider = TranslationProvider.MYMEMORY))
        client.parseMarkedTranslation("你好\n世界", 2)
    }

    @Test(expected = TranslationException::class)
    fun `out-of-order markers are rejected`() {
        val client = TranslationClient(TranslationSettings(provider = TranslationProvider.MYMEMORY))
        client.parseMarkedTranslation("你好\n[[[1]]]\n世界\n[[[0]]]\n早上好", 3)
    }
}
