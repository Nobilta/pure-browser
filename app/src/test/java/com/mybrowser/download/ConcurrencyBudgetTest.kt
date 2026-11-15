package com.mybrowser.download

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyBudgetTest {
    @Test fun changedLimitsAffectQueuedTasksWithoutInterruptingExistingLeases() = runTest {
        val budget = ConcurrencyBudget(2, 2)
        val first = budget.acquire("same.test")
        val second = budget.acquire("same.test")
        budget.updateLimits(1, 1)
        val waiting = async { budget.acquire("other.test") }
        runCurrent()
        first.close()
        runCurrent()
        assertFalse(waiting.isCompleted)
        second.close()
        val third = waiting.await()
        val next = async { budget.acquire("other.test") }
        runCurrent()
        assertFalse(next.isCompleted)
        budget.updateLimits(3, 2)
        next.await().close()
        third.close()
    }
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
