package com.mybrowser.site

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DesktopSiteTest {
    private val mobile = "https://m.jrs16.com"
    private val desktop = "https://www.jrs16.com"

    @Test fun mobileToDesktopRedirectKeepsTheRequestedModeAcrossEveryCallback() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        repo.update(mobile) { it.copy(desktop = true) }
        // shouldOverrideUrlLoading and onPageStarted can alternate, including a
        // callback for the old mobile document while the target is still loading.
        listOf(mobile, desktop, mobile, desktop, "$desktop/live?id=1").forEach {
            assertTrue(it, repo.get(it).desktop)
        }
        assertEquals(setOf(mobile), repo.entries.value.keys)
    }

    @Test fun onlyDesktopIsSharedBetweenPresentationAliases() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        val original = SiteSettings(desktop = true, filtering = false, javaScript = false,
            images = false, thirdPartyCookies = false, textZoom = 150,
            camera = SitePermission.ALLOW, microphone = SitePermission.BLOCK,
            location = SitePermission.ALLOW, protectedMedia = SitePermission.ALLOW)
        repo.update(mobile) { original }
        assertEquals(original, repo.get(mobile))
        listOf(desktop, "https://jrs16.com", "https://mobile.jrs16.com").forEach {
            assertEquals(it, SiteSettings(desktop = true), repo.get(it))
        }
    }

    @Test fun unrelatedSubdomainsSchemesPortsAndSitesKeepIndependentModes() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        repo.update(mobile) { it.copy(desktop = true) }
        listOf("https://news.jrs16.com", "https://m.news.jrs16.com", "http://www.jrs16.com",
            "https://www.jrs16.com:8443", "https://www.jrs16.com.evil.test",
            "https://www.jrs17.com").forEach { assertEquals(it, SiteSettings(), repo.get(it)) }
    }

    @Test fun disablingFromTheRedirectDestinationClearsEverySavedAliasAtomically() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        repo.update(mobile) { it.copy(desktop = true, camera = SitePermission.ALLOW) }
        repo.update(desktop) { it.copy(images = false, microphone = SitePermission.BLOCK) }
        repo.update(desktop) { it.copy(desktop = false) }
        val restored = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        listOf(mobile, desktop, "https://jrs16.com").forEach { assertFalse(it, restored.get(it).desktop) }
        assertEquals(SitePermission.ALLOW, restored.get(mobile).camera)
        assertEquals(SitePermission.ASK, restored.get(desktop).camera)
        assertEquals(SitePermission.BLOCK, restored.get(desktop).microphone)
        assertFalse(restored.get(desktop).images)
    }

    @Test fun resettingAnAliasClearsSharedDesktopButOnlyItsOwnOtherSettings() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        repo.update(mobile) { it.copy(desktop = true, javaScript = false, camera = SitePermission.BLOCK) }
        repo.update(desktop) { it.copy(images = false) }
        repo.reset(desktop)
        assertEquals(SiteSettings(), repo.get(desktop))
        assertEquals(SiteSettings(javaScript = false, camera = SitePermission.BLOCK), repo.get(mobile))
        assertEquals(setOf(mobile), repo.entries.value.keys)
    }

    @Test fun oldExactOriginPreferencesWorkWithoutMigrationOrExtraStorageEntries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val old = JSONObject().put(mobile, JSONObject().put("desktop", true).put("CAMERA", "ALLOW"))
            .put(desktop, JSONObject().put("desktop", false).put("images", false))
        context.getSharedPreferences("site_settings", Context.MODE_PRIVATE).edit()
            .putString("sites", old.toString()).commit()
        val repo = SiteSettingsRepository(context)
        assertTrue(repo.get(desktop).desktop)
        assertFalse(repo.get(desktop).images)
        assertEquals(SitePermission.ASK, repo.get(desktop).camera)
        assertEquals(setOf(mobile, desktop), repo.entries.value.keys)
        repo.update(desktop) { it.copy(desktop = false) }
        assertFalse(SiteSettingsRepository(context).get(mobile).desktop)
    }

    @Test fun privateDesktopChangesStayInsideThePrivateSession() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val normal = SiteSettingsRepository(context)
        normal.update(mobile) { it.copy(desktop = true, camera = SitePermission.ALLOW) }
        val private = normal.privateSession()
        assertTrue(private.get(desktop).desktop)
        assertEquals(SitePermission.ASK, private.get(mobile).camera)
        private.update(desktop) { it.copy(desktop = false) }
        assertFalse(private.get(mobile).desktop)
        assertTrue(normal.get(desktop).desktop)
        assertTrue(SiteSettingsRepository(context).get(desktop).desktop)
    }

    @Test fun keysNormalizeAliasesIdnAndDefaultPortsWithoutCollapsingOtherHosts() {
        assertEquals("https://jrs16.com", DesktopSite.of("HTTPS://WWW.JRS16.COM:443/live?q=1"))
        assertEquals("https://xn--bcher-kva.de", DesktopSite.of("https://m.bücher.de/"))
        assertEquals("https://news.example.com:8443", DesktopSite.of("https://m.news.example.com:8443/a"))
        listOf("http://127.0.0.1:8875", "http://[::1]:8875", "https://localhost", "https://m.com").forEach {
            assertEquals(it, DesktopSite.of(it))
        }
        val nested = DesktopSite.of("https://www.m.example.com")!!
        assertEquals(nested, DesktopSite.of(nested))
        listOf("file:///tmp/page", "javascript:alert(1)", "https://").forEach { assertNull(DesktopSite.of(it)) }
    }
}
