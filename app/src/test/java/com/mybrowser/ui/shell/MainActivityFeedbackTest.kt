package com.mybrowser.ui.shell

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import com.mybrowser.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class MainActivityFeedbackTest {
    /** Exercise the actual feedback path without starting Chromium or the browser session. */
    private fun feedbackTest(test: (MainActivity, SnackbarHostState, LifecycleRegistry) -> Unit) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val restore = ComponentActivity::class.java.getDeclaredField("savedStateRegistryController")
            .apply { isAccessible = true }.get(activity) as SavedStateRegistryController
        restore.performRestore(null)
        val lifecycle = activity.lifecycle as LifecycleRegistry
        lifecycle.currentState = Lifecycle.State.RESUMED
        val host = MainActivity::class.java.getDeclaredField("snackbarHostState")
            .apply { isAccessible = true }.get(activity) as SnackbarHostState
        try {
            test(activity, host, lifecycle)
        } finally {
            lifecycle.currentState = Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun MainActivity.notify(message: String, action: String? = null, important: Boolean = false) {
        javaClass.getDeclaredMethod(
            "snackbar", String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }.invoke(this, message, action, important, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun importantVisibleResultIsReplayedAfterUndo() = feedbackTest { activity, host, _ ->
        activity.notify("Skipped 10 non-web bookmarks", important = true)
        assertTrue((host.currentSnackbarData!!.visuals as FeedbackMessage).important)
        activity.notify("Bookmark removed", action = "Undo")
        assertEquals("Bookmark removed", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Skipped 10 non-web bookmarks", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(host.currentSnackbarData)
    }

    @Test fun importantPausedResultWaitsForResume() = feedbackTest { activity, host, lifecycle ->
        lifecycle.currentState = Lifecycle.State.STARTED
        activity.notify("Import skipped invalid entries", important = true)
        assertNull(host.currentSnackbarData)
        lifecycle.currentState = Lifecycle.State.RESUMED
        activity.javaClass.getDeclaredMethod("startFeedbackIfResumed")
            .apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Import skipped invalid entries", host.currentSnackbarData?.visuals?.message)
    }
}
