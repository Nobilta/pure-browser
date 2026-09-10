package com.mybrowser.core

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.core.net.toUri
import com.mybrowser.MainActivity
import com.mybrowser.R

object WebsiteShortcuts {
    fun pin(context: Context, url: String, title: String, bitmap: Bitmap?): Boolean {
        if (!UrlUtils.isHttpUrl(url)) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val label = title.trim().ifBlank { UrlUtils.hostOf(url).orEmpty() }.take(40)
        val icon = bitmap?.takeUnless { it.isRecycled }?.let(Icon::createWithBitmap)
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        val shortcut = ShortcutInfo.Builder(context, "site-" + TextDownloader.sha256(url).take(32))
            .setShortLabel(label).setIcon(icon)
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url.toUri())).build()
        return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
    }
}
