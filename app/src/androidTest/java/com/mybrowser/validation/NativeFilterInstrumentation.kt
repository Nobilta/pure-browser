package com.mybrowser.validation

import android.app.Instrumentation
import android.os.Bundle
import com.mybrowser.core.ResourceType
import com.mybrowser.filter.NativeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No runner dependency enters the app; run with adb am instrument -w. */
class NativeFilterInstrumentation : Instrumentation() {
    private var suite: String? = null
    override fun onCreate(arguments: Bundle?) { suite = arguments?.getString("suite"); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            if (suite == "login") {
                val report = JSONObject()
                runOnMainSync {
                    (targetContext.applicationContext as com.mybrowser.App).prepareWebEngine()
                    val view = android.webkit.WebView(targetContext)
                    try {
                        com.mybrowser.core.SystemLoginSupport.configure(view, false)
                        check(view.importantForAutofill == android.view.View.IMPORTANT_FOR_AUTOFILL_AUTO)
                        val supported = com.mybrowser.core.SystemLoginSupport.supportsWebAuthn
                        val originGranted = com.mybrowser.core.SystemLoginSupport.hasOriginPermission(targetContext)
                        check(originGranted) { "Browser WebAuthn requires the declared origin permission" }
                        if (supported) check(androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(view.settings) ==
                            androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
                        com.mybrowser.core.SystemLoginSupport.configure(view, true)
                        check(view.importantForAutofill == android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
                        if (supported) check(androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(view.settings) ==
                            androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE)
                        report.put("passed", true).put("webAuthnSupported", supported).put("originPermissionGranted", originGranted)
                            .put("normalAutofill", true).put("privateAutofillDisabled", true).put("privateWebAuthnDisabled", supported)
                            .put("webView", android.webkit.WebView.getCurrentWebViewPackage()?.versionName)
                    } finally { view.destroy() }
                }
                result.putString("report", report.toString()); finish(-1, result); return
            }
            val rows = JSONArray()
            checkNotNull(NativeFilter.createOrNull()).use { engine ->
                val started = System.nanoTime()
                for (file in listOf("easylist.txt", "easyprivacy.txt", "easylist-china.txt")) {
                    engine.addList(targetContext.assets.open("filters/$file").bufferedReader().use { it.readText() })
                }
                val loadMs = (System.nanoTime() - started) / 1e6
                val cases = listOf(
                    Triple("https://www.example.org/", "https://www.example.org/", ResourceType.DOCUMENT),
                    Triple("https://cdn.example.org/assets/main.js", "https://www.example.org/", ResourceType.SCRIPT),
                    Triple("https://cdn.example.org/images/photo.webp", "https://www.example.org/", ResourceType.IMAGE),
                    Triple("https://fonts.gstatic.com/s/roboto/font.woff2", "https://www.example.org/", ResourceType.FONT),
                    Triple("https://m.youtube.com/youtubei/v1/player", "https://m.youtube.com/watch?v=aqz-KE-bpKQ", ResourceType.XML_HTTP_REQUEST),
                    Triple("https://rr1.googlevideo.com/videoplayback?id=123&mime=video/mp4", "https://m.youtube.com/", ResourceType.MEDIA),
                    Triple("https://media.example.org/live/vl.m3u8?token=ABC123", "https://m.jrs16.com/wlty.html", ResourceType.MEDIA),
                    Triple("https://media.example.org/live/segment-001.ts", "https://m.jrs16.com/wlty.html", ResourceType.MEDIA),
                    Triple("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://www.example.org/", ResourceType.SCRIPT),
                    Triple("https://www.google-analytics.com/analytics.js", "https://www.example.org/", ResourceType.SCRIPT),
                    Triple("https://cdn.example.org/ads/banner.js", "https://www.example.org/", ResourceType.SCRIPT),
                    Triple("https://cdn.example.org/movie/segment.m4s?token=" + "abcdef0123456789".repeat(128), "https://www.example.org/", ResourceType.MEDIA))
                val expected = cases.map { (url, page, type) -> engine.shouldBlockUncached(url, page, type) }
                // Alternate order across runs to avoid crediting warm caches to one path.
                repeat(6) { run ->
                    for (cached in if (run % 2 == 0) listOf(false, true) else listOf(true, false)) {
                        val samples = LongArray(100 * cases.size)
                        var i = 0
                        repeat(100) {
                            cases.forEachIndexed { index, (url, page, type) ->
                                val time = System.nanoTime()
                                val value = if (cached) engine.shouldBlock(url, page, type) else engine.shouldBlockUncached(url, page, type)
                                samples[i++] = System.nanoTime() - time
                                check(value == expected[index]) { "JNI decision mismatch" }
                            }
                        }
                        samples.sort()
                        if (run > 0) rows.put(JSONObject().put("cached", cached).put("run", run)
                            .put("p50Us", samples[samples.size / 2] / 1000.0).put("p95Us", samples[samples.size * 95 / 100] / 1000.0))
                    }
                }
                val report = JSONObject().put("rules", engine.ruleCount).put("loadMs", loadMs)
                    .put("decisions", JSONArray(expected)).put("samples", rows).put("passed", true)
                File(targetContext.filesDir, "native-benchmark.json").writeText(report.toString(2))
                result.putString("report", report.toString())
            }
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString()); finish(0, result)
        }
    }
}
