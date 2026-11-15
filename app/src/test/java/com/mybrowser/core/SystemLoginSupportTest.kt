package com.mybrowser.core

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
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
    @Test fun modernSettingsTargetsCredentialProviders() {
        val context = RuntimeEnvironment.getApplication()
        val intent = SystemLoginSupport.settingsIntent(context)
        assertEquals("android.settings.CREDENTIAL_PROVIDER", intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test @Config(sdk = [29]) fun olderSettingsTargetsAutofillPicker() {
        val context = RuntimeEnvironment.getApplication()
        val intent = SystemLoginSupport.settingsIntent(context)
        assertEquals(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test fun missingVendorPickerFallsBackToSystemSettings() {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                if (actions.size == 1) throw ActivityNotFoundException()
            }
        }
        SystemLoginSupport.openSettings(context)
        assertEquals(listOf("android.settings.CREDENTIAL_PROVIDER", Settings.ACTION_SETTINGS), actions)
    }

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

    @Test @Config(sdk = [33]) fun preCredentialManagerAndroidDoesNotRequireTheNewPlatformPermission() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN)
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
            SystemLoginSupport.webAuthnMode(context, false))
    }
}
