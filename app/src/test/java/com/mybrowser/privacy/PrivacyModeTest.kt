package com.mybrowser.privacy

import android.app.Application
import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PrivacyModeTest.NoProfiles::class])
class PrivacyModeTest {
    @Implements(WebViewFeature::class, isInAndroidSdk = false)
    class NoProfiles {
        companion object {
            @JvmStatic @Implementation fun isFeatureSupported(feature: String) = false
        }
    }

    @Test fun autofillIsExcludedOnlyInThePrivateSession() {
        val context = RuntimeEnvironment.getApplication()
        val privacy = PrivacyMode(context)
        val view = WebView(context)
        privacy.applyTo(view)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, view.importantForAutofill)
        privacy.enter()
        privacy.applyTo(view)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.importantForAutofill)
        privacy.exit(wipeSharedStorage = false)
        privacy.applyTo(view)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, view.importantForAutofill)
        view.destroy()
    }
}
