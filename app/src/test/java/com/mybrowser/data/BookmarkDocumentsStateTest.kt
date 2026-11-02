package com.mybrowser.data

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class BookmarkDocumentsStateActivity : ComponentActivity() {
    lateinit var documents: BookmarkDocuments
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documents = BookmarkDocuments(this, lifecycleScope, { error("No repository access needed") }, {}, { _, _ -> })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class BookmarkDocumentsStateTest {
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
