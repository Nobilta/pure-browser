package com.mybrowser.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FullscreenPreferenceStateTest {
    @Test fun exitRemainsEffectiveAfterFailedWriteAndPreferenceReload() = runBlocking {
        val state = FullscreenPreferenceState()
        val token = state.request(false)
        assertFalse(state.effective(true))
        assertEquals(false, state.persist(token, false) { false })
        repeat(3) { assertFalse(state.effective(true)) }
        val enable = state.request(true)
        assertEquals(false, state.persist(enable, true) { throw IllegalStateException("disk") })
        assertFalse(state.effective(true))
        assertEquals(true, state.persist(state.request(true), true) { true })
        assertTrue(state.effective(true))
    }

    @Test fun lateEnableCannotUndoAnImmediateExit() = runBlocking {
        val state = FullscreenPreferenceState()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = state.request(true)
        val pending = async(Dispatchers.Default) {
            state.persist(first, true) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); true }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val exit = state.request(false)
            assertFalse(state.effective(true))
            release.countDown()
            assertNull(pending.await())
            assertEquals(false, state.persist(exit, false) { false })
            assertFalse(state.effective(true))
            state.acceptImport()
            assertTrue(state.effective(true))
        } finally { release.countDown() }
    }
}
