package com.mybrowser.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue policy that keeps an undo on screen; pure Kotlin, no Android or Compose. */
class FeedbackQueueTest {

    @Test fun keepsEveryDistinctBurstMessageInOrder() {
        val queue = FeedbackQueue()
        assertTrue(queue.add(FeedbackMessage("Imported 3 groups"), null, null))
        assertTrue(queue.add(FeedbackMessage("Added 4 bookmarks"), "Imported 3 groups", null))

        assertEquals(listOf("Imported 3 groups", "Added 4 bookmarks"), drain(queue))
    }

    @Test fun ignoresADuplicateOfTheVisibleMessage() {
        val queue = FeedbackQueue()

        assertFalse(queue.add(FeedbackMessage("Download started"), "Download started", null))
        assertTrue(queue.isEmpty())
    }

    @Test fun ignoresADuplicateAlreadyWaiting() {
        val queue = FeedbackQueue()
        queue.add(FeedbackMessage("Download started"), "Something else", null)

        assertFalse(queue.add(FeedbackMessage("Download started"), "Something else", null))
        assertEquals(1, queue.size)
    }

    @Test fun ordinaryMessageWaitsBehindUndoInsteadOfReplacingIt() {
        val queue = FeedbackQueue()
        queue.add(FeedbackMessage("Bookmark removed", "Undo"), "Previous", null)
        queue.add(FeedbackMessage("Download started"), "Bookmark removed", "Undo")

        assertEquals(listOf("Bookmark removed", "Download started"), drain(queue))
    }

    @Test fun onlyTheNewestOrdinaryMessageWaitsBehindAnUndo() {
        val queue = FeedbackQueue()
        queue.add(FeedbackMessage("Download started"), "Bookmark removed", "Undo")
        queue.add(FeedbackMessage("Download finished"), "Bookmark removed", "Undo")

        assertEquals(listOf("Download finished"), drain(queue))
    }

    @Test fun neverDropsAnActionToMakeRoom() {
        val queue = FeedbackQueue(maxOrdinary = 2)
        queue.add(FeedbackMessage("Bookmark removed", "Undo"), null, null)
        repeat(5) { queue.add(FeedbackMessage("Status $it"), null, null) }

        val messages = drain(queue)
        assertEquals("Bookmark removed", messages.first())
        assertEquals(3, messages.size)
        assertEquals("Status 4", messages.last())
    }

    @Test fun importantResultsAreNotMergedAwayBehindAnUndo() {
        val queue = FeedbackQueue()
        queue.add(FeedbackMessage("Bookmark removed", "Undo"), null, null)
        queue.add(FeedbackMessage("Exported 3", important = true), "Bookmark removed", "Undo")
        queue.add(FeedbackMessage("Skipped 2 non-web", important = true), "Bookmark removed", "Undo")
        queue.add(FeedbackMessage("2 omitted", important = true), "Bookmark removed", "Undo")

        assertEquals(
            listOf("Bookmark removed", "Exported 3", "Skipped 2 non-web", "2 omitted"),
            drain(queue),
        )
    }

    @Test fun drainsToEmptyAndReportsSize() {
        val queue = FeedbackQueue()
        assertTrue(queue.isEmpty())
        assertNull(queue.removeFirst())
        queue.add(FeedbackMessage("One"), null, null)
        assertEquals(1, queue.size)
        assertFalse(queue.isEmpty())
        assertEquals("One", queue.removeFirst()?.message)
        assertNull(queue.removeFirst())
    }

    @Test fun interruptedImportantResultReturnsAfterAllPendingActions() {
        val queue = FeedbackQueue()
        queue.add(FeedbackMessage("First undo", "Undo"), null, null)
        queue.add(FeedbackMessage("Second undo", "Undo"), null, null)
        queue.add(FeedbackMessage("Later result", important = true), null, null)
        val interrupted = FeedbackMessage("Interrupted result", important = true)
        queue.restoreInterrupted(interrupted)
        queue.restoreInterrupted(interrupted)

        assertEquals(
            listOf("First undo", "Second undo", "Interrupted result", "Later result"),
            drain(queue),
        )
    }

    @Test fun ordinaryInterruptedStatusIsNotReplayed() {
        val queue = FeedbackQueue()
        queue.restoreInterrupted(FeedbackMessage("Old status"))
        assertTrue(queue.isEmpty())
    }

    private fun drain(queue: FeedbackQueue): List<String> {
        val messages = mutableListOf<String>()
        while (true) {
            val next = queue.removeFirst() ?: break
            messages += next.message
        }
        return messages
    }
}
