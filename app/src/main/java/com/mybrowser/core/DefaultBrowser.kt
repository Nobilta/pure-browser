package com.mybrowser.core

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

object DefaultBrowser {
    fun isDefault(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    }.getOrDefault(false)

    fun requestIntent(context: Context): Intent = runCatching {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            !roles.isRoleHeld(RoleManager.ROLE_BROWSER)
        ) {
            roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        } else {
            settingsIntent()
        }
    }.getOrElse { settingsIntent() }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
