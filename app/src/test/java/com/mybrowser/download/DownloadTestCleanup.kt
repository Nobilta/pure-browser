package com.mybrowser.download

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf

/** Finish IO cleanup before Robolectric resets its filesystem and ContentResolver. */
internal fun DownloadHandler.closeAndAwait() = runBlocking {
    val scope = DownloadHandler::class.java.getDeclaredField("transferScope").apply { isAccessible = true }
        .get(this@closeAndAwait) as CoroutineScope
    close()
    val job = scope.coroutineContext[Job]!!
    withTimeout(10_000) {
        while (!job.isCompleted) {
            // A cancelled deletion callback still needs the paused main looper to unwind.
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }
}
