package com.mybrowser.site

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SiteSettingsTest {
    @Test fun originsNormalizeDefaultPortsAndInternationalHostsWithoutSharingGrants() {
        assertEquals("https://example.com", SiteOrigin.of("HTTPS://Example.com:443/path?q=1"))
        assertEquals("http://example.com", SiteOrigin.of("http://example.com:80/"))
        assertEquals("https://example.com:444", SiteOrigin.of("https://example.com:444/"))
        assertEquals("https://xn--bcher-kva.de", SiteOrigin.of("https://bücher.de/read"))
        assertEquals("http://[::1]:8080", SiteOrigin.of("http://[::1]:8080/"))
        listOf("file:///tmp/a", "https://example.com:65536", "https://", "javascript:alert(1)").forEach {
            assertNull(it, SiteOrigin.of(it))
        }
        assertFalse(SiteOrigin.canRequestPermission("http://example.com"))
        assertTrue(SiteOrigin.canRequestPermission("http://127.0.0.1:8000"))
        assertFalse(SiteOrigin.canRequestPermission("http://localhost.example.com"))
    }

    @Test fun savedPermissionsStayWithinTheirOriginAndSurviveRepositoryRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = SiteSettingsRepository(context)
        repo.update("https://example.com/path") { it.copy(camera = SitePermission.ALLOW, desktop = true, textZoom = 900) }
        val restored = SiteSettingsRepository(context)
        assertEquals(SitePermission.ALLOW, restored.get("https://example.com:443/other").camera)
        assertEquals(200, restored.get("https://example.com").textZoom)
        listOf("http://example.com", "https://sub.example.com", "https://example.com:444").forEach {
            assertEquals(SiteSettings(), restored.get(it))
        }
        restored.reset("https://example.com")
        assertTrue(SiteSettingsRepository(context).entries.value.isEmpty())
    }

    @Test fun privateChoicesNeverChangeNormalSettingsOrPersist() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val normal = SiteSettingsRepository(context)
        normal.update("https://example.com") { it.copy(camera = SitePermission.ALLOW, microphone = SitePermission.BLOCK, filtering = false) }
        val private = normal.privateSession()
        assertEquals(SitePermission.ASK, private.get("https://example.com").camera)
        assertEquals(SitePermission.BLOCK, private.get("https://example.com").microphone)
        assertFalse(private.get("https://example.com").filtering)
        private.update("https://private.example") { it.copy(location = SitePermission.ALLOW) }
        private.reset("https://example.com")
        assertEquals(SitePermission.ASK, SiteSettingsRepository(context).get("https://private.example").location)
        assertEquals(SitePermission.ALLOW, normal.get("https://example.com").camera)
    }

    @Test fun concurrentChangesDoNotLoseOtherPermissionsAndClearingPreservesPagePreferences() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        SiteCapability.entries.map { capability -> async {
            repo.update("https://example.com") { it.withPermission(capability, SitePermission.ALLOW) }
        } }.awaitAll()
        assertTrue(SiteCapability.entries.all { repo.get("https://example.com").permission(it) == SitePermission.ALLOW })
        repo.update("https://example.com") { it.copy(javaScript = false) }
        repo.clearPermissions()
        assertFalse(repo.get("https://example.com").javaScript)
        assertTrue(SiteCapability.entries.all { repo.get("https://example.com").permission(it) == SitePermission.ASK })
    }
}
