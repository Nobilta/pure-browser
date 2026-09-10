package com.mybrowser.download

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyBudgetTest {
    @Test fun differentHostsProgressAndCancelledWaitersDoNotLeakPermits() = runTest {
        val budget = ConcurrencyBudget(3, 2)
        val first = budget.acquire("same.test")
        val second = budget.acquire("same.test")
        val waiting = async { budget.acquire("same.test") }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val other = budget.acquire("other.test")
        waiting.cancelAndJoin()
        first.close(); first.close(); second.close(); other.close()
        val leases = listOf(budget.acquire("a"), budget.acquire("a"), budget.acquire("b"))
        val final = async { budget.acquire("c") }
        runCurrent(); assertFalse(final.isCompleted)
        leases[0].close()
        final.await().close()
        leases.drop(1).forEach { it.close() }
    }
    @Test fun cancellationAfterAssignmentReturnsItsPermit() = runTest {
        val budget = ConcurrencyBudget(1, 1)
        val first = budget.acquire("a")
        val waiter = async { budget.acquire("a") }
        runCurrent()
        first.close()
        waiter.cancelAndJoin()
        withTimeout(1000) { budget.acquire("a").close() }
    }
}
