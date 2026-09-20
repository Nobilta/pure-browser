package com.mybrowser.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WebLinkExtractorTest {
    @Test fun extractsHttpsLinkFromChineseProseWithFullWidthPunctuation() {
        val text = "这段内容值得看看 https://example.com/item?id=123 ，复制后打开"
        assertEquals(listOf("https://example.com/item?id=123"), WebLinkExtractor.extractWebLinks(text))
    }

    @Test fun sentencePunctuationIsTrimmedFromTheTail() {
        val text = "看这个 https://example.com/page. 还有 https://example.com/next!"
        assertEquals(
            listOf("https://example.com/page", "https://example.com/next"),
            WebLinkExtractor.extractWebLinks(text),
        )
    }

    @Test fun balancedAsciiParenthesesAreKeptUnbalancedWrappersAreDropped() {
        val wrapped = "详情见(https://example.com/wiki/Page_(disambiguation))结束"
        assertEquals(listOf("https://example.com/wiki/Page_(disambiguation)"), WebLinkExtractor.extractWebLinks(wrapped))
        val unbalanced = "见 (https://example.com/a) 完"
        assertEquals(listOf("https://example.com/a"), WebLinkExtractor.extractWebLinks(unbalanced))
    }

    @Test fun chineseQuotesAndBracketsTerminateALink() {
        val text = "「https://example.com/quoted】其余文字https://example.com/second《结束》"
        assertEquals(
            listOf("https://example.com/quoted", "https://example.com/second"),
            WebLinkExtractor.extractWebLinks(text),
        )
    }

    @Test fun atMostThreeDistinctLinksInOrder() {
        val text = "https://a.test/1 然后 https://b.test/2 然后 https://a.test/1 还有 https://c.test/3 最后 https://d.test/4"
        assertEquals(
            listOf("https://a.test/1", "https://b.test/2", "https://c.test/3"),
            WebLinkExtractor.extractWebLinks(text),
        )
    }

    @Test fun queryFragmentAndPercentEncodingSurvive() {
        val text = "链接 https://example.com/search?q=%E5%AE%89%E5%8D%93&lang=zh#results 结束"
        assertEquals(
            listOf("https://example.com/search?q=%E5%AE%89%E5%8D%93&lang=zh#results"),
            WebLinkExtractor.extractWebLinks(text),
        )
    }

    @Test fun schemeCaseIsInsensitiveAndPreserved() {
        val text = "看看 HTTPS://Example.COM/A 以及 http://example.org/b"
        val links = WebLinkExtractor.extractWebLinks(text)
        assertEquals(2, links.size)
        assertTrue(links[0].startsWith("HTTPS://Example.COM/A"))
        assertEquals("http://example.org/b", links[1])
    }

    @Test fun dangerousAndOpaqueSchemesAreNeverExtracted() {
        val text = "点击 javascript:alert(1) 或 data:text/html,hi 或 file:///sdcard/x 或 myapp://open 没有 http 链接"
        assertEquals(emptyList<String>(), WebLinkExtractor.extractWebLinks(text))
    }

    @Test fun bareDomainsInsideProseAreNotLinks() {
        val text = "你可以在 example.com 或者 www.example.com 找到"
        assertEquals(emptyList<String>(), WebLinkExtractor.extractWebLinks(text))
    }

    @Test fun oversizeInputScansBoundedAndDoesNotThrow() {
        val filler = "很".repeat(64 * 1024 + 100)
        val text = filler + " https://example.com/tail"
        // The marker sits beyond the scan limit, so nothing is extracted — without crashing.
        assertEquals(emptyList<String>(), WebLinkExtractor.extractWebLinks(text))
        val earlyText = "https://example.com/head " + filler
        assertEquals(listOf("https://example.com/head"), WebLinkExtractor.extractWebLinks(earlyText))
    }

    @Test fun invalidHostsAreRejectedByTheSharedValidator() {
        val text = "伪链接 https:///nohost 和 https:// 也都无效"
        assertEquals(emptyList<String>(), WebLinkExtractor.extractWebLinks(text))
    }

    @Test fun turkishCapitalIDoesNotShiftCaseInsensitiveMatching() {
        // U+0130 lowercases to two chars under a locale-aware toLowerCase, which used to
        // shift offsets and garble candidates that followed it.
        val text = "İstanbul'da HTTP://EXAMPLE.COM/TR/İST gezisi"
        val links = WebLinkExtractor.extractWebLinks(text)
        assertEquals(1, links.size)
        assertTrue(links[0].startsWith("HTTP://EXAMPLE.COM/TR/"))
    }

    @Test fun balancedBracketsInIpv6HostAndQuerySurvive() {
        assertEquals(
            listOf("https://[::1]:8080/a"),
            WebLinkExtractor.extractWebLinks("本机 https://[::1]:8080/a 服务"),
        )
        assertEquals(
            listOf("https://example.com/search?filter[x]=1&sort[y]=asc"),
            WebLinkExtractor.extractWebLinks("查询 https://example.com/search?filter[x]=1&sort[y]=asc 即可"),
        )
    }

    @Test fun candidateStraddlingTheScanLimitIsRejectedNotTruncated() {
        // A marker just inside the limit whose host runs past it must be dropped whole,
        // never returned as a truncated URL.
        val pad = "a".repeat(WebLinkExtractor.MAX_SCAN_CHARS - 12)
        val links = WebLinkExtractor.extractWebLinks(pad + " https://example.com/long/path/that/overflows")
        assertTrue(links.none { it != "https://example.com/long/path/that/overflows" })
    }

    @Test fun longRunOfClosingBracketsTrimsToTheBareUrl() {
        // The tail trim must stay linear: a wall of unmatched closers is adversarial
        // paste content, not a reason to scan the candidate once per character.
        val text = "看这个 https://example.com/a" + ")".repeat(8000)
        assertEquals(listOf("https://example.com/a"), WebLinkExtractor.extractWebLinks(text))
    }
    @Test fun rejectedNestedSchemesAreScannedOnlyOnce() {
        for (raw in listOf(
            "https:///" + "https://".repeat(7000),
            "https:///" + "https://".repeat(9000), // truncated at the scan limit
        )) {
            var reads = 0
            val counted = object : CharSequence {
                override val length = raw.length
                override fun get(index: Int): Char {
                    check(index < WebLinkExtractor.MAX_SCAN_CHARS)
                    reads++
                    return raw[index]
                }
                override fun subSequence(startIndex: Int, endIndex: Int) = raw.subSequence(startIndex, endIndex)
            }
            assertEquals(emptyList<String>(), WebLinkExtractor.extractWebLinks(counted))
            assertTrue("character reads must be linear, got $reads", reads <= minOf(raw.length, WebLinkExtractor.MAX_SCAN_CHARS) * 3)
        }
    }

    @Test fun invalidCandidateDoesNotHideTheNextSeparateLink() {
        assertEquals(listOf("https://good.test/path"), WebLinkExtractor.extractWebLinks(
            "https:///broken/https://nested.test 然后 https://good.test/path",
        ))
    }

}
