package com.mybrowser.tabs

import androidx.lifecycle.ViewModelStore
import androidx.webkit.WebViewFeature
import com.mybrowser.App
import com.mybrowser.download.DownloadHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, shadows = [BrowserSessionStateTest.NoProfiles::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserSessionStateTest {
    @Implements(WebViewFeature::class, isInAndroidSdk = false)
    class NoProfiles {
        companion object {
            @JvmStatic @Implementation fun isFeatureSupported(feature: String) = false
        }
    }

    @Test fun privateStartupRotatesDownloadsAndClosingWindowEndsScopeWhileRetainingCleanup() {
        val app = RuntimeEnvironment.getApplication() as App
        val handler = app.downloadHandler
        val url = "https://example.test/private.bin"
        handler.rotatePrivateScope()
        assertTrue(handler.enqueueOrGetExisting(url, null, null, null, isPrivate = true) is DownloadHandler.EnqueueOutcome.Started)
        assertNotNull(handler.existingTaskFor(url, true))
        val state = BrowserSessionState(app)
        val store = ViewModelStore()
        store.put("browser", state)
        try {
            state.beginPrivateSession()
            assertNull(handler.existingTaskFor(url, true))
            assertTrue(handler.enqueueOrGetExisting(url, null, null, null, isPrivate = true) is DownloadHandler.EnqueueOutcome.Started)
            assertNotNull(handler.existingTaskFor(url, true))
            store.clear()
            assertNull(handler.existingTaskFor(url, true))
            val reopened = BrowserSessionState(app)
            assertSame(state.privacy, reopened.privacy)
            assertFalse(reopened.privacy.isIncognito)
            assertTrue(reopened.privacy.isTransitioning)
        } finally { store.clear(); handler.close() }
    }
}
