package com.mybrowser.devtools

import android.annotation.SuppressLint
import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads Cookies, Local Storage and Session Storage from the page currently on screen.
 *
 * Cookies go through CookieManagerCompat.getCookieInfo first: document.cookie cannot see
 * HttpOnly cookies and cannot report Domain/Path/SameSite. The API is URL-scoped, so the
 * interface never claims to list every cookie of the site. Storage is read in the page's own
 * context, with each value bounded; the full value is fetched only when the user asks for it.
 */
internal class StorageReader(private val session: DevToolsSession) {

    enum class Kind(val js: String) { LOCAL("localStorage"), SESSION("sessionStorage") }

    @SuppressLint("RequiresFeature")
    suspend fun cookies(url: String): PageResult<CookieResult> {
        val boundedUrl = url.take(DevToolsBudgets.URL_CHARS)
        if (!boundedUrl.startsWith("http://") && !boundedUrl.startsWith("https://")) {
            return PageResult.Failed(null)
        }
        // Resolve the manager on the WebView's thread, but do the cookie IPC off it.
        val manager = withContext(Dispatchers.Main.immediate) {
            // CookieManager.getInstance() is the default profile. A profile-isolated incognito
            // WebView has its own manager; reading the default one would show normal cookies
            // and miss the private page's cookies.
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    WebViewCompat.getProfile(session.view)?.cookieManager
                } else null
            }.getOrNull() ?: runCatching { CookieManager.getInstance() }.getOrNull()
        } ?: return PageResult.Failed(null)
        return withContext(Dispatchers.IO) {
            val rich = runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)
            }.getOrDefault(false)
            if (rich) {
                val info = runCatching { CookieManagerCompat.getCookieInfo(manager, boundedUrl) }
                    .getOrNull()
                if (info != null) {
                    return@withContext PageResult.Ok(
                        CookieResult(
                            cookies = info.mapNotNull(::parseCookieInfoLine).sortedBy { it.name.lowercase() },
                            richAttributes = true,
                        )
                    )
                }
            }
            // Fallback: CookieManager still returns HttpOnly names and values, but no attributes.
            val header = runCatching { manager.getCookie(boundedUrl) }.getOrNull().orEmpty()
            PageResult.Ok(
                CookieResult(
                    cookies = parseCookieHeader(header).sortedBy { it.name.lowercase() },
                    richAttributes = false,
                )
            )
        }
    }

    suspend fun list(kind: Kind): PageResult<StorageSnapshot> {
        val expected = session.documentId
        val script = listScript(kind)
        val raw = session.evaluateInDocument(script) ?: return PageResult.Unavailable
        if (!session.isCurrentDocument(expected)) return PageResult.Unavailable
        val payload = DevToolsJs.decode(raw) ?: return PageResult.Unavailable
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(payload) }.getOrNull()
                ?: return@withContext PageResult.Failed(null)
            if (!json.optBoolean("ok")) {
                return@withContext PageResult.Failed(json.optString("error").take(256))
            }
            val array = json.optJSONArray("entries") ?: return@withContext PageResult.Ok(
                StorageSnapshot(json.optString("origin").take(512), emptyList(), 0, false)
            )
            val entries = ArrayList<StorageEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONArray(index) ?: continue
                val name = item.optString(0).take(DevToolsBudgets.STORAGE_VALUE_PREVIEW_CHARS)
                val value = item.optString(1).take(DevToolsBudgets.STORAGE_VALUE_PREVIEW_CHARS)
                val chars = item.optInt(2, value.length).coerceAtLeast(0)
                if (name.isBlank() && value.isBlank() && chars == 0) continue
                entries += StorageEntry(name, value, chars, chars > value.length)
            }
            PageResult.Ok(
                StorageSnapshot(
                    origin = json.optString("origin").take(512),
                    entries = entries,
                    total = json.optInt("total", entries.size).coerceAtLeast(entries.size),
                    truncated = json.optBoolean("truncated"),
                )
            )
        }
    }

    suspend fun value(kind: Kind, name: String, offset: Int): PageResult<StorageValue> {
        val expected = session.documentId
        val script = valueScript(kind, name, offset)
        val raw = session.evaluateInDocument(script) ?: return PageResult.Unavailable
        if (!session.isCurrentDocument(expected)) return PageResult.Unavailable
        val payload = DevToolsJs.decode(raw) ?: return PageResult.Unavailable
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(payload) }.getOrNull()
                ?: return@withContext PageResult.Failed(null)
            if (!json.optBoolean("ok")) {
                return@withContext PageResult.Failed(json.optString("error").take(256))
            }
            if (json.optBoolean("missing")) return@withContext PageResult.Invalid
            val value = json.optString("value")
            val total = json.optInt("totalChars", value.length)
            PageResult.Ok(
                StorageValue(
                    name = name,
                    value = value,
                    offset = json.optInt("offset", offset),
                    totalChars = total,
                    truncated = json.optInt("offset", offset) + value.length < total,
                )
            )
        }
    }

    private fun listScript(kind: Kind): String = """
        (function(){try{
        var s=window.${kind.js};var out=[];var total=s.length;var totalChars=0;
        var limit=${DevToolsBudgets.STORAGE_ITEMS};
        var perValue=${DevToolsBudgets.STORAGE_VALUE_PREVIEW_CHARS};
        var budget=${DevToolsBudgets.STORAGE_TOTAL_PREVIEW_CHARS};
        for(var i=0;i<total&&out.length<limit;i++){
          var k=s.key(i);var v=s.getItem(k);if(v===null)v='';
          var chars=v.length;var preview=chars>perValue?v.slice(0,perValue):v;
          totalChars+=preview.length;
          if(totalChars>budget)break;
          out.push([k,preview,chars]);
        }
        return JSON.stringify({ok:true,origin:location.origin,entries:out,total:total,
          truncated:out.length<total||totalChars>budget});
        }catch(e){return JSON.stringify({ok:false,error:String((e&&e.name)||e)+
          (e&&e.message?': '+e.message:'')});}})()
    """.trimIndent()

    private fun valueScript(kind: Kind, name: String, offset: Int): String = """
        (function(){try{
        var s=window.${kind.js};var v=s.getItem(${JSONObject.quote(name)});
        if(v===null)return JSON.stringify({ok:true,missing:true});
        var start=Math.max(0,Math.min(v.length,$offset));
        var size=${DevToolsBudgets.STORAGE_VALUE_READ_CHARS};
        return JSON.stringify({ok:true,offset:start,value:v.slice(start,start+size),totalChars:v.length});
        }catch(e){return JSON.stringify({ok:false,error:String((e&&e.name)||e)+
          (e&&e.message?': '+e.message:'')});}})()
    """.trimIndent()

    companion object {
        /** Parses one `name=value; Path=/; Secure` line without splitting on commas. */
        internal fun parseCookieInfoLine(line: String): CookieRow? {
            val segments = line.split(';')
            val first = segments.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return null
            val equals = first.indexOf('=')
            val name = if (equals < 0) first else first.substring(0, equals)
            if (name.isEmpty()) return null
            val value = if (equals < 0) "" else first.substring(equals + 1)
            val attributes = HashMap<String, String?>()
            for (segment in segments.drop(1)) {
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) continue
                val at = trimmed.indexOf('=')
                val key = (if (at < 0) trimmed else trimmed.substring(0, at)).trim().lowercase()
                if (key.isEmpty()) continue
                attributes[key] = if (at < 0) "true" else trimmed.substring(at + 1).trim()
            }
            return CookieRow(name, value, attributes)
        }

        /** Parses a Cookie header value; repeated names are kept and `=` inside values survives. */
        internal fun parseCookieHeader(header: String): List<CookieRow> {
            if (header.isBlank()) return emptyList()
            return header.split(';').mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val at = trimmed.indexOf('=')
                val name = (if (at < 0) trimmed else trimmed.substring(0, at)).trim()
                if (name.isEmpty()) return@mapNotNull null
                CookieRow(name, if (at < 0) "" else trimmed.substring(at + 1).trim(), emptyMap())
            }
        }
    }
}
