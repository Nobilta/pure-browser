package com.mybrowser.filter

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules this app writes, tested as text.
 *
 * The engine silently drops a rule it cannot parse, so a wrong rule is not a visible failure — it
 * is an ad that keeps appearing while the settings claim it is blocked. These cases pin the text
 * down, including the ones that must be refused rather than written.
 *
 * Robolectric and the same setup as UrlUtilsTest, for the same reasons: reading a host goes through
 * android.net.Uri, which is a stub on the unit-test classpath, and the real App class would have
 * Robolectric's WebView shadow throw during setup for tests that need no application state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlockRulesTest {

    @Test
    fun anAddressRuleDropsTheQueryAndKeepsThePath() {
        assertEquals(
            "https://cdn.example.com/a/ads/300x250.png",
            BlockRules.addressRule("https://cdn.example.com/a/ads/300x250.png?token=abc123&t=99#frag"),
        )
    }

    @Test
    fun anAddressRuleNeedsSomethingToBlockPrecisely() {
        // No path: this is the whole host, which the caller offers as its own choice instead.
        assertNull(BlockRules.addressRule("https://ads.example.com"))
        assertNull(BlockRules.addressRule("https://ads.example.com/"))
        // Not a web address at all.
        assertNull(BlockRules.addressRule("about:blank"))
        assertNull(BlockRules.addressRule("data:image/png;base64,AAAA"))
        assertNull(BlockRules.addressRule("not a url"))
        assertNull(BlockRules.addressRule(""))
    }

    @Test
    fun anAddressRuleRefusesMetaCharactersItCannotEscape() {
        // These change what a substring pattern means, and this app has no way to quote them.
        assertNull(BlockRules.addressRule("https://ads.example.com/a*b.png"))
        assertNull(BlockRules.addressRule("https://ads.example.com/a^b.png"))
        assertNull(BlockRules.addressRule("https://ads.example.com/a|b.png"))
        // A dollar sign is legal in a URL path, but the engine splits the rule at its last one and
        // reads what follows as options, so a written rule would be dropped as unsupported. See
        // rust/adblock/src/rule.rs: the rfind('$') split precedes option parsing.
        assertNull(BlockRules.addressRule("https://ads.example.com/banner$1.png"))
        assertFalse(BlockRules.isValid("https://ads.example.com/banner$1.png"))
    }

    @Test
    fun aHostRuleAnchorsTheHost() {
        assertEquals("||ads.example.com^", BlockRules.hostRule("https://ads.example.com/a/b.png?x=1"))
        // www is a subdomain of what the rule names, and the || anchor already covers subdomains.
        assertEquals("||ads.example.com^", BlockRules.hostRule("https://www.ads.example.com/a.png"))
        assertNull(BlockRules.hostRule("about:blank"))
        assertNull(BlockRules.hostRule("https://"))
    }

    @Test
    fun anElementRuleIsScopedToTheSiteUnlessItIsNot() {
        assertEquals("example.com##div.ad-slot", BlockRules.elementRule("example.com", "div.ad-slot", allSites = false))
        // The engine strips www itself when it reads a host, so a rule written from the address bar
        // covers the site the user is looking at rather than its www alias only.
        assertEquals(
            "example.com##div.ad-slot",
            BlockRules.elementRule(BlockRules.hostOf("https://www.example.com/page"), "div.ad-slot", allSites = false),
        )
        assertEquals("##div.ad-slot", BlockRules.elementRule("example.com", "div.ad-slot", allSites = true))
        // A site-scoped rule needs a site.
        assertNull(BlockRules.elementRule(null, "div.ad-slot", allSites = false))
        assertNull(BlockRules.elementRule("", "div.ad-slot", allSites = false))
        assertNull(BlockRules.elementRule("localhost", "div.ad-slot", allSites = false))
    }

    @Test
    fun anElementRuleKeepsSelectorsTheEngineCanUse() {
        // Descendant combinators and attribute selectors are ordinary selectors and must survive.
        assertEquals("##div.ad > ins", BlockRules.elementRule(null, "div.ad > ins", allSites = true))
        assertEquals("##[data-ad]", BlockRules.elementRule(null, "[data-ad]", allSites = true))
        assertEquals("##.a:nth-of-type(2)", BlockRules.elementRule(null, ".a:nth-of-type(2)", allSites = true))
        assertNull(BlockRules.elementRule(null, "", allSites = true))
        assertNull(BlockRules.elementRule(null, "   ", allSites = true))
    }

    @Test
    fun aPathSelectorIsAValidRule() {
        // The picker's own fallback for an element with no usable id or class is the exact path
        // through the tree, which always contains a descendant or child combinator. It is the
        // common case, not an edge one: refusing whitespace here refused almost every element a
        // user could point at, and hid it locally anyway, so the block looked applied and was not.
        for (selector in listOf(
            "div.ad > ins",
            "section > div:nth-of-type(2) > ins",
            "div.ad > body",
            "bodywork.ad",
        )) {
            val rule = BlockRules.elementRule("example.com", selector, allSites = false)
            assertEquals("example.com##$selector", rule)
            assertTrue(rule, BlockRules.isValid(rule!!))
            assertEquals("FilterListFormat must count $rule", 1, FilterListFormat.validate(rule))
        }
        val generic = BlockRules.elementRule(null, "section > div:nth-of-type(2) > ins", allSites = true)
        assertEquals("##section > div:nth-of-type(2) > ins", generic)
        assertTrue(generic, BlockRules.isValid(generic!!))
    }

    @Test
    fun aRuleTheListFormatWouldNotCountIsRefused() {
        // A plain pattern may not carry whitespace, but a cosmetic selector may; the two halves of
        // the same check have to differ or one of them is wrong.
        assertFalse(BlockRules.isValid("ads example.com/banner.png"))
        assertTrue(BlockRules.isValid("example.com##div.ad > ins"))
        // The forms the native cosmetic parser drops wholesale (rust/adblock/src/cosmetic.rs).
        for (selector in listOf(
            "div:matches-css(color:red)",
            "div:xpath(//div)",
            "div:remove()",
            "div:style(color:red)",
            "div:-abp-has(.ad)",
            "div@ad",
        )) {
            assertNull(selector, BlockRules.elementRule(null, selector, allSites = true))
        }
    }

    @Test
    fun aSelectorCannotCarryTheRuleMarkerIntoTheRule() {
        // `example.com##a##body` does not reach the engine as the selector "a##body": it splits at
        // the first marker, so it is read as a rule for `a` plus a second, generic rule for the
        // whole document — a rule nobody chose, hiding every page. A picker never produces one, and
        // the check refuses it rather than trusting that.
        assertFalse(BlockRules.isValid("example.com##a##body"))
        assertFalse(BlockRules.isValid("##a#@#b"))
        assertNull(BlockRules.elementRule(null, "a##body", allSites = true))
        assertNull(BlockRules.elementRule("example.com", "a#@#b", allSites = false))
    }

    @Test
    fun anElementRuleRefusesTheRuleKindsThisEngineDoesNotImplement() {
        // A declaration is a different rule kind; the engine drops the whole line rather than
        // hiding the element, so writing it would lie to the user.
        assertNull(BlockRules.elementRule(null, ".ad{display:none}", allSites = true))
        assertNull(BlockRules.elementRule(null, "+js(alert)", allSites = true))
        assertNull(BlockRules.elementRule(null, "div:has-text(ad)", allSites = true))
        // A newline would end the rule and start another one.
        assertNull(BlockRules.elementRule(null, "div\n##.other", allSites = true))
    }

    @Test
    fun anElementRuleRefusesToBlankThePage() {
        // The picker reads a page's own tree, so a page can offer an element that is the document.
        // A rule naming it would leave a blank site behind, which a user reads as the filter having
        // broken the page rather than as something they chose.
        assertNull(BlockRules.elementRule("example.com", "body", allSites = false))
        assertNull(BlockRules.elementRule("example.com", "html", allSites = false))
        assertNull(BlockRules.elementRule(null, "body", allSites = true))
        assertNull(BlockRules.elementRule(null, "body > div.ad", allSites = true))
        assertNull(BlockRules.elementRule(null, "HEAD > meta", allSites = true))
        assertFalse(BlockRules.isValid("example.com##body"))
        assertFalse(BlockRules.isValid("##html"))
        // Only the first word is read, so an element that merely starts with one of those names is
        // still an ordinary element.
        assertEquals("##bodywork.ad", BlockRules.elementRule(null, "bodywork.ad", allSites = true))
        assertEquals("##div.ad > body", BlockRules.elementRule(null, "div.ad > body", allSites = true))
    }

    @Test
    fun aSelectorThatNamesTheWholeDocumentIsRefusedInEveryForm() {
        // The picker's channel is open to the page, so what a page posts is input, not an answer.
        // `*` is the one string that hides everything, and with "all sites" it would do so
        // everywhere — a page that posts it during a pick must not get it written.
        for (selector in listOf("*", " * ", "* > div", "*>div", ":root", ":root div", ":scope")) {
            assertNull("`$selector` must not become a rule", BlockRules.elementRule(null, selector, allSites = true))
            assertNull(BlockRules.elementRule("example.com", selector, allSites = false))
            assertFalse(BlockRules.isUsableElementSelector(selector))
        }
        // A legitimate selector that merely contains one of those characters is untouched.
        assertEquals("##div.ad *", BlockRules.elementRule(null, "div.ad *", allSites = true))
        assertEquals("##.a\\.b", BlockRules.elementRule(null, ".a\\.b", allSites = true))
    }

    @Test
    fun validityMatchesWhatTheListFormatCounts() {
        // Everything this app writes has to be countable by FilterListFormat, or the payload that
        // carries it is rejected as "No filter rules" and the engine keeps the old snapshot.
        for (rule in listOf(
            "||ads.example.com^",
            "https://cdn.example.com/a/ads/300x250.png",
            "##div.ad-slot",
            "example.com##div.ad-slot",
        )) {
            assertTrue(rule, BlockRules.isValid(rule))
            assertEquals("FilterListFormat must count $rule", 1, FilterListFormat.validate(rule))
        }
        for (rule in listOf("", "   ", "div\n##.other", ".ad{display:none}", "##")) {
            assertFalse(rule, BlockRules.isValid(rule))
        }
    }
}
