package com.mybrowser.download

import android.app.Activity
import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadNotificationGuideTest {
    @Test fun oldRequestHistoryRoutesFixedDenialStraightToSettings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val current = activity.getSharedPreferences("download_notification_guide", Context.MODE_PRIVATE)
            val old = activity.getSharedPreferences("download_notifications", Context.MODE_PRIVATE)
            current.edit().clear().commit(); old.edit().clear().commit()
            assertEquals(DownloadNotificationGuide.Route.PermissionRequest, DownloadNotificationGuide.route(activity))
            old.edit().putBoolean("requested", true).commit()
            assertEquals(DownloadNotificationGuide.Route.AppSettings, DownloadNotificationGuide.route(activity))
            assertTrue(current.getBoolean("permission_requested", false))
        } finally { controller.pause().stop().destroy() }
    }
}
