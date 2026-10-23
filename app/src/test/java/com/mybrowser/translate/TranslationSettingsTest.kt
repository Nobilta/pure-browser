package com.mybrowser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TranslationSettingsTest {
    @Test
    fun `default target follows simplified and traditional Chinese locales`() {
        assertEquals("zh-CN", defaultTranslationTarget(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-TW", defaultTranslationTarget(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-TW", defaultTranslationTarget(Locale.forLanguageTag("zh-HK")))
        assertEquals("en", defaultTranslationTarget(Locale.forLanguageTag("en-US")))
        assertEquals("ja", defaultTranslationTarget(Locale.forLanguageTag("ja-JP")))
    }

    @Test
    fun `page translation defaults to replacement and can choose comparison`() {
        assertEquals(TranslationPageMode.REPLACE, TranslationSettings().pageMode)
        assertEquals(
            TranslationPageMode.COMPARE,
            TranslationSettings(pageMode = TranslationPageMode.COMPARE).pageMode,
        )
    }

    @Test
    fun `built-in providers are configured and can translate a whole page`() {
        val myMemory = TranslationSettings(provider = TranslationProvider.MYMEMORY)
        assertTrue(myMemory.configured)
        assertTrue(myMemory.supportsFullPage)
        val google = TranslationSettings(provider = TranslationProvider.GOOGLE)
        assertTrue(google.configured)
        assertTrue(google.supportsFullPage)
    }

    @Test
    fun `cloud providers require their own credential or endpoint`() {
        assertFalse(TranslationSettings(provider = TranslationProvider.AZURE).configured)
        assertTrue(TranslationSettings(provider = TranslationProvider.AZURE, azureKey = "key").supportsFullPage)
        assertFalse(TranslationSettings(provider = TranslationProvider.DEEPL).configured)
        assertTrue(TranslationSettings(provider = TranslationProvider.DEEPL, deeplKey = "key:fx").supportsFullPage)
        assertFalse(TranslationSettings(provider = TranslationProvider.LIBRETRANSLATE).configured)
        assertTrue(TranslationSettings(provider = TranslationProvider.LIBRETRANSLATE,
            libreEndpoint = "https://translate.example.com").supportsFullPage)
    }

    @Test
    fun `google chunks do not spin on surrogate pairs`() {
        val original = "😀".repeat(900)
        val pieces = TranslationClient(TranslationSettings()).splitForUrl(original, 1_800, 6_000)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { java.net.URLEncoder.encode(it, "UTF-8").length <= 6_000 })
        assertEquals(original, pieces.joinToString(""))
    }

    @Test
    fun `google chunks respect the encoded URI budget`() {
        val original = "中文段落".repeat(400)
        val pieces = TranslationClient(TranslationSettings()).splitForUrl(original, 1_800, 6_000)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { java.net.URLEncoder.encode(it, "UTF-8").length <= 6_000 })
        assertEquals(original, pieces.joinToString(""))
    }

    @Test
    fun `source detection covers scripts that MyMemory needs a concrete pair for`() {
        assertEquals("zh-CN", detectSourceLanguage("这是一个中文句子"))
        assertEquals("ja", detectSourceLanguage("これは日本語です"))
        assertEquals("ko", detectSourceLanguage("한국어 문장"))
        assertEquals("ru", detectSourceLanguage("Русский текст"))
        assertEquals("ar", detectSourceLanguage("نص عربي"))
        assertEquals("en", detectSourceLanguage("An English sentence"))
        assertEquals("fr", detectSourceLanguage("Les enfants sont dans la maison avec une lumière"))
        assertEquals("de", detectSourceLanguage("Die Kinder sind nicht mit dem Fahrrad auf der Straße"))
        assertEquals("es", detectSourceLanguage("Los niños están en la casa y el perro duerme"))
        assertEquals("pt", detectSourceLanguage("Os livros não estão na mesa com uma caneta"))
        assertEquals("it", detectSourceLanguage("Il libro è per che non è nella stanza"))
    }
}
