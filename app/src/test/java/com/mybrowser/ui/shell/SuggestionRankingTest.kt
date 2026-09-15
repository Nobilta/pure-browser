package com.mybrowser.ui.shell

import org.junit.Assert.*
import org.junit.Test

class SuggestionRankingTest {
    @Test fun exactHostWinsOverSavedSubstringAndDedupKeepsVisitHistory() {
        val results = rankSuggestions("example.com", listOf(
            Suggestion("Saved mention", "https://other.test/example.com", SuggestionType.BOOKMARK),
            Suggestion("Example", "https://example.com/", SuggestionType.BOOKMARK),
            Suggestion("Visited", "https://example.com/", SuggestionType.HISTORY, 50, 1000)), 2000)
        assertEquals(2, results.size)
        assertEquals("https://example.com/", results.first().url)
        assertEquals(SuggestionType.BOOKMARK, results.first().type)
        assertEquals(50, results.first().visitCount)
        assertEquals(1000L, results.first().lastVisit)
    }

    @Test fun prefixAndRecencyOrderIsDeterministic() {
        val old = Suggestion("Old", "https://docs.test/a", SuggestionType.HISTORY, 1, 1000)
        val recent = old.copy(title = "Recent", url = "https://docs.test/b", lastVisit = 2000)
        assertEquals(listOf(recent, old), rankSuggestions("docs", listOf(old, recent), 3000))
    }
}
