package com.mybrowser.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkStatusTrackerTest {
    @Test fun oldReadCannotOverwriteAConfirmedBookmarkWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val read = CompletableDeferred<Boolean>()
            val changes = mutableListOf<Boolean>()
            val tracker = BookmarkStatusTracker({ withContext(NonCancellable) { read.await() } }, this,
                { "https://example.com" }, { false }, changes::add, dispatcher)
            tracker.refresh("https://example.com")
            runCurrent()
            tracker.setKnown("https://example.com", true)
            read.complete(false)
            advanceUntilIdle()
            assertEquals(listOf(true), changes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newerReadOfTheSameUrlWinsEvenWhenTheOlderQueryFinishesLast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val old = CompletableDeferred<Boolean>()
            var calls = 0
            val changes = mutableListOf<Boolean>()
            val tracker = BookmarkStatusTracker({ if (++calls == 1) withContext(NonCancellable) { old.await() } else true },
                this, { "https://example.com" }, { false }, changes::add, dispatcher)
            tracker.refresh("https://example.com")
            runCurrent()
            tracker.refresh("https://example.com")
            runCurrent()
            old.complete(false)
            advanceUntilIdle()
            assertEquals(listOf(true), changes)
        } finally { Dispatchers.resetMain() }
    }
}
