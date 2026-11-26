package com.mybrowser.validation

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import com.mybrowser.core.ResourceType
import com.mybrowser.data.BookmarkManager
import com.mybrowser.dlna.DeviceDescription
import com.mybrowser.dlna.SsdpDiscovery
import com.mybrowser.download.DownloadBackend
import com.mybrowser.download.DownloadMetadata
import com.mybrowser.download.DownloadMetadataStore
import com.mybrowser.download.DownloadStatus
import com.mybrowser.filter.NativeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android SQLite/XML/JSON, shipped JNI and real WebView checks without another runner dependency. */
internal class PlatformContractsInstrumentation(private val runner: Instrumentation) {
    private val targetContext get() = runner.targetContext
    private fun runOnMainSync(action: () -> Unit) = runner.runOnMainSync { action() }

    fun run() {
        val result = Bundle()
        val checks = JSONArray()
        try {
            bookmarks()
            checks.put("Android SQLite accepts bookmark pagination with offset")
            metadata()
            checks.put("Active download survives both persisted snapshot limits")
            val device = DeviceDescription.parse("""<root><URLBase>http://192.168.1.10:1400/</URLBase>
                <device><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>control/av</controlURL></service><service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>control/rc</controlURL></service></serviceList></device></root>""",
                SsdpDiscovery.Reply("http://192.168.1.10:1400/xml/device.xml", "uuid:test", null, "ssdp:all"))
            check(device?.controlUrl == "http://192.168.1.10:1400/control/av")
            check(device.renderingControlUrl == "http://192.168.1.10:1400/control/rc")
            checks.put("Android XML parser resolves AVTransport and volume URLs against URLBase")
            val exception = "@@||page.test^\$document"
            val rules = "||ads.test^\n$exception"
            checkNotNull(NativeFilter.createOrNull()).use {
                it.addList(rules)
                check(!it.shouldBlock("https://ads.test/ad.js", "https://page.test/", ResourceType.SCRIPT))
            }
            check(NativeFilter.explainList(rules, "https://ads.test/ad.js", "https://page.test/", ResourceType.SCRIPT).second == exception)
            checks.put("Shipped JNI reports the document exception that permits a request")
            userscriptHelpers()
            checks.put("Real WebView page replacements of JS helpers cannot observe GM values or credentials")
            result.putString("checks", checks.toString())
            runner.finish(-1, result)
        } catch (error: Throwable) {
            result.putString("checks", checks.toString())
            result.putString("failure", error.stackTraceToString())
            runner.finish(0, result)
        }
    }

    private fun bookmarks() {
        val manager = BookmarkManager(targetContext)
        val ids = (0..2).map { manager.addBookmark("Platform QA $it", "https://platform-contract.invalid/$it") }.toSet()
        try {
            check(ids.size == 3 && ids.all { it > 0 })
            check(manager.getAllBookmarks(limit = 1, offset = 1).size == 1)
            check(manager.searchBookmarks("Platform QA", limit = 1, offset = 1).size == 1)
        } finally { manager.deleteBookmarks(ids); manager.close() }
    }

    private fun metadata() {
        val name = "platform-contract-downloads"
        val prefs = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = DownloadMetadataStore(prefs)
            val active = DownloadMetadata(-1, DownloadBackend.LOCAL, "https://example.test/1", "agent", null,
                null, "application/octet-stream", "1.bin", null, 1, DownloadStatus.DOWNLOADING, bytesDownloaded = 123)
            for (large in listOf(false, true)) {
                store.save((2L..300L).map { active.copy(id = -it, timestamp = it, status = DownloadStatus.COMPLETED,
                    url = "https://example.test/" + if (large) "x".repeat(8000) else it.toString()) } + active)
                val restored = store.load()
                check(-1L in restored.interruptedIds)
                check(restored.entries.single { it.id == -1L }.bytesDownloaded == 123L)
            }
        } finally { prefs.edit().clear().commit(); targetContext.deleteSharedPreferences(name) }
    }

    private fun userscriptHelpers() {
        val source = targetContext.assets.open("userscript-runtime.js").bufferedReader().use { it.readText() }
        val config = JSONObject().put("id", "platform-qa").put("name", "Platform QA").put("namespace", "qa")
            .put("version", "1").put("description", "").put("matches", JSONArray(listOf("http://127.0.0.1/*")))
            .put("includes", JSONArray()).put("excludes", JSONArray()).put("excludeMatches", JSONArray())
            .put("runAt", "document-start").put("noframes", false)
            .put("grants", JSONArray(listOf("GM_getValue", "GM_setValue", "GM_listValues")))
            .put("needsStorage", true).put("bridge", "testBridge").put("token", "private-token")
            .put("values", """{"secret":"private-value","config":{"items":[1,2]}}""").put("meta", "")
        val script = """
            window.testBridge={postMessage(){},addEventListener(){}};
            $source($config,function(api){window.testApi=api;});
            var seen=[];
            for(const [owner,name] of [[Object,'defineProperty'],[Object,'keys'],[Array,'isArray'],
                [Array.prototype,'push'],[Promise,'resolve']]) {
                const original=owner[name];
                owner[name]=function(...args){seen[seen.length]=args[0];return Reflect.apply(original,this,args);};
            }
            var value=testApi.GM_getValue('config'),keys=testApi.GM_listValues();
            testApi.GM_setValue('visits',1);
            testApi.GM.getValue('secret').then(secret=>{
                window.contractResult=seen.length===0&&secret==='private-value'&&value.items[1]===2&&keys.length===2?'PASS':'FAIL';
            });
        """.trimIndent()
        lateinit var view: WebView
        runOnMainSync {
            view = WebView(targetContext)
            view.settings.javaScriptEnabled = true
            view.loadDataWithBaseURL("http://127.0.0.1/platform-contract", "<script>$script</script>", "text/html", "utf-8", null)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var answer: String? = null
            while (answer != "\"PASS\"" && System.nanoTime() < deadline) {
                val done = CountDownLatch(1)
                runOnMainSync { view.evaluateJavascript("window.contractResult") { answer = it; done.countDown() } }
                check(done.await(5, TimeUnit.SECONDS))
                check(answer != "\"FAIL\"") { "Page helper observed private GM state" }
                if (answer != "\"PASS\"") Thread.sleep(100)
            }
            check(answer == "\"PASS\"") { "WebView probe did not finish: $answer" }
        } finally { runOnMainSync { view.destroy() } }
    }
}
