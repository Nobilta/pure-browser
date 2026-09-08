package com.mybrowser.home

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

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
        assertFalse(settings.restoreLastSession)
    }

    @Test
    fun `session restore is explicit persistent and independent of homepage`() {
        repository.saveMode(HomepageMode.FIXED_URL)
        repository.saveFixedUrl("https://example.com/home")
        assertFalse(repository.loadSettings().restoreLastSession)
        repository.saveRestoreLastSession(true)
        assertTrue(HomeRepository(context).loadSettings().restoreLastSession)
        repository.saveRestoreLastSession(false)
        val settings = HomeRepository(context).loadSettings()
        assertFalse(settings.restoreLastSession)
        assertEquals(HomepageMode.FIXED_URL, settings.mode)
        assertEquals("https://example.com/home", settings.fixedUrl)
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

    @Test
    fun `editing URL keeps identity timestamp position and does not create a second tile`() {
        val first = repository.upsertShortcut("First", "https://one.example", null).single()
        repository.upsertShortcut("Second", "https://two.example", null)

        assertEquals(ShortcutSaveResult.SAVED,
            repository.updateShortcut(first.id, " Renamed ", "changed.example/page", ShortcutIconChange.Keep))

        val loaded = HomeRepository(context).loadShortcuts()
        assertEquals(2, loaded.size)
        assertEquals(first.id, loaded.first().id)
        assertEquals(first.createdAt, loaded.first().createdAt)
        assertEquals("Renamed", loaded.first().title)
        assertEquals("https://changed.example/page", loaded.first().url)
        assertEquals("Second", loaded.last().title)
    }

    @Test
    fun `duplicate URL and invalid address leave existing records intact`() {
        val first = repository.upsertShortcut("First", "https://one.example", null).single()
        val before = repository.upsertShortcut("Second", "https://two.example", null)

        assertEquals(ShortcutSaveResult.DUPLICATE_URL,
            repository.updateShortcut(first.id, "Changed", "two.example", ShortcutIconChange.UseText))
        for (url in listOf("hello world", "javascript:alert(1)", "content://local/image", "https://", "tel:1234")) {
            assertEquals(ShortcutSaveResult.INVALID_URL,
                repository.updateShortcut(first.id, "Changed", url, ShortcutIconChange.UseText))
        }
        assertEquals(before, repository.loadShortcuts())
    }

    @Test
    fun `editing a removed tile cannot recreate it`() {
        val first = repository.upsertShortcut("First", "https://one.example", null).single()
        repository.removeShortcut(first.id)
        assertEquals(ShortcutSaveResult.NOT_FOUND,
            repository.updateShortcut(first.id, "Changed", first.url, ShortcutIconChange.Keep))
        assertTrue(repository.loadShortcuts().isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `custom image is bounded persistent and protected from later favicon updates`() {
        val first = repository.upsertShortcut("First", "https://one.example", null).single()
        val custom = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        assertEquals(ShortcutSaveResult.SAVED,
            repository.updateShortcut(first.id, first.title, first.url, ShortcutIconChange.Replace(custom)))
        custom.recycle()
        val icon = HomeRepository(context).loadShortcuts().single().icon
        assertNotNull(icon)
        assertEquals(96, icon!!.width)
        assertEquals(48, icon.height)
        assertEquals(Color.RED, icon.getPixel(0, 0))

        val siteIcon = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val updated = repository.upsertShortcut("First", first.url, siteIcon).single()
        assertEquals(Color.RED, updated.icon!!.getPixel(0, 0))
        siteIcon.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `text icon removes saved image and survives bookmarking again`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val first = repository.upsertShortcut("First", "https://one.example", bitmap).single()
        assertNotNull(first.icon)
        assertEquals(ShortcutSaveResult.SAVED,
            repository.updateShortcut(first.id, "Updated", first.url, ShortcutIconChange.UseText))
        assertNull(HomeRepository(context).loadShortcuts().single().icon)
        assertTrue(File(context.filesDir, "homepage_icons").listFiles().orEmpty().isEmpty())
        assertNull(repository.upsertShortcut("Updated", first.url, bitmap).single().icon)
        bitmap.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `unwritable replacement preserves the old image and metadata`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val first = repository.upsertShortcut("First", "https://one.example", bitmap).single()
        val temporary = File(context.filesDir, "homepage_icons/${first.id}.tmp")
        assertTrue(temporary.mkdir())
        assertEquals(ShortcutSaveResult.ICON_ERROR,
            repository.updateShortcut(first.id, "Changed", "https://changed.example", ShortcutIconChange.Replace(bitmap)))
        val loaded = HomeRepository(context).loadShortcuts().single()
        assertEquals(first.title, loaded.title)
        assertEquals(first.url, loaded.url)
        assertEquals(Color.BLUE, loaded.icon!!.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test
    fun `failed image validation prevents partial title and URL updates`() {
        val first = repository.upsertShortcut("First", "https://one.example", null).single()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { recycle() }
        assertEquals(ShortcutSaveResult.ICON_ERROR,
            repository.updateShortcut(first.id, "Changed", "https://changed.example", ShortcutIconChange.Replace(bitmap)))
        assertEquals(first, repository.loadShortcuts().single())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `failed metadata commit keeps the original values and image for every icon choice`() {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val replacement = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val first = repository.upsertShortcut("First", "https://one.example", original).single()
        val prefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val before = prefs.getString("homepage_shortcuts", null)
        val directory = File(context.filesDir, "homepage_icons")
        val beforeFiles = directory.listFiles().orEmpty().map { it.name }.sorted()

        for (change in listOf(ShortcutIconChange.Keep, ShortcutIconChange.Replace(replacement), ShortcutIconChange.UseText)) {
            assertEquals(ShortcutSaveResult.FAILED,
                failingCommitRepository().updateShortcut(first.id, "Changed", "https://changed.example", change))
            assertEquals(before, prefs.getString("homepage_shortcuts", null))
            assertEquals(beforeFiles, directory.listFiles().orEmpty().map { it.name }.sorted())
            val loaded = HomeRepository(context).loadShortcuts().single()
            assertEquals(first.title, loaded.title)
            assertEquals(first.url, loaded.url)
            assertEquals(Color.BLUE, loaded.icon!!.getPixel(0, 0))
        }
        assertEquals(ShortcutSaveResult.SAVED,
            repository.updateShortcut(first.id, "Changed", first.url, ShortcutIconChange.Replace(replacement)))
        assertEquals(Color.RED, HomeRepository(context).loadShortcuts().single().icon!!.getPixel(0, 0))
        assertEquals(1, directory.listFiles().orEmpty().size)
        original.recycle()
        replacement.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `failed removal retains both the record and its image`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val first = repository.upsertShortcut("First", "https://one.example", bitmap).single()

        assertThrows(IOException::class.java) { failingCommitRepository().removeShortcut(first.id) }

        val loaded = HomeRepository(context).loadShortcuts().single()
        assertEquals(first.id, loaded.id)
        assertEquals(Color.BLUE, loaded.icon!!.getPixel(0, 0))
        assertEquals(1, File(context.filesDir, "homepage_icons").listFiles().orEmpty().size)
        bitmap.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `failed bookmark upsert cleans the new image and leaves existing shortcuts intact`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val first = repository.upsertShortcut("First", "https://one.example", bitmap).single()
        val prefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val before = prefs.getString("homepage_shortcuts", null)
        val directory = File(context.filesDir, "homepage_icons")
        val beforeFiles = directory.listFiles().orEmpty().map { it.name }.sorted()

        for (url in listOf(first.url, "https://new.example")) {
            assertThrows(IOException::class.java) { failingCommitRepository().upsertShortcut("Changed", url, bitmap) }
            assertEquals(before, prefs.getString("homepage_shortcuts", null))
            assertEquals(beforeFiles, directory.listFiles().orEmpty().map { it.name }.sorted())
            assertEquals(Color.BLUE, HomeRepository(context).loadShortcuts().single().icon!!.getPixel(0, 0))
        }
        bitmap.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `successful image replacement removes superseded files`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val first = repository.upsertShortcut("First", "https://one.example", bitmap).single()
        val directory = File(context.filesDir, "homepage_icons")
        val initialFile = directory.listFiles()!!.single()
        repository.upsertShortcut("First", first.url, bitmap)
        assertFalse(initialFile.exists())
        val faviconFile = directory.listFiles()!!.single()

        assertEquals(ShortcutSaveResult.SAVED,
            repository.updateShortcut(first.id, first.title, first.url, ShortcutIconChange.Replace(bitmap)))
        assertFalse(faviconFile.exists())
        assertEquals(1, directory.listFiles().orEmpty().size)

        repository.removeShortcut(first.id)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        bitmap.recycle()
    }

    /** A real commit updates the map before reporting failure, just like Android on a disk error. */
    private fun failingCommitRepository(): HomeRepository {
        val actual = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by actual {
            var failNextCommit = true

            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        val committed = editor.commit()
                        if (!failNextCommit) return committed
                        failNextCommit = false
                        return false
                    }
                }
            }
        }
        return HomeRepository(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = failing
        })
    }
}
