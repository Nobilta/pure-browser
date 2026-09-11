package com.mybrowser.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Credentials stay with the Android provider; the browser never stores passwords. */
object SystemLoginSupport {
    val supportsWebAuthn: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION) }.getOrDefault(false)

    fun hasOriginPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.checkSelfPermission(Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN) == PackageManager.PERMISSION_GRANTED

    internal fun webAuthnMode(context: Context, isPrivate: Boolean): Int =
        if (isPrivate || !hasOriginPermission(context)) WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
        else WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER

    @SuppressLint("RequiresFeature")
    fun configure(view: WebView, isPrivate: Boolean) {
        view.importantForAutofill = if (isPrivate) View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            else View.IMPORTANT_FOR_AUTOFILL_AUTO
        if (supportsWebAuthn) runCatching {
            // A missing origin permission otherwise throws asynchronously inside Chromium's
            // CredentialManager call, outside this configuration call's exception boundary.
            WebSettingsCompat.setWebAuthenticationSupport(view.settings, webAuthnMode(view.context, isPrivate))
        }
    }

    fun autofillEnabled(context: Context) = context.getSystemService(AutofillManager::class.java)?.isEnabled == true
    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
