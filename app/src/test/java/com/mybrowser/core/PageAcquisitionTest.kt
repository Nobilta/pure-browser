package com.mybrowser.core

import org.junit.Assert.*
import org.junit.Test

class PageAcquisitionTest {
    @Test fun allocationFailureEvictsBeforeRetrying() {
        var attempts = 0
        var evictions = 0
        val page = Any()
        val result = acquireWithMemoryRecovery(
            create = { if (attempts++ == 0) throw OutOfMemoryError("allocation") else page },
            configure = {}, discard = { fail("No instance was allocated") }, evict = { evictions++; true },
        )
        assertSame(page, result)
        assertEquals(1, evictions)
        assertEquals(2, attempts)
    }

    @Test fun failedConfigurationIsDiscardedBeforeEvictionAndRetry() {
        val events = mutableListOf<String>()
        var attempts = 0
        acquireWithMemoryRecovery(create = { ++attempts }, configure = {
            if (it == 1) throw OutOfMemoryError("configuration")
        }, discard = { events += "discard $it" }, evict = { events += "evict"; true })
        assertEquals(listOf("discard 1", "evict"), events)
        assertEquals(2, attempts)
    }

    @Test fun exhaustedRecoveryRethrowsTheAllocationFailure() {
        val failure = OutOfMemoryError("allocation")
        try {
            acquireWithMemoryRecovery<Any>(create = { throw failure }, configure = {}, discard = {}, evict = { false })
            fail()
        } catch (error: OutOfMemoryError) { assertSame(failure, error) }
    }

    @Test fun nonMemoryConfigurationFailureAlsoReleasesTheInstance() {
        val page = Any()
        var discarded: Any? = null
        val failure = IllegalStateException("profile")
        try {
            acquireWithMemoryRecovery(create = { page }, configure = { throw failure },
                discard = { discarded = it }, evict = { fail("Not recoverable by eviction"); false })
            fail()
        } catch (error: IllegalStateException) { assertSame(failure, error) }
        assertSame(page, discarded)
    }
}
