package com.mybrowser.ui.shell

import org.junit.Assert.*
import org.junit.Test

class SuggestionRankingTest {
    @Test fun exactHostWinsOverSavedSubstringAndDedupKeepsVisitHistory() {
        val results = rankSuggestions("example.com", listOf(
            Suggestion("Saved mention", "https://other.test/example.com", SuggestionType.BOOKMARK, SuggestionAction.Visit("https://other.test/example.com")),
            Suggestion("Example", "https://example.com/", SuggestionType.BOOKMARK, SuggestionAction.Visit("https://example.com/")),
            Suggestion("Visited", "https://example.com/", SuggestionType.HISTORY, SuggestionAction.Visit("https://example.com/"), 50, 1000)), 2000)
        assertEquals(2, results.size)
        assertEquals("https://example.com/", results.first().url)
        assertEquals(SuggestionType.BOOKMARK, results.first().type)
        assertEquals(50, results.first().visitCount)
        assertEquals(1000L, results.first().lastVisit)
    }

    @Test fun prefixAndRecencyOrderIsDeterministic() {
        val old = Suggestion("Old", "https://docs.test/a", SuggestionType.HISTORY, SuggestionAction.Visit("https://docs.test/a"), 1, 1000)
        val recent = old.copy(title = "Recent", url = "https://docs.test/b", lastVisit = 2000)
        assertEquals(listOf(recent, old), rankSuggestions("docs", listOf(old, recent), 3000))
    }
}

class SuggestionAssemblyTest {
    private fun local(url: String, type: SuggestionType = SuggestionType.HISTORY) =
        Suggestion(url, url, type, SuggestionAction.Visit(url))

    @Test fun orderIsLinksSearchOnlineThenLocalWithinEightRows() {
        val rows = assembleSuggestions(
            searchTitle = "Search: 安卓浏览器",
            query = "安卓浏览器",
            links = listOf("https://example.com/a"),
            onlineTerms = listOf("安卓浏览器", "安卓浏览器 推荐", "安卓浏览器 下载", "安卓浏览器 图标", "安卓浏览器 apk", "安卓浏览器 ptt"),
            localRanked = List(6) { local("https://docs.test/$it") },
            linkTitle = { "Visit $it" },
        )
        assertEquals(8, rows.size)
        assertEquals(SuggestionType.LINK, rows[0].type)
        assertEquals(SuggestionType.SEARCH, rows[1].type)
        // The engine echoing the query back is dropped, five online rows remain.
        assertEquals(5, rows.count { it.type == SuggestionType.SEARCH_SUGGESTION })
        // Local fills only the single remaining slot.
        assertEquals(1, rows.count { it.type == SuggestionType.HISTORY })
    }

    @Test fun onlineCapAndRowCapInteract() {
        val rows = assembleSuggestions(
            searchTitle = "Search: q",
            query = "q",
            links = listOf("https://a.test/1", "https://a.test/2", "https://a.test/3"),
            onlineTerms = List(10) { "term$it" },
            localRanked = List(10) { local("https://docs.test/$it") },
            linkTitle = { "Visit $it" },
        )
        assertEquals(8, rows.size)
        assertTrue(rows.count { it.type == SuggestionType.SEARCH_SUGGESTION } <= 5)
        assertEquals(0, rows.count { it.type == SuggestionType.HISTORY })
    }

    @Test fun localRowsShowWithoutAnyOnlineTermsOrLinks() {
        val rows = assembleSuggestions(
            searchTitle = "Search: docs",
            query = "docs",
            links = emptyList(),
            onlineTerms = emptyList(),
            localRanked = List(9) { local("https://docs.test/$it") },
            linkTitle = { "Visit $it" },
        )
        assertEquals(8, rows.size)
        assertEquals(SuggestionType.SEARCH, rows[0].type)
        assertEquals(7, rows.count { it.type == SuggestionType.HISTORY })
    }

    @Test fun duplicateActionsAcrossSourcesCollapseToFirstOccurrence() {
        val bookmark = Suggestion("Example", "https://example.com/", SuggestionType.BOOKMARK, SuggestionAction.Visit("https://example.com/"))
        val rows = assembleSuggestions(
            searchTitle = "Search: example.com",
            query = "example.com",
            links = listOf("https://example.com/"),
            onlineTerms = listOf("example.com"),
            localRanked = listOf(bookmark),
            linkTitle = { "Visit $it" },
        )
        // LINK row, explicit search row, and nothing duplicated.
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.key == "v:https://example.com/" })
        assertEquals(1, rows.count { it.key == "s:example.com" })
    }

    @Test fun searchActionCarriesTheTrimmedQuery() {
        val rows = assembleSuggestions(
            searchTitle = "Search: react.js",
            query = "  react.js  ",
            links = emptyList(),
            onlineTerms = emptyList(),
            localRanked = emptyList(),
            linkTitle = { "Visit $it" },
        )
        val search = rows.single()
        assertEquals(SuggestionAction.Search("react.js"), search.action)
        assertEquals("react.js", search.fillText)
    }
}
