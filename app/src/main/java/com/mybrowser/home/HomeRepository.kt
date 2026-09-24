package com.mybrowser.home

import android.annotation.SuppressLint
import android.util.Log
import com.mybrowser.data.commitConfirmed
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import androidx.core.graphics.scale
import com.mybrowser.core.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** The two user-selectable meanings of the browser's Home action. */
enum class HomepageMode {
    NAVIGATION,
    FIXED_URL,
}

data class HomeSettings(
    val mode: HomepageMode,
    val fixedUrl: String,
    val restoreLastSession: Boolean = false,
)

/** A persisted tile shown on the native navigation homepage. */
data class HomeShortcut(
    val id: String,
    val title: String,
    val url: String,
    val icon: Bitmap?,
    val createdAt: Long,
)

sealed interface ShortcutIconChange {
    data object Keep : ShortcutIconChange
    data object UseText : ShortcutIconChange
    data class Replace(val bitmap: Bitmap) : ShortcutIconChange
}

enum class ShortcutSaveResult { SAVED, INVALID_URL, DUPLICATE_URL, NOT_FOUND, ICON_ERROR, FAILED }

/**
 * Owns homepage settings and shortcut persistence.
 *
 * Metadata is a small bounded JSON preference. Favicons live in the app's files directory:
 * they are user-created homepage data, not disposable WebView cache, and therefore survive
 * "clear browsing data". Every bitmap is scaled before writing and decoded only after a
 * bounds pass so a corrupt file cannot allocate an arbitrary image.
 */
class HomeRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val iconDirectory = File(appContext.filesDir, ICON_DIRECTORY).apply { mkdirs() }

    fun loadSettings(): HomeSettings {
        val fixedUrl = prefs.getString(KEY_FIXED_URL, null)
            ?.trim()
            ?.takeIf(UrlUtils::isHttpUrl)
            ?: DEFAULT_FIXED_URL
        val savedMode = prefs.getString(KEY_MODE, null)
            ?.let { raw -> HomepageMode.entries.firstOrNull { it.name == raw } }

        // Preserve the behaviour of people who explicitly configured the old single URL.
        // A fresh install gets the new navigation homepage by default.
        val mode = savedMode ?: if (prefs.contains(KEY_FIXED_URL)) {
            HomepageMode.FIXED_URL
        } else {
            HomepageMode.NAVIGATION
        }
        return HomeSettings(mode, fixedUrl, prefs.getBoolean(KEY_RESTORE_SESSION, false))
    }

    /** Batch import: absent fields and navigation tiles remain untouched. */
    fun importSettings(mode: HomepageMode?, fixedUrl: String?, restoreLastSession: Boolean?) {
        val cleanUrl = fixedUrl?.trim()
        require(cleanUrl == null || UrlUtils.isHttpUrl(cleanUrl)) { "Invalid homepage URL" }
        prefs.commitConfirmed(buildMap {
            mode?.let { put(KEY_MODE, it.name) }
            cleanUrl?.let { put(KEY_FIXED_URL, it) }
            restoreLastSession?.let { put(KEY_RESTORE_SESSION, it) }
        })
    }

    fun saveMode(mode: HomepageMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
    }

    fun saveRestoreLastSession(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_RESTORE_SESSION, enabled) }
    }

    fun saveFixedUrl(url: String): Boolean {
        val clean = url.trim()
        if (!UrlUtils.isHttpUrl(clean)) return false
        prefs.edit { putString(KEY_FIXED_URL, clean) }
        return true
    }

    @Synchronized
    fun loadShortcuts(): List<HomeShortcut> = readRecords().map { record ->
        HomeShortcut(
            id = record.id,
            title = record.title,
            url = record.url,
            icon = record.iconFile?.let(::decodeIcon),
            createdAt = record.createdAt,
        )
    }

    /** Adds a tile or updates the existing tile with the same URL, preserving its position. */
    @Synchronized
    fun upsertShortcut(title: String, url: String, favicon: Bitmap?): List<HomeShortcut> {
        val cleanUrl = url.trim()
        require(UrlUtils.isHttpUrl(cleanUrl)) { "homepage shortcut must be HTTP(S)" }
        val cleanTitle = sanitizeTitle(title).ifBlank { UrlUtils.hostOf(cleanUrl).orEmpty() }
            .ifBlank { cleanUrl }

        val records = readRecords().toMutableList()
        val existingIndex = records.indexOfFirst { it.url == cleanUrl }
        val old = records.getOrNull(existingIndex)
        // Reject a new tile before allocating an id or writing its favicon. Writing first
        // left an unreferenced PNG behind whenever the bounded dashboard was already full.
        if (old == null && records.size >= MAX_SHORTCUTS) return loadShortcuts()
        val id = old?.id ?: UUID.randomUUID().toString()
        val iconFile = when {
            old?.customIcon == true -> old.iconFile
            favicon != null -> writeIcon(id, favicon) ?: old?.iconFile
            else -> old?.iconFile
        }
        val updated = Record(
            id = id,
            title = cleanTitle,
            url = cleanUrl,
            iconFile = iconFile,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            customIcon = old?.customIcon ?: false,
        )
        if (existingIndex >= 0) records[existingIndex] = updated
        else if (records.size < MAX_SHORTCUTS) records += updated

        if (!persist(records)) {
            if (iconFile != old?.iconFile) deleteIcon(iconFile)
            throw IOException("Unable to save homepage shortcut")
        }
        if (iconFile != old?.iconFile) deleteIcon(old?.iconFile)
        return loadShortcuts()
    }

    /** Editing uses the stable id so changing a URL never creates or replaces another tile. */
    @Synchronized
    fun updateShortcut(id: String, title: String, url: String, icon: ShortcutIconChange): ShortcutSaveResult {
        val cleanUrl = if (UrlUtils.isNavigableInput(url)) UrlUtils.normalizeOrSearch(url, "") else ""
        if (!UrlUtils.isHttpUrl(cleanUrl)) return ShortcutSaveResult.INVALID_URL
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return ShortcutSaveResult.NOT_FOUND
        if (records.any { it.id != id && it.url == cleanUrl }) return ShortcutSaveResult.DUPLICATE_URL
        val old = records[index]
        val iconFile = when (icon) {
            ShortcutIconChange.Keep -> old.iconFile
            ShortcutIconChange.UseText -> null
            is ShortcutIconChange.Replace -> writeIcon(id, icon.bitmap) ?: return ShortcutSaveResult.ICON_ERROR
        }
        records[index] = old.copy(
            title = sanitizeTitle(title).ifBlank { UrlUtils.hostOf(cleanUrl).orEmpty() }.ifBlank { cleanUrl },
            url = cleanUrl,
            iconFile = iconFile,
            customIcon = old.customIcon || icon != ShortcutIconChange.Keep,
        )
        if (!persist(records)) {
            if (iconFile != old.iconFile) deleteIcon(iconFile)
            return ShortcutSaveResult.FAILED
        }
        if (iconFile != old.iconFile) deleteIcon(old.iconFile)
        return ShortcutSaveResult.SAVED
    }

    @Synchronized
    fun removeShortcut(id: String): List<HomeShortcut> {
        if (!ID_PATTERN.matches(id)) return loadShortcuts()
        val records = readRecords().toMutableList()
        val removed = records.firstOrNull { it.id == id } ?: return loadShortcuts()
        records.removeAll { it.id == id }
        if (!persist(records)) throw IOException("Unable to remove homepage shortcut")
        deleteIcon(removed.iconFile)
        return loadShortcuts()
    }

    private fun readRecords(): List<Record> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        // A value this large cannot be read, and it is deliberately not deleted: destroying the
        // user's shortcuts to recover from a corrupt preference is worse than reporting none of
        // them for now. The length check above keeps every read cheap, the log makes the failure
        // diagnosable, and the next persisted edit replaces the value with the valid set.
        if (raw.length > MAX_METADATA_LENGTH) {
            Log.w(TAG, "Homepage shortcuts exceed $MAX_METADATA_LENGTH characters; ignoring the stored value")
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_SHORTCUTS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val url = item.optString("url").trim()
                    if (!ID_PATTERN.matches(id) || !UrlUtils.isHttpUrl(url)) continue
                    val title = sanitizeTitle(item.optString("title"))
                        .ifBlank { UrlUtils.hostOf(url).orEmpty() }
                        .ifBlank { url }
                    val iconName = item.optString("icon").takeIf(ICON_NAME_PATTERN::matches)
                    add(
                        Record(
                            id = id,
                            title = title,
                            url = url,
                            iconFile = iconName,
                            createdAt = item.optLong("createdAt", 0L)
                                .takeIf { it > 0L } ?: System.currentTimeMillis(),
                            customIcon = item.optBoolean("customIcon", false),
                        ),
                    )
                }
            }.distinctBy { it.url }
        }.getOrElse { error ->
            // Same rule as the length check above: report none, keep the value, say why.
            Log.w(TAG, "Unreadable homepage shortcuts; ignoring the stored value", error)
            emptyList()
        }
    }

    /** Callers run on IO: report success only after the metadata reaches storage. */
    @SuppressLint("UseKtx") // KTX edit returns Unit; the commit result gates image cleanup.
    private fun persist(records: List<Record>): Boolean {
        val array = JSONArray()
        records.take(MAX_SHORTCUTS).forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("title", record.title)
                    .put("url", record.url)
                    .put("icon", record.iconFile ?: JSONObject.NULL)
                    .put("createdAt", record.createdAt)
                    .put("customIcon", record.customIcon),
            )
        }
        val previous = prefs.getString(KEY_SHORTCUTS, null)
        if (prefs.edit().putString(KEY_SHORTCUTS, array.toString()).commit()) return true
        // SharedPreferences updates its in-memory map even when commit returns false.
        // Restore the previous map as well so a failed edit cannot reappear on reload.
        prefs.edit(commit = true) { putString(KEY_SHORTCUTS, previous) }
        return false
    }

    private fun writeIcon(id: String, bitmap: Bitmap): String? {
        if (!ID_PATTERN.matches(id) || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 ||
            bitmap.width.toLong() * bitmap.height.toLong() > MAX_SOURCE_PIXELS
        ) return null

        // Keep the previous image intact until the metadata commit succeeds. The stable
        // shortcut id is independent of its image filename, including for older records.
        val fileName = "${UUID.randomUUID()}.png"
        val target = safeIconFile(fileName) ?: return null
        val temporary = safeIconFile("$id.tmp") ?: return null
        val scale = minOf(1f, ICON_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            bitmap.scale(width, height, true)
        }

        return try {
            FileOutputStream(temporary).use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                output.fd.sync()
            }
            if (temporary.length() !in 1..MAX_ICON_BYTES) {
                return null
            }
            // Publish only a fully written image; metadata still references the old file.
            if (!temporary.renameTo(target)) {
                return null
            }
            fileName
        } catch (_: RuntimeException) {
            null
        } catch (_: IOException) {
            null
        } finally {
            temporary.delete()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun deleteIcon(name: String?) {
        name?.let { safeIconFile(it)?.delete() }
    }

    private fun decodeIcon(name: String): Bitmap? {
        val file = safeIconFile(name)?.takeIf { it.isFile && it.length() in 1..MAX_ICON_BYTES }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..MAX_DECODE_EDGE || bounds.outHeight !in 1..MAX_DECODE_EDGE) {
            return null
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** Resolves only validated leaf names inside the private icon directory. */
    private fun safeIconFile(name: String): File? {
        if (!ICON_NAME_PATTERN.matches(name) && !TEMP_NAME_PATTERN.matches(name)) return null
        // Both accepted patterns consist solely of a bounded ASCII basename and a fixed
        // extension, so separators and traversal are impossible. Avoiding canonicalFile
        // also keeps malformed storage state from surfacing an IOException to callers.
        return File(iconDirectory, name)
    }

    private fun sanitizeTitle(value: String): String = value
        .filterNot { it.isISOControl() }
        .trim()
        .take(MAX_TITLE_LENGTH)

    private data class Record(
        val id: String,
        val title: String,
        val url: String,
        val iconFile: String?,
        val createdAt: Long,
        val customIcon: Boolean = false,
    )

    private companion object {
        const val TAG = "HomeRepository"
        const val PREFS_NAME = "browser_settings"
        const val KEY_MODE = "homepage_mode"
        const val KEY_RESTORE_SESSION = "restore_last_session"
        // Keep the old key so existing installations migrate without losing their URL.
        const val KEY_FIXED_URL = "homepage"
        const val KEY_SHORTCUTS = "homepage_shortcuts"
        const val ICON_DIRECTORY = "homepage_icons"
        const val DEFAULT_FIXED_URL = "https://www.bing.com"
        const val MAX_SHORTCUTS = 24
        const val MAX_TITLE_LENGTH = 128
        const val MAX_METADATA_LENGTH = 256 * 1024
        const val ICON_EDGE = 96
        const val MAX_DECODE_EDGE = 512
        const val MAX_ICON_BYTES = 512L * 1024L
        const val MAX_SOURCE_PIXELS = 16L * 1024L * 1024L
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val ICON_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.png")
        val TEMP_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.tmp")
    }
}
