package com.mybrowser.core

import android.content.Context
import android.content.Intent

/**
 * Naming the app a page's deep link would open.
 *
 * Launching itself never needs this: `startActivity` resolves the sanitized intent on its
 * own. The confirmation prompt does, because "打开「中国银行」？" is a decision a user can
 * make while "使用 bocmbankpsn 打开" is not. Resolution runs on the same intent
 * [ExternalIntentHandler] would launch, so the name shown and the app that opens cannot
 * drift apart.
 *
 * `queryIntentActivities` only sees packages visible to this app (Android 11+), and a
 * custom scheme cannot be listed in `<queries>`. The manifest therefore requests
 * QUERY_ALL_PACKAGES; it is used for this lookup and nothing else.
 */
object ExternalTargets {

    /**
     * Application label of the single app that would handle [url].
     *
     * Null when nothing resolves (the launch will fail anyway) or when several apps would
     * handle it: the system picker then decides, so promising one name would be wrong.
     */
    fun labelOf(context: Context, url: String): String? =
        (ExternalIntentHandler.prepare(url) as? ExternalIntentHandler.Prepared.Ready)
            ?.let { labelOf(context, it.intent) }

    fun labelOf(context: Context, intent: Intent): String? {
        val manager = context.packageManager
        val packages = runCatching { manager.queryIntentActivities(intent, 0) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val application = info.activityInfo?.applicationInfo
                val label = application
                    ?.let { app -> runCatching { manager.getApplicationLabel(app).toString() }.getOrNull() }
                    ?: runCatching { info.loadLabel(manager)?.toString() }.getOrNull()
                packageName to label
            }
            .distinctBy { it.first }
        if (packages.size != 1) return null
        return packages.single().second
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(LABEL_LIMIT)
    }

    /** Labels are app-controlled text; the dialog is not a place for a paragraph. */
    private const val LABEL_LIMIT = 80
}
