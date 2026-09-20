package com.mybrowser.download

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Real system state of download notifications, and where to send the user who wants to
 * change it. The settings row shows this state and never claims more than the system
 * allows: the guide only starts when the user taps it, and a refusal keeps downloads
 * working — notifications are a display preference, not a download requirement.
 */
object DownloadNotificationGuide {

    private const val PREFS = "download_notification_guide"
    private const val KEY_ASKED = "permission_requested"

    /**
     * Whether a download notification can actually appear. A missing channel is not
     * "disabled": the channel is created with its default (on) the first time a
     * download runs, so only a user-set IMPORTANCE_NONE counts as off.
     */
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(DownloadTransferService.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Where the settings action goes, most useful first. */
    sealed interface Route {
        /** The runtime permission dialog can still be shown (never asked, or not fixed-denied). */
        data object PermissionRequest : Route

        /** The app's notifications are off system-wide, or no narrower page applies. */
        data object AppSettings : Route

        /** Everything else is allowed but the downloads channel itself was turned off. */
        data object ChannelSettings : Route
    }

    /**
     * Decides the entry point from real system state plus this app's request history.
     * `shouldShowRequestPermissionRationale` is false both before the first request and
     * after a permanent denial, so the two are told apart by whether a request was ever
     * launched here; after a permanent denial the system dialog can no longer appear
     * and the user is taken straight to the app's notification settings instead.
     */
    fun route(activity: Activity): Route {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacyAsked = activity.getSharedPreferences("download_notifications", Context.MODE_PRIVATE)
                .getBoolean("requested", false)
            val asked = prefs.getBoolean(KEY_ASKED, false) || legacyAsked
            if (legacyAsked && !prefs.getBoolean(KEY_ASKED, false)) {
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
            }
            val rationale = activity.shouldShowRequestPermissionRationale(
                android.Manifest.permission.POST_NOTIFICATIONS)
            return if (!asked || rationale) Route.PermissionRequest else Route.AppSettings
        }
        val manager = activity.getSystemService(NotificationManager::class.java)
        if (manager != null && !manager.areNotificationsEnabled()) return Route.AppSettings
        // The channel page needs the channel to exist; create it (this preserves the
        // user's choices for an existing channel) without starting the download service.
        DownloadTransferService.ensureChannel(activity)
        val channel = manager?.getNotificationChannel(DownloadTransferService.CHANNEL_ID)
        return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE)
            Route.ChannelSettings else Route.AppSettings
    }

    /** Records that the system permission dialog was launched from the settings row. */
    fun markPermissionRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun appSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    ).apply {
        // The action is documented to require this extra; the package data URI alone
        // is not the contract on every system version.
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        data = Uri.fromParts("package", context.packageName, null)
    }

    fun channelSettingsIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, DownloadTransferService.CHANNEL_ID)
        return intent
    }

    /** Opens the requested settings page, ending at the app details page if nothing else resolves. */
    fun safeStart(context: Context, intent: Intent) {
        val resolved = context.packageManager.resolveActivity(intent, 0) != null
        val target = if (resolved) intent else appSettingsIntent(context)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(target) }
            .onFailure { runCatching { context.startActivity(details) } }
    }
}
