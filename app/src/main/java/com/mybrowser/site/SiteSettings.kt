package com.mybrowser.site

import com.mybrowser.data.commitConfirmed
import android.content.Context
import androidx.core.net.toUri
import com.mybrowser.core.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val externalApps: SitePermission = SitePermission.ASK,
    val webDarkening: Boolean = true,
    val desktopWidth: Int = 1024,
    // Null inherits the browser default; an explicit choice belongs to this origin only.
    val enhancedPlayback: Boolean? = null,
) {
    fun useEnhancedPlayback(default: Boolean): Boolean = enhancedPlayback ?: default

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

    fun forPrivateSession(): SiteSettings = SiteCapability.entries.fold(copy(
        externalApps = if (externalApps == SitePermission.BLOCK) externalApps else SitePermission.ASK,
    )) { settings, capability ->
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
    private val stored = if (prefs == null) initial else decode(prefs.getString("sites", null))
    private val repairState = MutableStateFlow(prefs != null && stored == null)
    val needsRepair = repairState.asStateFlow()
    private val mutable = MutableStateFlow(Snapshot(stored ?: emptyMap()))
    val entries: StateFlow<Map<String, SiteSettings>> = mutable.asStateFlow()

    fun get(url: String): SiteSettings = settingsFor(mutable.value, url)

    suspend fun update(url: String, transform: (SiteSettings) -> SiteSettings) = withContext(Dispatchers.IO) {
        val origin = requireNotNull(SiteOrigin.of(url))
        mutex.withLock {
            val next = mutable.value.toMutableMap()
            val desktopSite = requireNotNull(DesktopSite.of(origin))
            val settings = transform(settingsFor(mutable.value, origin)).let { it.copy(textZoom = it.textZoom.coerceIn(50, 200),
                desktopWidth = it.desktopWidth.takeIf { width -> width in DESKTOP_WIDTHS } ?: 1024) }
            next[origin] = settings
            // One display choice across existing aliases, committed atomically. Do
            // not create extra origins or copy any permission to another host.
            next.replaceAll { site, value ->
                if (DesktopSite.of(site) == desktopSite) value.copy(desktop = settings.desktop, desktopWidth = settings.desktopWidth) else value
            }
            next.entries.removeAll { it.value == SiteSettings() }
            require(next.size <= MAX_SITES) { "Site settings limit reached" }
            save(next)
        }
    }

    suspend fun reset(url: String) = update(url) { SiteSettings() }

    /**
     * Replaces the migratable subset of every origin's settings from a settings import.
     * Permission decisions are never taken from the file: origins it lists keep their
     * current permissions (new origins start at ASK), and origins it does not list keep
     * their permissions while their migratable fields return to defaults. Fails without
     * touching the store when the store is unreadable — imports must not bypass the
     * same corruption protection as every other write.
     */
    suspend fun applyImported(migratable: Map<String, (SiteSettings) -> SiteSettings>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = mutable.value.mapValues { (origin, current) ->
                val transform = migratable[origin]
                if (transform != null) transform(current) else current.copy(
                    filtering = true, javaScript = true, images = true, thirdPartyCookies = true,
                    desktop = false, textZoom = 100, webDarkening = true, desktopWidth = 1024,
                    enhancedPlayback = null,
                )
            }.toMutableMap()
            migratable.forEach { (origin, transform) ->
                if (origin !in next) next[origin] = transform(SiteSettings())
            }
            next.entries.removeAll { it.value == SiteSettings() }
            require(next.size <= MAX_SITES) { "Site settings limit reached" }
            save(next)
        }
    }

    suspend fun clearPermissions() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = mutable.value.mapValues { (_, value) ->
                SiteCapability.entries.fold(value.copy(externalApps = SitePermission.ASK)) { settings, capability -> settings.withPermission(capability, SitePermission.ASK) }
            }.filterValues { it != SiteSettings() }
            // A corrupted store must stay untouched here; only the confirmed repair() may
            // replace unreadable bytes. clearPermissions() fails loudly on such a store.
            save(next)
        }
    }

    fun privateSession() = SiteSettingsRepository(null, mutable.value.mapValues { it.value.forPrivateSession() })

    /** Discard an unreadable store only after the user confirms resetting settings. */
    suspend fun repair() = withContext(Dispatchers.IO) {
        mutex.withLock { if (repairState.value) save(emptyMap(), force = true) }
    }

    @android.annotation.SuppressLint("UseKtx") // KTX edit discards the commit result; publishing requires a confirmed write.
    private fun save(next: Map<String, SiteSettings>, force: Boolean = false) {
        check(!repairState.value || force) { "Stored site settings are unreadable; reset them before saving" }
        val snapshot = Snapshot(next.toMap())
        val json = JSONObject()
        next.forEach { (origin, settings) ->
            val entry = JSONObject().put("filtering", settings.filtering).put("javascript", settings.javaScript)
                .put("images", settings.images).put("thirdPartyCookies", settings.thirdPartyCookies)
                .put("desktop", settings.desktop).put("textZoom", settings.textZoom)
                .put("externalApps", settings.externalApps.name)
                .put("webDarkening", settings.webDarkening).put("desktopWidth", settings.desktopWidth)
                .put("enhancedPlayback", settings.enhancedPlayback)
            SiteCapability.entries.forEach { entry.put(it.name, settings.permission(it).name) }
            json.put(origin, entry)
        }
        val text = json.toString()
        require(text.length <= 512 * 1024) { "Site settings storage limit reached" }
        prefs?.commitConfirmed(mapOf("sites" to text))
        mutable.value = snapshot
        repairState.value = false
    }

    /** Compute desktop aliases once per write, keeping origin permissions and the index together. */
    private class Snapshot(settings: Map<String, SiteSettings>) : Map<String, SiteSettings> by settings {
        val desktop = buildMap<String, SiteSettings> {
            settings.forEach { (origin, value) ->
                if (value.desktop) DesktopSite.of(origin)?.let { key -> if (key !in this) put(key, value) }
            }
        }

        override fun equals(other: Any?) = other is Map<*, *> && entries == other.entries
        override fun hashCode() = entries.hashCode()
    }

    companion object {
        val DESKTOP_WIDTHS = listOf(0, 980, 1024, 1280, 1440)
        const val MAX_SITES = 256
        private fun settingsFor(sites: Snapshot, url: String): SiteSettings {
            val desktopSite = DesktopSite.of(url)
            // Read legacy exact-origin records directly; no lossy migration or extra
            // storage entries. An enabled alias remains effective until explicitly
            // disabled/reset from any of the same site's presentation aliases.
            val presentation = desktopSite?.let(sites.desktop::get)
            return (sites[SiteOrigin.of(url)] ?: SiteSettings()).let {
                it.copy(desktop = presentation != null, desktopWidth = presentation?.desktopWidth ?: it.desktopWidth)
            }
        }

        private fun decode(raw: String?): Map<String, SiteSettings>? = runCatching {
            if (raw == null) return emptyMap()
            if (raw.length > 512 * 1024) return null
            val json = JSONObject(raw)
            buildMap {
                json.keys().asSequence().take(MAX_SITES).forEach { key ->
                    val origin = SiteOrigin.of(key)?.takeIf { it == key } ?: return@forEach
                    val entry = json.optJSONObject(key) ?: return@forEach
                    var value = SiteSettings(filtering = entry.optBoolean("filtering", true),
                        javaScript = entry.optBoolean("javascript", true), images = entry.optBoolean("images", true),
                        thirdPartyCookies = entry.optBoolean("thirdPartyCookies", true), desktop = entry.optBoolean("desktop", false),
                        textZoom = entry.optInt("textZoom", 100).coerceIn(50, 200),
                        externalApps = runCatching { SitePermission.valueOf(entry.optString("externalApps")) }.getOrDefault(SitePermission.ASK),
                        webDarkening = entry.optBoolean("webDarkening", true),
                        desktopWidth = entry.optInt("desktopWidth", 1024).takeIf { it in DESKTOP_WIDTHS } ?: 1024,
                        enhancedPlayback = entry.opt("enhancedPlayback") as? Boolean)
                    SiteCapability.entries.forEach { capability ->
                        val permission = runCatching { SitePermission.valueOf(entry.optString(capability.name)) }.getOrDefault(SitePermission.ASK)
                        value = value.withPermission(capability, permission)
                    }
                    put(origin, value)
                }
            }
        }.getOrNull()
    }
}
