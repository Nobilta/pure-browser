package com.mybrowser.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookmarkIconsTest {
    @Test fun cacheKeepsExactPageIdentityAndScalesIconsWithoutRecyclingTheSource() {
        val context = RuntimeEnvironment.getApplication()
        val icon = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            BookmarkIcons.write(context, "https://site.test/a", icon)
            assertFalse(icon.isRecycled)
            val stored = BookmarkIcons.read(context, "https://site.test/a")!!
            assertEquals(64, stored.width)
            assertEquals(64, stored.height)
            assertNull(BookmarkIcons.read(context, "https://site.test/b"))
            assertNull(BookmarkIcons.read(context, "http://site.test/a"))
        } finally { icon.recycle() }
    }
}
