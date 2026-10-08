package com.mybrowser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inspect questions a page has not answered yet.
 *
 * A page can fire two blob downloads before either answer arrives. One slot meant the second
 * question replaced the first, the first answer matched nothing and was dropped, and the user saw
 * only the later download while the earlier file was silently not saved.
 */
class PendingQuestionsTest {

    @Test fun twoQuestionsAskedBeforeEitherAnswerBothSurvive() {
        val open = PendingQuestions()
        open.add(PageFileQuestion("blob:a", "https://example.com/p", null, 1))
        open.add(PageFileQuestion("blob:b", "https://example.com/p", "image/png", 2))
        assertEquals("blob:a", open.resolve(1)?.url)
        assertEquals("blob:b", open.resolve(2)?.url)
    }

    @Test fun anAnswerOnlyResolvesTheQuestionItsProbeNames() {
        val open = PendingQuestions()
        open.add(PageFileQuestion("blob:a", "https://example.com/p", null, 7))
        assertNull("an id nothing asked about resolves nothing", open.resolve(8))
        // And asking about it twice must not hand the same question out again.
        assertEquals("blob:a", open.resolve(7)?.url)
        assertNull(open.resolve(7))
    }

    @Test fun theOpenSetIsBoundedAndForgettingOneLeavesTheRest() {
        val open = PendingQuestions()
        val last = PendingQuestions.MAX + 4
        repeat(PendingQuestions.MAX + 5) { open.add(PageFileQuestion("blob:$it", "https://example.com/p", null, it.toLong())) }
        assertNull("the oldest questions are dropped first", open.resolve(0))
        assertEquals("blob:$last", open.resolve(last.toLong())?.url)
        open.add(PageFileQuestion("blob:keep", "https://example.com/p", null, 99))
        assertTrue(open.owns("blob:keep"))
        open.forget(99)
        assertFalse(open.owns("blob:keep"))
    }
}
