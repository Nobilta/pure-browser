package com.mybrowser.data

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class BookmarkDocumentsStateActivity : ComponentActivity() {
    lateinit var documents: BookmarkDocuments
    lateinit var repository: BookmarkManager
    val messages = mutableListOf<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = BookmarkManager(applicationContext)
        documents = BookmarkDocuments(this, lifecycleScope, { repository }, {}, { _, text -> messages += text })
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class BookmarkDocumentsStateTest {
    @Config(sdk = [34])
    @Test fun invalidExportDoesNotOpenOrTruncateTheReturnedTarget() {
        val controller = Robolectric.buildActivity(BookmarkDocumentsStateActivity::class.java).setup()
        val database = BrowserDatabase.acquire(controller.get())
        val output = java.io.File.createTempFile("bookmarks-unchanged", ".html", controller.get().cacheDir)
        try {
            output.writeText("keep existing contents")
            database.writableDatabase.execSQL("INSERT INTO bookmark_folders(title) VALUES ('')")
            controller.get().documents.exportFile()
            val request = shadowOf(controller.get()).nextStartedActivityForResult
            assertTrue(controller.get().activityResultRegistry.dispatchResult(
                request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (controller.get().messages.isEmpty() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue(controller.get().messages.isNotEmpty())
            assertEquals("keep existing contents", output.readText())
            assertNull(controller.get().documents.exportResult)
        } finally {
            database.writableDatabase.delete("bookmark_folders", "title = ?", arrayOf(""))
            BrowserDatabase.release(database)
            controller.pause().stop().destroy()
            output.delete()
        }
    }

    // Export initializes the URL JNI library; keep its classloader shared with the native tests.
    @Config(sdk = [34])
    @Test fun exportWritesBookmarksAfterRecreationWhileThePickerIsOpen() {
        val controller = Robolectric.buildActivity(BookmarkDocumentsStateActivity::class.java).setup()
        val output = java.io.File.createTempFile("bookmarks-export", ".html", controller.get().cacheDir)
        fun awaitMain(done: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!done() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue("lifecycle=${controller.get().lifecycle.currentState}, busy=${controller.get().documents.busy}, messages=${controller.get().messages}", done())
        }
        try {
            controller.get().repository.addBookmark("Saved bookmark", "https://example.com/bookmark")
            controller.get().documents.exportFile()
            awaitMain { shadowOf(controller.get()).peekNextStartedActivityForResult() != null }
            val request = shadowOf(controller.get()).nextStartedActivityForResult
            controller.recreate()
            val uri = Uri.fromFile(output)
            assertTrue(controller.get().activityResultRegistry.dispatchResult(
                request.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            awaitMain { controller.get().documents.exportResult != null }
            assertTrue(output.readText().contains("https://example.com/bookmark"))
            assertEquals(1, controller.get().documents.exportResult?.count)
        } finally { controller.pause().stop().destroy(); output.delete() }
    }

    private fun recreateWithResult(result: BookmarkExportResult?): BookmarkExportResult? {
        val controller = Robolectric.buildActivity(BookmarkDocumentsStateActivity::class.java).setup()
        // Supply the completed export, then exercise Android's actual save/restore path.
        @Suppress("UNCHECKED_CAST")
        val state = BookmarkDocuments::class.java.getDeclaredField("exportResult\$delegate")
            .apply { isAccessible = true }.get(controller.get().documents) as MutableState<BookmarkExportResult?>
        state.value = result
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(BookmarkDocumentsStateActivity::class.java)
            .create(saved).start().resume().visible()
        return try { restored.get().documents.exportResult }
        finally { restored.pause().stop().destroy() }
    }

    @Test fun completedExportRetainsEveryWarningAfterRecreation() {
        val result = BookmarkExportResult(count = 5000, omitted = 1000, unsupported = 7)
        assertEquals(result, recreateWithResult(result))
    }

    @Test fun recreationWithoutAnExportDoesNotShowAZeroCountResult() {
        assertNull(recreateWithResult(null))
    }
}
