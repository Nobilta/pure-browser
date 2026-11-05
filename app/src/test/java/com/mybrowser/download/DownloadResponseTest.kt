package com.mybrowser.download

import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadResponseTest {
    @Test fun waitingExpiresButStartingCancelsExpiryAndCloseIsIdempotent() = runTest {
        var closes = 0
        fun response() = DownloadResponse(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closes++; super.close() }
        }, 1)
        val expired = response()
        expired.hold(this, 100)
        advanceTimeBy(100); runCurrent()
        assertEquals(DownloadResponse.Unavailable.EXPIRED, expired.unavailable.value)
        assertFalse(expired.start())
        expired.close()
        assertEquals(1, closes)

        val active = response()
        active.hold(this, 100)
        assertTrue(active.start())
        advanceTimeBy(1000); runCurrent()
        assertNull(active.unavailable.value)
        assertEquals(1, active.body.read())
        active.close(); active.close()
        assertEquals(2, closes)
    }
}
