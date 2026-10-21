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
    fun `mymemory is configured but cannot translate a whole page`() {
        val settings = TranslationSettings(provider = TranslationProvider.MYMEMORY)
        assertTrue(settings.configured)
        assertFalse(settings.supportsFullPage)
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
    fun `source detection covers scripts that MyMemory needs a concrete pair for`() {
        assertEquals("zh-CN", detectSourceLanguage("这是一个中文句子"))
        assertEquals("ja", detectSourceLanguage("これは日本語です"))
        assertEquals("ko", detectSourceLanguage("한국어 문장"))
        assertEquals("ru", detectSourceLanguage("Русский текст"))
        assertEquals("ar", detectSourceLanguage("نص عربي"))
        assertEquals("en", detectSourceLanguage("An English sentence"))
    }
}
