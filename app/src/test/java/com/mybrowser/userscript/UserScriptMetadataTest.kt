package com.mybrowser.userscript

import org.junit.Assert.*
import org.junit.Test

class UserScriptMetadataTest {
    private fun script(metadata: String) = UserScriptMetadata.parse(
        "// ==UserScript==\n// @name Example\n// @namespace test\n" + metadata + "\n// ==/UserScript==\nconsole.log('ok');")

    @Test fun matchHostBoundariesSchemesPortsQueriesAndExclusions() {
        val value = script("// @match *://*.example.com/path*\n// @exclude *private*\n// @exclude-match https://secret.example.com/*")
        assertTrue(value.matchesUrl("https://example.com/path?x=1#fragment"))
        assertTrue(value.matchesUrl("http://a.example.com:8875/path"))
        assertFalse(value.matchesUrl("https://notexample.com/path"))
        assertFalse(value.matchesUrl("https://example.com.evil.test/path"))
        assertFalse(value.matchesUrl("https://example.com/path/private"))
        assertFalse(value.matchesUrl("https://secret.example.com/path"))
        assertFalse(value.matchesUrl("file://example.com/path"))
    }

    @Test fun includeGlobsHonorExclusionsAndRejectLocalSchemes() {
        val value = script("// @include https://site.test/*\n// @exclude *logout*")
        assertTrue(value.matchesUrl("https://site.test/page"))
        assertFalse(value.matchesUrl("https://site.test/logout"))
        assertFalse(value.matchesUrl("https://other.test/page"))
        assertFalse(script("// @include *").matchesUrl("content://site.test/page"))
    }

    @Test fun allUrlsMeansOnlyWebDocuments() {
        val value = script("// @match <all_urls>")
        assertTrue(value.matchesUrl("https://localhost/"))
        assertFalse(value.matchesUrl("data:text/html,hello"))
        assertFalse(value.matchesUrl("about:blank"))
    }

    @Test fun unsupportedCrossOriginGrantCannotBeEnabledAlongsideResources() {
        val value = script("// @match https://example.com/*\n// @grant GM_xmlhttpRequest\n// @resource icon https://site.test/i.png")
        assertFalse(value.supported)
        assertTrue(value.unsupported.contains("@grant GM_xmlhttpRequest"))
        assertEquals("https://site.test/i.png", value.resources["icon"])
    }

    @Test fun resourceNamesAndGrantsAreBoundedAndValidated() {
        assertTrue(script("// @match *://*/*\n// @resource theme https://site.test/a.css\n// @grant GM_getResourceText\n// @grant GM.getResourceUrl").supported)
        assertFalse(script("// @match *://*/*\n// @resource ../theme https://site.test/a.css").supported)
        assertFalse(script("// @match *://*/*\n// @resource icon file:///etc/private").supported)
        assertFalse(script("// @match *://*/*\n// @resource icon https://site.test/a\n// @resource icon https://site.test/b").supported)
    }

    @Test fun missingMatchesRegexIncludesAndMalformedPatternsAreExplicit() {
        assertFalse(script("// @grant none").supported)
        assertFalse(script("// @include /https:.*/").supported)
        assertFalse(script("// @match https://*example.com/*").supported)
        assertFalse(script("// @match https://example.com").supported)
    }

    @Test fun timingNoframesRequiresAndModernGrantsAreParsed() {
        val value = script("// @match *://*/*\n// @run-at document-start\n// @noframes\n// @grant GM.getValue\n// @grant GM.setValue\n// @require https://cdn.test/lib.js")
        assertTrue(value.supported)
        assertTrue(value.noframes)
        assertTrue(value.needsStorage)
        assertTrue(value.grants("GM_setValue"))
        assertEquals("document-start", value.runAt)
        assertEquals(listOf("https://cdn.test/lib.js"), value.requires)
    }

    @Test fun unsafeRequiresAndInconsistentGrantsAreRefused() {
        assertFalse(script("// @match *://*/*\n// @require file:///tmp/code.js").supported)
        assertFalse(script("// @match *://*/*\n// @grant none\n// @grant GM_getValue").supported)
        assertFalse(script("// @match *://*/*\n// @run-at document-body").supported)
    }

    @Test fun identitySurvivesVersionsButSeparatesNamespaces() {
        val first = script("// @match *://*/*\n// @version 1")
        val second = script("// @match *://*/*\n// @version 2")
        assertEquals(first.id, second.id)
        assertNotEquals(first.id, first.copy(namespace = "another").id)
    }

    @Test fun arbitraryJavascriptAndCodeBeforeHeadersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UserScriptMetadata.parse("alert(1)") }
        assertThrows(IllegalArgumentException::class.java) {
            UserScriptMetadata.parse("alert(1);\n// ==UserScript==\n// @name Unsafe\n// ==/UserScript==")
        }
    }

    @Test fun globsTreatRegexPunctuationLiterally() {
        assertTrue(UserScriptMetadata.glob("*a[1].js*", "https://site/a[1].js?x"))
        assertFalse(UserScriptMetadata.glob("*a[1].js*", "https://site/a1xjs"))
        assertTrue(UserScriptMetadata.glob("a**b*c", "axybzc"))
        assertFalse(UserScriptMetadata.glob("a*b*c", "abx"))
    }
}
