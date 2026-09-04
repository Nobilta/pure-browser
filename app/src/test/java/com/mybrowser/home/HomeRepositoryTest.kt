package com.mybrowser.home

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: HomeRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, "homepage_icons").deleteRecursively()
        repository = HomeRepository(context)
    }

    @Test
    fun `fresh install uses navigation homepage`() {
        val settings = repository.loadSettings()

        assertEquals(HomepageMode.NAVIGATION, settings.mode)
        assertEquals("https://www.bing.com", settings.fixedUrl)
    }

    @Test
    fun `legacy homepage preference migrates to fixed url mode`() {
        context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("homepage", "https://legacy.example/start")
            .commit()

        val settings = repository.loadSettings()

        assertEquals(HomepageMode.FIXED_URL, settings.mode)
        assertEquals("https://legacy.example/start", settings.fixedUrl)
    }

    @Test
    fun `shortcut upsert deduplicates by url and preserves position`() {
        val first = repository.upsertShortcut(
            title = "旧标题",
            url = "https://example.com/page",
            favicon = null,
        ).single()
        val second = repository.upsertShortcut(
            title = "新标题",
            url = "https://example.com/page",
            favicon = null,
        ).single()

        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("新标题", second.title)
    }

    @Test
    fun `remove shortcut does not affect the remaining entries`() {
        val first = repository.upsertShortcut("一", "https://one.example", null).single()
        repository.upsertShortcut("二", "https://two.example", null)

        val remaining = repository.removeShortcut(first.id)

        assertEquals(listOf("https://two.example"), remaining.map { it.url })
    }

    @Test
    fun `non web shortcut is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.upsertShortcut("电话", "tel:1234", null)
        }
        assertTrue(repository.loadShortcuts().isEmpty())
    }

    @Test
    fun `full dashboard rejects another tile without leaving an icon file`() {
        repeat(24) { index ->
            repository.upsertShortcut(
                title = "站点 $index",
                url = "https://site$index.example",
                favicon = null,
            )
        }
        val iconDirectory = File(context.filesDir, "homepage_icons")
        val beforeFiles = iconDirectory.listFiles().orEmpty().map { it.name }.sorted()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

        val result = try {
            repository.upsertShortcut("溢出", "https://overflow.example", bitmap)
        } finally {
            bitmap.recycle()
        }

        assertEquals(24, result.size)
        assertTrue(result.none { it.url == "https://overflow.example" })
        assertEquals(beforeFiles, iconDirectory.listFiles().orEmpty().map { it.name }.sorted())
    }
}
