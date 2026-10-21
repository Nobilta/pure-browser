package com.mybrowser.translate

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

enum class TranslationProvider {
    MYMEMORY,
    AZURE,
    DEEPL,
    LIBRETRANSLATE,
}

data class TranslationLanguage(val code: String, val label: String)

/**
 * User-visible translation preferences. API keys are kept in the same app-private preference file
 * as the rest of the settings; the app disables Android backup, so they never leave the sandbox
 * through a backup. They are never included in settings export.
 */
data class TranslationSettings(
    val provider: TranslationProvider = TranslationProvider.MYMEMORY,
    val targetLanguage: String = defaultTranslationTarget(),
    val sourceLanguage: String = "auto",
    val azureKey: String = "",
    val azureRegion: String = "",
    val deeplKey: String = "",
    val libreEndpoint: String = "",
    val libreKey: String = "",
) {
    val supportsFullPage: Boolean
        get() = when (provider) {
            TranslationProvider.MYMEMORY -> false
            TranslationProvider.AZURE -> azureKey.isNotBlank()
            TranslationProvider.DEEPL -> deeplKey.isNotBlank()
            TranslationProvider.LIBRETRANSLATE -> libreEndpoint.trim().startsWith("http")
        }

    val configured: Boolean
        get() = when (provider) {
            TranslationProvider.MYMEMORY -> true
            TranslationProvider.AZURE -> azureKey.isNotBlank()
            TranslationProvider.DEEPL -> deeplKey.isNotBlank()
            TranslationProvider.LIBRETRANSLATE -> libreEndpoint.trim().startsWith("http")
        }

    /** Never let a data-class toString put an API key into a log or crash report. */
    override fun toString(): String =
        "TranslationSettings(provider=$provider, target=$targetLanguage, source=$sourceLanguage, configured=$configured)"
}

class TranslationSettingsRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("translation_settings", Context.MODE_PRIVATE)

    fun load(): TranslationSettings = TranslationSettings(
        provider = runCatching {
            TranslationProvider.valueOf(prefs.getString("provider", null).orEmpty())
        }.getOrDefault(TranslationProvider.MYMEMORY),
        targetLanguage = prefs.getString("target", null)?.takeIf { it.length <= 16 }
            ?: defaultTranslationTarget(),
        sourceLanguage = prefs.getString("source", null)?.takeIf { it.length <= 16 } ?: "auto",
        azureKey = prefs.getString("azure_key", null).orEmpty().take(512),
        azureRegion = prefs.getString("azure_region", null).orEmpty().take(64),
        deeplKey = prefs.getString("deepl_key", null).orEmpty().take(512),
        libreEndpoint = prefs.getString("libre_endpoint", null).orEmpty().take(2048),
        libreKey = prefs.getString("libre_key", null).orEmpty().take(512),
    )

    fun save(settings: TranslationSettings) {
        prefs.edit {
            putString("provider", settings.provider.name)
            putString("target", settings.targetLanguage)
            putString("source", settings.sourceLanguage)
            putString("azure_key", settings.azureKey)
            putString("azure_region", settings.azureRegion)
            putString("deepl_key", settings.deeplKey)
            putString("libre_endpoint", settings.libreEndpoint)
            putString("libre_key", settings.libreKey)
        }
    }
}

val TRANSLATION_TARGETS = listOf(
    TranslationLanguage("zh-CN", "简体中文"),
    TranslationLanguage("zh-TW", "繁體中文"),
    TranslationLanguage("en", "English"),
    TranslationLanguage("ja", "日本語"),
    TranslationLanguage("ko", "한국어"),
    TranslationLanguage("fr", "Français"),
    TranslationLanguage("de", "Deutsch"),
    TranslationLanguage("es", "Español"),
    TranslationLanguage("ru", "Русский"),
    TranslationLanguage("ar", "العربية"),
)

val TRANSLATION_SOURCES = listOf(TranslationLanguage("auto", "Auto")) + TRANSLATION_TARGETS

fun defaultTranslationTarget(locale: Locale = Locale.getDefault()): String = when (locale.language) {
    "zh" -> if (locale.country == "TW" || locale.country == "HK" || locale.country == "MO" ||
        locale.script == "Hant"
    ) "zh-TW" else "zh-CN"
    "ja" -> "ja"
    "ko" -> "ko"
    "fr" -> "fr"
    "de" -> "de"
    "es" -> "es"
    "ru" -> "ru"
    "ar" -> "ar"
    else -> "en"
}
