package com.mybrowser.site

import android.content.Context
import androidx.core.net.toUri
import com.mybrowser.core.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.IDN
import java.util.Locale

/** Permissions use the full origin. A grant never silently spreads to a subdomain or port. */
object SiteOrigin {
    fun of(url: String): String? = runCatching {
        if (!UrlUtils.isHttpUrl(url) || url.length > 8192) return null
        val uri = url.toUri()
        val scheme = uri.scheme!!.lowercase(Locale.ROOT)
        val rawHost = uri.host ?: return null
        val host = if (rawHost.contains(':')) "[${rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT)}]"
            else IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()

    fun canRequestPermission(origin: String): Boolean = origin.startsWith("https://") ||
        origin.toUri().host?.removeSurrounding("[", "]") in setOf("localhost", "127.0.0.1", "::1")
}

enum class SitePermission { ASK, ALLOW, BLOCK }
enum class SiteCapability { CAMERA, MICROPHONE, LOCATION, PROTECTED_MEDIA }

data class SiteSettings(
    val filtering: Boolean = true,
    val javaScript: Boolean = true,
    val images: Boolean = true,
    val thirdPartyCookies: Boolean = true,
    val desktop: Boolean = false,
    val textZoom: Int = 100,
    val camera: SitePermission = SitePermission.ASK,
    val microphone: SitePermission = SitePermission.ASK,
    val location: SitePermission = SitePermission.ASK,
    val protectedMedia: SitePermission = SitePermission.ASK,
) {
    fun permission(capability: SiteCapability) = when (capability) {
        SiteCapability.CAMERA -> camera
        SiteCapability.MICROPHONE -> microphone
        SiteCapability.LOCATION -> location
        SiteCapability.PROTECTED_MEDIA -> protectedMedia
    }

    fun withPermission(capability: SiteCapability, permission: SitePermission) = when (capability) {
        SiteCapability.CAMERA -> copy(camera = permission)
        SiteCapability.MICROPHONE -> copy(microphone = permission)
        SiteCapability.LOCATION -> copy(location = permission)
        SiteCapability.PROTECTED_MEDIA -> copy(protectedMedia = permission)
    }

    fun forPrivateSession(): SiteSettings = SiteCapability.entries.fold(this) { settings, capability ->
        settings.withPermission(capability, if (permission(capability) == SitePermission.BLOCK)
            SitePermission.BLOCK else SitePermission.ASK)
    }
}

/** Immutable snapshots are safe to read in WebView callbacks; disk writes are serialized. */
class SiteSettingsRepository private constructor(
    private val prefs: android.content.SharedPreferences?,
    initial: Map<String, SiteSettings>,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("site_settings", Context.MODE_PRIVATE), emptyMap(),
    )

    private val mutex = Mutex()
    private val mutable = MutableStateFlow(if (prefs == null) initial else decode(prefs.getString("sites", null)))
    val entries = mutable.asStateFlow()

    fun get(url: String): SiteSettings = mutable.value[SiteOrigin.of(url)] ?: SiteSettings()

    suspend fun update(url: String, transform: (SiteSettings) -> SiteSettings) = withContext(Dispatchers.IO) {
        val origin = requireNotNull(SiteOrigin.of(url))
        mutex.withLock {
            val next = mutable.value.toMutableMap()
            val settings = transform(next[origin] ?: SiteSettings()).let { it.copy(textZoom = it.textZoom.coerceIn(50, 200)) }
            if (settings == SiteSettings()) next.remove(origin) else {
                require(origin in next || next.size < MAX_SITES) { "Site settings limit reached" }
                next[origin] = settings
            }
            save(next)
        }
    }

    suspend fun reset(url: String) = update(url) { SiteSettings() }

    suspend fun clearPermissions() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = mutable.value.mapValues { (_, value) ->
                SiteCapability.entries.fold(value) { settings, capability -> settings.withPermission(capability, SitePermission.ASK) }
            }.filterValues { it != SiteSettings() }
            save(next)
        }
    }

    fun privateSession() = SiteSettingsRepository(null, mutable.value.mapValues { it.value.forPrivateSession() })

    @android.annotation.SuppressLint("UseKtx") // KTX edit discards the commit result; publishing requires a confirmed write.
    private fun save(next: Map<String, SiteSettings>) {
        val json = JSONObject()
        next.forEach { (origin, settings) ->
            val entry = JSONObject().put("filtering", settings.filtering).put("javascript", settings.javaScript)
                .put("images", settings.images).put("thirdPartyCookies", settings.thirdPartyCookies)
                .put("desktop", settings.desktop).put("textZoom", settings.textZoom)
            SiteCapability.entries.forEach { entry.put(it.name, settings.permission(it).name) }
            json.put(origin, entry)
        }
        val text = json.toString()
        require(text.length <= 512 * 1024) { "Site settings storage limit reached" }
        check(prefs == null || prefs.edit().putString("sites", text).commit()) { "Unable to save site settings" }
        mutable.value = next.toMap()
    }

    companion object {
        private const val MAX_SITES = 256
        private fun decode(raw: String?): Map<String, SiteSettings> = runCatching {
            if (raw == null || raw.length > 512 * 1024) return emptyMap()
            val json = JSONObject(raw)
            buildMap {
                json.keys().asSequence().take(MAX_SITES).forEach { key ->
                    val origin = SiteOrigin.of(key)?.takeIf { it == key } ?: return@forEach
                    val entry = json.optJSONObject(key) ?: return@forEach
                    var value = SiteSettings(filtering = entry.optBoolean("filtering", true),
                        javaScript = entry.optBoolean("javascript", true), images = entry.optBoolean("images", true),
                        thirdPartyCookies = entry.optBoolean("thirdPartyCookies", true), desktop = entry.optBoolean("desktop", false),
                        textZoom = entry.optInt("textZoom", 100).coerceIn(50, 200))
                    SiteCapability.entries.forEach { capability ->
                        val permission = runCatching { SitePermission.valueOf(entry.optString(capability.name)) }.getOrDefault(SitePermission.ASK)
                        value = value.withPermission(capability, permission)
                    }
                    put(origin, value)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
