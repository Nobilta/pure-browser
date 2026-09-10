package com.mybrowser.filter

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeContextTest {
    @Test fun cachedDocumentsMatchUncachedUnderConcurrentEviction() {
        checkNotNull(NativeFilter.createOrNull()).use { engine ->
            engine.addList("||cdn.example.co.uk^\$third-party\n||cdn.example.com^\$~third-party\n||bob.github.io^\$third-party\n")
            val pool = Executors.newFixedThreadPool(8)
            try {
                pool.invokeAll((0 until 8).map { worker -> Callable {
                    repeat(512) { index ->
                        val page = listOf("https://www.example.co.uk/", "https://www.example.com/", "https://alice.github.io/")[index % 3] + "?doc=$worker-$index"
                        for (url in listOf("https://cdn.example.co.uk/a.js", "https://cdn.example.com/a.js", "https://bob.github.io/a.js")) {
                            assertEquals(engine.shouldBlockUncached(url, page, NativeFilter.ResourceType.SCRIPT),
                                engine.shouldBlock(url, page, NativeFilter.ResourceType.SCRIPT))
                        }
                    }
                } }).forEach { it.get() }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun diagnosticReportsBlockingAndExceptionWithoutCountingCosmeticsAsUnsupportedNetworkRules() {
        checkNotNull(NativeFilter.createOrNull()).use { engine ->
            val text = "||ads.example^\$script\n@@||ads.example/safe.js\$script\nexample.com##.ad\n||ads.example^\$redirect=noopjs"
            engine.addList(text)
            assertEquals(1, engine.unsupportedRuleCount)
            val hit = engine.explainList(text, "https://ads.example/safe.js", "https://example.com", NativeFilter.ResourceType.SCRIPT)
            assertEquals("||ads.example^\$script", hit.first)
            assertEquals("@@||ads.example/safe.js\$script", hit.second)
            assertFalse(engine.shouldBlock("https://ads.example/safe.js", "https://example.com", NativeFilter.ResourceType.SCRIPT))
        }
    }
}
