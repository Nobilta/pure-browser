package com.mybrowser.search

import android.app.Application
import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider behavior against a controlled fake server: endpoint resolution, parsing,
 * size caps, caching and the private/normal cache boundary. Endpoint truth for the
 * real engines is smoke-verified separately; these tests pin the client contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchSuggestionProviderTest {
    @Test fun duckDuckGoListEndpointUsesTheNestedOpenSearchSuggestions() = runBlocking {
        serve("/ddg", body = """["android",["android browser","android studio"]]""")
        val client = SearchSuggestionProvider(openConnection = { endpoint ->
            assertEquals("duckduckgo.com", endpoint.host)
            assertTrue(endpoint.query.contains("type=list"))
            URL("http://127.0.0.1:${server.address.port}/ddg").openConnection() as HttpURLConnection
        })
        val engine = SearchEngine.BUILTIN_ENGINES.single { it.id == "duckduckgo" }
        assertEquals(listOf("android browser", "android studio"), client.fetch(engine, "android", false))
    }
    @Test fun deepJsonFailsClosedForBothFormatsAndTheWorkerCanServeLaterRequests() = runBlocking {
        val deep = "[".repeat(25_000) + "]".repeat(25_000)
        assertTrue(SearchSuggestionProvider.parseOpenSearchJson(deep).isEmpty())
        assertTrue(SearchSuggestionProvider.parseBaiduJsonp("window.baidu.sug({\"s\":$deep});").isEmpty())
        server.createContext("/deep") { exchange ->
            val bytes = deep.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertTrue(withTimeout(2000) { provider.fetch(customEngine("http://127.0.0.1:${server.address.port}/deep?q={query}"), "q", false) }.isEmpty())
        serve("/valid", body = """["q",["valid"]]""")
        assertEquals(listOf("valid"), provider.fetch(customEngine("http://127.0.0.1:${server.address.port}/valid?q={query}"), "q", false))
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private lateinit var provider: SearchSuggestionProvider
    private val requests = AtomicInteger(0)

    @Before fun setUp() {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val connectivityShadow = org.robolectric.Shadows.shadowOf(connectivity)
        connectivityShadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        connectivityShadow.setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        requests.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        provider = SearchSuggestionProvider()
    }

    @After fun tearDown() {
        server.stop(0)
    }

    private fun serve(path: String, status: Int = 200, body: String) {
        server.createContext(path) { exchange ->
            requests.incrementAndGet()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun customEngine(suggest: String) = SearchEngine(
        id = "custom_test", name = "Test", searchUrlTemplate = "https://www.example.com/search?q={query}",
        suggestUrl = suggest, isCustom = true,
    )

    @Test fun parsesOpenSearchJsonAndEncodesTheQuery() = runBlocking {
        var seenQuery: String? = null
        server.createContext("/suggest") { exchange ->
            requests.incrementAndGet()
            seenQuery = exchange.requestURI.rawQuery
            val body = """["安卓 浏览器",["安卓 浏览器 推荐","安卓 浏览器 下载",{"type":"x"}]]"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val engine = customEngine("http://127.0.0.1:${server.address.port}/suggest?q={query}")
        val terms = provider.fetch(engine, "安卓 浏览器", isPrivate = false)
        assertEquals(listOf("安卓 浏览器 推荐", "安卓 浏览器 下载"), terms)
        assertTrue("query must be URL-encoded once: $seenQuery",
            seenQuery == "q=%E5%AE%89%E5%8D%93%20%E6%B5%8F%E8%A7%88%E5%99%A8" || seenQuery == "q=%E5%AE%89%E5%8D%93+%E6%B5%8F%E8%A7%88%E5%99%A8")
    }

    @Test fun parsesTheRestrictedBaiduJsonpWrapper() = runBlocking {
        assertEquals(listOf("测试一", "测试二"), SearchSuggestionProvider.parseBaiduJsonp(
            """window.baidu.sug({"q":"测试","p":false,"s":["测试一","测试二"]});""",
        ))
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseBaiduJsonp("""evil.callback({"s":["x"]})"""))
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseBaiduJsonp("""window.baidu.sug("""))
        // Every built-in engine with a search box entry must map to an endpoint; the
        // retired Azure Autosuggest API is not what this endpoint is.
        assertNotNull(SearchSuggestionProvider.BUILTIN_ENDPOINTS["baidu"])
        assertNotNull(SearchSuggestionProvider.BUILTIN_ENDPOINTS["google"])
        assertNotNull(SearchSuggestionProvider.BUILTIN_ENDPOINTS["duckduckgo"])
        val bing = SearchSuggestionProvider.BUILTIN_ENDPOINTS["bing"]
        assertNotNull(bing)
        assertEquals(SuggestFormat.OPEN_SEARCH_JSON, bing!!.format)
        assertTrue(bing.urlTemplate.startsWith("https://api.bing.com/osjson.aspx"))
        assertEquals(bing, provider.endpointFor(SearchEngine.BING))
    }

    @Test fun bingShapedResponsesParseToPlainTerms() = runBlocking {
        // api.bing.com/osjson.aspx answers with Google's shape and needs no cvid/key.
        serve("/bing", body = """["安卓浏览器",["安卓浏览器","安卓浏览器 推荐","安卓浏览器 下载"]]""")
        val client = SearchSuggestionProvider(openConnection = { endpoint ->
            assertEquals("api.bing.com", endpoint.host)
            assertTrue(endpoint.query.contains("query=%E5%AE%89%E5%8D%93"))
            URL("http://127.0.0.1:${server.address.port}/bing").openConnection() as HttpURLConnection
        })
        assertEquals(
            listOf("安卓浏览器", "安卓浏览器 推荐", "安卓浏览器 下载"),
            client.fetch(SearchEngine.BING, "安卓", false),
        )
    }

    @Test fun googleShapedResponsesParseToPlainTerms() = runBlocking {
        val body = """["安卓浏览器",["安卓浏览器","安卓浏览器 推荐"],[],{"google:suggestsubtypes":[[512]]}]"""
        assertEquals(listOf("安卓浏览器", "安卓浏览器 推荐"), SearchSuggestionProvider.parseOpenSearchJson(body))
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseOpenSearchJson("""["q"]"""))
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseOpenSearchJson("""not json"""))
    }

    @Test fun parserCapsTermCountAndLengthAndDropsGarbage() = runBlocking {
        val many = (1..20).joinToString(",", "[", "]") { "term$it" }
        assertEquals(10, SearchSuggestionProvider.parseOpenSearchJson("""["q",$many]""").size)
        val long = "x".repeat(101)
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseOpenSearchJson("""["q",["$long"]]"""))
        assertEquals(emptyList<String>(), SearchSuggestionProvider.parseOpenSearchJson("""["q",["  "]]"""))
    }

    @Test fun failuresReturnEmptyAndStayCachedPerMode() = runBlocking {
        serve("/suggest", body = """["q",["a","b"]]""")
        val engine = customEngine("http://127.0.0.1:${server.address.port}/suggest?q={query}")
        assertEquals(listOf("a", "b"), provider.fetch(engine, "q", isPrivate = false))
        // Second identical lookup is served from cache: still one server hit.
        assertEquals(listOf("a", "b"), provider.fetch(engine, "q", isPrivate = false))
        assertEquals(1, requests.get())
        // The private cache is separate: a real request happens and is then cached.
        assertEquals(listOf("a", "b"), provider.fetch(engine, "q", isPrivate = true))
        assertEquals(2, requests.get())
        provider.rotatePrivateSession()
        assertEquals(listOf("a", "b"), provider.fetch(engine, "q", isPrivate = true))
        assertEquals(3, requests.get())
        // Normal cache was untouched by the private wipe.
        assertEquals(listOf("a", "b"), provider.fetch(engine, "q", isPrivate = false))
        assertEquals(3, requests.get())
    }

    @Test fun httpErrorAndOversizeBodiesReturnEmpty() = runBlocking {
        serve("/broken", status = 503, body = """["q",["a"]]""")
        val broken = customEngine("http://127.0.0.1:${server.address.port}/broken?q={query}")
        assertEquals(emptyList<String>(), provider.fetch(broken, "q", isPrivate = false))

        server.createContext("/huge") { exchange ->
            requests.incrementAndGet()
            val filler = "a".repeat(1024).toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { output ->
                    repeat(128) { output.write(filler) } // 128 KiB > 64 KiB cap
                }
            }
        }
        val huge = customEngine("http://127.0.0.1:${server.address.port}/huge?q={query}")
        assertEquals(emptyList<String>(), provider.fetch(huge, "q", isPrivate = false))
    }

    @Test fun redirectsAreWalkedUnderTheAppsHopPolicy() = runBlocking {
        // A same-scheme redirect is still followed, so a moved endpoint keeps working.
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/final")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        serve("/final", body = """["q",["followed"]]""")
        val followed = customEngine("http://127.0.0.1:${server.address.port}/moved?q={query}")
        assertEquals(listOf("followed"), provider.fetch(followed, "q", isPrivate = false))

        // A Location that is not http(s) is refused rather than handed to the platform.
        server.createContext("/scheme") { exchange ->
            exchange.responseHeaders.add("Location", "file:///etc/hosts")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        val scheme = customEngine("http://127.0.0.1:${server.address.port}/scheme?q={query}")
        assertEquals(emptyList<String>(), provider.fetch(scheme, "q", isPrivate = false))

        // A loop is cut off at the hop cap instead of running until the deadline.
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        val loop = customEngine("http://127.0.0.1:${server.address.port}/loop?q={query}")
        assertEquals(emptyList<String>(), provider.fetch(loop, "q", isPrivate = false))
    }

    @Test fun engineWithoutEndpointNeverHitsTheNetwork() = runBlocking {
        // Every built-in engine now maps to an endpoint, so this covers an unknown id and
        // a custom engine with no suggest template. Neither may reach the network.
        assertEquals(emptyList<String>(),
            provider.fetch(SearchEngine.BAIDU.copy(id = "no_such_engine"), "q", isPrivate = false))
        assertEquals(emptyList<String>(), provider.fetch(customEngine("   "), "q", isPrivate = false))
        assertEquals(0, requests.get())
    }

    @Test fun suggestTemplateValidationRules() = runBlocking {
        assertTrue(SearchSuggestionProvider.isValidSuggestTemplate(null))
        assertTrue(SearchSuggestionProvider.isValidSuggestTemplate(""))
        assertTrue(SearchSuggestionProvider.isValidSuggestTemplate("  "))
        assertTrue(SearchSuggestionProvider.isValidSuggestTemplate("https://example.com/suggest?q={query}"))
        assertTrue(SearchSuggestionProvider.isValidSuggestTemplate("https://example.com/suggest?q=%s"))
        // HTTP, missing host, missing or duplicated placeholders all fail.
        assertFalse(SearchSuggestionProvider.isValidSuggestTemplate("http://example.com/suggest?q={query}"))
        assertFalse(SearchSuggestionProvider.isValidSuggestTemplate("https:///suggest?q={query}"))
        assertFalse(SearchSuggestionProvider.isValidSuggestTemplate("https://example.com/suggest"))
        assertFalse(SearchSuggestionProvider.isValidSuggestTemplate("https://example.com/s?q={query}&r={query}"))
        assertFalse(SearchSuggestionProvider.isValidSuggestTemplate("https://example.com/s?q=" + "x".repeat(2100)))
    }
    private class HeldConnection(private val holdHeaders: Boolean = true) : HttpURLConnection(URL("https://test.invalid")) {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        private fun awaitRelease() {
            started.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "test did not release the response" }
        }
        override fun getResponseCode(): Int {
            if (holdHeaders) awaitRelease()
            return 200
        }
        override fun getInputStream(): InputStream {
            if (!holdHeaders) awaitRelease()
            return ByteArrayInputStream("""["q",["old response"]]""".toByteArray())
        }
    }

    @Test fun privateRotationRejectsAnOldResponseEvenWhenDisconnectDoesNotStopIt() = runBlocking {
        val old = HeldConnection()
        val hits = AtomicInteger()
        val client = SearchSuggestionProvider(openConnection = {
            if (hits.incrementAndGet() == 1) old else HeldConnection().apply { released.countDown() }
        })
        val engine = customEngine("https://test.invalid/?q={query}")
        val pending = async(Dispatchers.IO) { client.fetch(engine, "q", true) }
        try {
            assertTrue(old.started.await(2, TimeUnit.SECONDS))
            client.rotatePrivateSession()
            assertTrue(old.disconnected.await(2, TimeUnit.SECONDS))
            old.released.countDown()
            assertTrue(withTimeout(2000) { pending.await() }.isEmpty())
            assertEquals(listOf("old response"), client.fetch(engine, "q", true))
            assertEquals(2, hits.get())
            assertEquals(listOf("old response"), client.fetch(engine, "q", true))
            assertEquals(2, hits.get()) // only the new session's answer was cached
        } finally {
            old.released.countDown()
            pending.cancel()
        }
    }

    @Test fun cancellationDisconnectsBeforeHeadersAndDoesNotWaitForTheWorker() = runBlocking {
        val connection = HeldConnection()
        val client = SearchSuggestionProvider(openConnection = { connection })
        val pending = async(Dispatchers.IO) { client.fetch(customEngine("https://test.invalid/?q={query}"), "q", false) }
        try {
            assertTrue(connection.started.await(2, TimeUnit.SECONDS))
            pending.cancel()
            withTimeout(1000) { pending.join() }
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
            assertEquals(1L, connection.released.count) // underlying response is still held
        } finally {
            connection.released.countDown()
        }
    }

    @Test fun totalDeadlineCoversBothHeadersAndBody() = runBlocking {
        for (holdHeaders in listOf(true, false)) {
            val connection = HeldConnection(holdHeaders)
            val client = SearchSuggestionProvider(openConnection = { connection }, deadlineMillis = 150)
            val engine = customEngine("https://test.invalid/?q={query}")
            val pending = async(Dispatchers.IO) { client.fetch(engine, "q", false) }
            try {
                assertTrue(connection.started.await(2, TimeUnit.SECONDS))
                assertTrue(withTimeout(1500) { pending.await() }.isEmpty())
                assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
                assertEquals(1L, connection.released.count)
            } finally {
                connection.released.countDown()
                pending.cancel()
            }
        }
    }

    @Test fun baiduGbkResponseIsDecodedBeforeJsonpParsingAndCaching() = runBlocking {
        server.createContext("/gbk") { exchange ->
            requests.incrementAndGet()
            val bytes = """window.baidu.sug({"q":"安卓","s":["安卓浏览器","安卓应用商店"]});""".toByteArray(charset("GBK"))
            exchange.responseHeaders.add("Content-Type", "text/javascript; charset=\"gbk\"")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val client = SearchSuggestionProvider(openConnection = {
            URL("http://127.0.0.1:${server.address.port}/gbk").openConnection() as HttpURLConnection
        })
        repeat(2) { assertEquals(listOf("安卓浏览器", "安卓应用商店"), client.fetch(SearchEngine.BAIDU, "安卓", false)) }
        assertEquals(1, requests.get())
    }

    @Test fun malformedEncodingIsNotDisplayedOrCached() = runBlocking {
        server.createContext("/encoding") { exchange ->
            val first = requests.incrementAndGet() == 1
            val bytes = if (first) byteArrayOf(0xC3.toByte(), 0x28) else """["q",["正常"]]""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val engine = customEngine("http://127.0.0.1:${server.address.port}/encoding?q={query}")
        assertTrue(provider.fetch(engine, "q", false).isEmpty())
        assertEquals(listOf("正常"), provider.fetch(engine, "q", false))
        assertEquals(2, requests.get())
    }

}
