package com.mybrowser.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsBodyTest {
    @Test fun anEvictedBodyCannotBecomeCollectedAgain() {
        val evicted = BodyPreview(state = BodyState.EVICTED, bytes = 0, chars = 0,
            reason = BodyReason.EVICTED)
        val result = evicted.afterBodyEnd(BodyState.COLLECTED, 131072, 131072, BodyReason.NONE)
        assertEquals(BodyState.EVICTED, result.state)
        assertEquals(BodyReason.EVICTED, result.reason)
        assertEquals(0, result.chars)
        assertTrue(result.text.isEmpty())
    }

    @Test fun aNormalBodyEndKeepsCollectedText() {
        val body = BodyPreview(state = BodyState.COLLECTING, text = "hello")
        val result = body.afterBodyEnd(BodyState.COLLECTED, 5, 5, BodyReason.NONE)
        assertEquals(BodyState.COLLECTED, result.state)
        assertEquals("hello", result.text)
        assertEquals(5, result.chars)
    }
}
