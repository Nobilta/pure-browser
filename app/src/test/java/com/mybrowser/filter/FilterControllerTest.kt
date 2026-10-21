package com.mybrowser.filter

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FilterControllerTest {
    @Test fun aRejectedSubscriptionSetStillCompletesFirstBuildReadiness() = runBlocking {
        val controller = FilterController(RuntimeEnvironment.getApplication())
        try {
            // One over the engine's list ceiling. No rebuild can be scheduled, but navigation
            // must not wait forever for a first build that will never run.
            controller.replaceLists(List(FilterListFormat.MAX_LISTS + 1) { "||ads.example^" })
            withTimeout(2_000) { controller.awaitReady() }
            assertTrue(controller.isReady)
        } finally {
            controller.close()
        }
    }
}
