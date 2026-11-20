package com.mybrowser.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRuleGroupingTest {
    @Test fun addressAuthorityDoesNotConfuseCredentialsOrSchemesWithHosts() {
        for (rule in listOf("https://user:pass@example.com/ad.js", "||http://example.com/ad.js")) {
            assertEquals("example.com", UserRuleGrouping.scopeOf(rule).site)
        }
        for (rule in listOf("##", "example.com##", "example.com##a#@#b")) {
            assertEquals(UserRuleGroupKind.UNKNOWN, UserRuleGrouping.scopeOf(rule).kind)
        }
    }

    @Test fun groupsBySiteAndKeepsTheGenericAndUnknownBucketsAtTheEdges() {
        val groups = UserRuleGrouping.group(listOf(
            "b.example##.ad",
            "||a.example^",
            "https://b.example/path",
            "##.promo",
            "not a rule",
            "a.example##.banner",
        ))
        assertEquals(
            listOf(
                UserRuleGroupKind.ALL_SITES,
                UserRuleGroupKind.SITE,
                UserRuleGroupKind.SITE,
                UserRuleGroupKind.UNKNOWN,
            ),
            groups.map { it.kind },
        )
        assertEquals(listOf("##.promo"), groups[0].rules)
        assertEquals("a.example", groups[1].site)
        assertEquals("b.example", groups[2].site)
        assertEquals(listOf("b.example##.ad", "https://b.example/path"), groups[2].rules)
        assertEquals(UserRuleGroupKind.UNKNOWN, groups[3].kind)
        assertNull(groups[3].site)
    }

    @Test fun recognisesHostAddressAndCosmeticScopes() {
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.SITE, "cdn.example"),
            UserRuleGrouping.scopeOf("||cdn.example^\$document"))
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.SITE, "news.example"),
            UserRuleGrouping.scopeOf("https://news.example/a?b=1"))
        for (rule in listOf("@@||example.com^", "@@https://example.com/a.js", "|https://example.com/a.js")) {
            assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.SITE, "example.com"), UserRuleGrouping.scopeOf(rule))
        }
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.UNKNOWN, null),
            UserRuleGrouping.scopeOf("~example.com##.ad"))
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.SITE, "example.com"),
            UserRuleGrouping.scopeOf("example.com#@#.ad"))
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.ALL_SITES, null),
            UserRuleGrouping.scopeOf("##.ad"))
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.UNKNOWN, null),
            UserRuleGrouping.scopeOf("example.com"))
        assertEquals(UserRuleGrouping.Scope(UserRuleGroupKind.UNKNOWN, null),
            UserRuleGrouping.scopeOf("https://"))
    }

    @Test fun sameSiteGroupsAreMergedCaseInsensitively() {
        val groups = UserRuleGrouping.group(listOf("Example.com##.a", "example.com##.b"))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().rules.size)
        assertEquals("example.com", groups.single().site)
    }
    @Test fun invalidCosmeticScopesStayUnknownAndAreNotAcceptedAsUserRules() {
        for (rule in listOf("@@##.ad", "@@example.com##.ad", "example.com ##.ad", "a.com,b.com##.ad")) {
            assertEquals(rule, UserRuleGroupKind.UNKNOWN, UserRuleGrouping.scopeOf(rule).kind)
            assertFalse(rule, BlockRules.isValid(rule))
        }
    }

    @Test fun networkPatternSyntaxDoesNotHideAConcreteHost() {
        for ((rule, host) in listOf(
            "https://example.com/ad^" to "example.com",
            "https://example.com/ad*" to "example.com",
            "|HTTPS://Example.com/ad|" to "example.com",
            "https://sub_1.example.com/a.js" to "sub_1.example.com",
            "@@||localhost^" to "localhost",
        )) {
            assertTrue(rule, BlockRules.isValid(rule))
            assertEquals(rule, UserRuleGrouping.Scope(UserRuleGroupKind.SITE, host), UserRuleGrouping.scopeOf(rule))
        }
        assertEquals(UserRuleGroupKind.UNKNOWN, UserRuleGrouping.scopeOf("https://*.example.com/ad").kind)
    }

}
