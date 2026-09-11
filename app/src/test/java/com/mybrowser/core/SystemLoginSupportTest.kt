package com.mybrowser.core

import android.Manifest
import android.app.Application
import androidx.webkit.WebSettingsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemLoginSupportTest {
    @Test fun modernBrowserModeRequiresOriginPermission() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN)
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
            SystemLoginSupport.webAuthnMode(context, false))
        shadowOf(context).denyPermissions(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN)
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
            SystemLoginSupport.webAuthnMode(context, false))
    }

    @Test fun privatePagesNeverUseGrantedOriginAccess() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN)
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
            SystemLoginSupport.webAuthnMode(context, true))
    }

    @Test @Config(sdk = [29]) fun oldAndroidDoesNotRequireTheNewPlatformPermission() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN)
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
            SystemLoginSupport.webAuthnMode(context, false))
    }
}
