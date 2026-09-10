package com.mybrowser.core

import android.app.Application
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BufferedLogTest {
    @Test fun retainsPreOpenHistoryAndPublishesInBatches() {
        val log = BufferedLog<Int>(3)
        repeat(1000) { log.edit { ring -> ring.add(it) } }
        assertTrue(log.entries.value.isEmpty())
        assertEquals(listOf(997, 998, 999), log.snapshot())
        log.setVisible(true)
        assertEquals(log.snapshot(), log.entries.value)
        repeat(10) { log.edit { ring -> ring.add(1000 + it) } }
        assertEquals(listOf(997, 998, 999), log.entries.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(listOf(1007, 1008, 1009), log.entries.value)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        log.setVisible(false)
    }
}
