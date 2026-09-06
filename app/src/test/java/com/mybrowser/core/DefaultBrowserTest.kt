package com.mybrowser.core

import android.app.Application
import android.app.role.RoleManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class DefaultBrowserTest {
    @Test
    fun systemRoleControlsStatusAndRequest() {
        val app = RuntimeEnvironment.getApplication()
        val roles = shadowOf(app.getSystemService(RoleManager::class.java))
        roles.addAvailableRole(RoleManager.ROLE_BROWSER)
        assertFalse(DefaultBrowser.isDefault(app))
        assertEquals("android.app.role.action.REQUEST_ROLE", DefaultBrowser.requestIntent(app).action)
        roles.addHeldRole(RoleManager.ROLE_BROWSER)
        assertTrue(DefaultBrowser.isDefault(app))
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, DefaultBrowser.requestIntent(app).action)
        roles.removeHeldRole(RoleManager.ROLE_BROWSER)
        assertFalse(DefaultBrowser.isDefault(app))
    }

    @Test
    fun missingRoleFallsBackToSystemSettings() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, DefaultBrowser.requestIntent(app).action)
    }
}
