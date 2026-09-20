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
    @Test fun anUnreadableStoreIsKeptInsteadOfBeingResetByTheNextWrite() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("site_settings", android.content.Context.MODE_PRIVATE)
        val corrupt = "{not json"
        assertTrue(prefs.edit().putString("sites", corrupt).commit())
        val repo = SiteSettingsRepository(context)
        // The session runs on defaults, but the bytes the user cannot currently read stay put.
        assertTrue(repo.get("https://example.com").filtering)
        assertTrue(repo.needsRepair.value)
        try {
            repo.update("https://example.com") { it.copy(javaScript = false) }
            fail("A non-persistent change must not report success")
        } catch (_: IllegalStateException) { }
        assertTrue(repo.get("https://example.com").javaScript)
        assertTrue(SiteSettingsRepository(context).get("https://example.com").javaScript)
        assertEquals(corrupt, prefs.getString("sites", null))
        // Discarding everything on purpose is still allowed to replace it.
        repo.repair()
        assertFalse(repo.needsRepair.value)
        assertNotEquals(corrupt, prefs.getString("sites", null))
    }

    @Test fun anUnreadableStorePersistsAgainAfterExplicitRepair() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("site_settings", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("sites", "{not json").commit())
        val repo = SiteSettingsRepository(context)
        repo.repair()
        // The repair restored persistence: a later change reaches disk without another force.
        repo.update("https://example.com") { it.copy(javaScript = false) }
        val saved = prefs.getString("sites", null)
        assertTrue(saved != null && saved.contains("example.com"))
        // And a fresh repository reads the repaired store back.
        assertFalse(SiteSettingsRepository(context).get("https://example.com").javaScript)
    }

    @Test fun clearingPermissionsNeverOverwritesAnUnreadableStore() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("site_settings", android.content.Context.MODE_PRIVATE)
        val corrupt = "{not json"
        assertTrue(prefs.edit().putString("sites", corrupt).commit())
        val repo = SiteSettingsRepository(context)
        try {
            repo.clearPermissions()
            fail("Permission clearing must fail on an unreadable store")
        } catch (_: IllegalStateException) { }
        assertEquals(corrupt, prefs.getString("sites", null))
        assertTrue(repo.needsRepair.value)
        // The user declining the repair keeps the original bytes and the protection.
        assertTrue(SiteSettingsRepository(context).needsRepair.value)
        assertEquals(corrupt, prefs.getString("sites", null))
    }

    @Test fun repairedClearPermissionsOnlyRemovesPermissions() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("site_settings", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("sites", "{not json").commit())
        val repo = SiteSettingsRepository(context)
        repo.repair()
        repo.update("https://example.com") {
            it.copy(camera = SitePermission.ALLOW, javaScript = false, desktop = true, desktopWidth = 1440, enhancedPlayback = false)
        }
        repo.clearPermissions()
        val restored = SiteSettingsRepository(context)
        assertEquals(SitePermission.ASK, restored.get("https://example.com").camera)
        assertFalse(restored.get("https://example.com").javaScript)
        assertTrue(restored.get("https://example.com").desktop)
        assertEquals(1440, restored.get("https://example.com").desktopWidth)
        assertFalse(restored.get("https://example.com").useEnhancedPlayback(true))
    }

    @Test fun enhancedPlaybackOverridesTheDefaultOnlyForTheSavedOrigin() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = SiteSettingsRepository(context)
        assertTrue(repo.get("https://example.com").useEnhancedPlayback(true))
        assertFalse(repo.get("https://example.com").useEnhancedPlayback(false))
        repo.update("https://example.com/watch") { it.copy(enhancedPlayback = false) }
        repo.update("https://enabled.example") { it.copy(enhancedPlayback = true) }
        val restored = SiteSettingsRepository(context)
        assertFalse(restored.get("https://example.com:443/other").useEnhancedPlayback(true))
        assertTrue(restored.get("https://enabled.example").useEnhancedPlayback(false))
        listOf("http://example.com", "https://example.com:444", "https://www.example.com").forEach {
            assertNull(restored.get(it).enhancedPlayback)
        }
        restored.clearPermissions()
        assertFalse(restored.get("https://example.com").useEnhancedPlayback(true))
        restored.reset("https://example.com")
        assertNull(SiteSettingsRepository(context).get("https://example.com").enhancedPlayback)
    }

    @Test fun repairDoesNotEraseAHealthyStoreAndAliasIndexTracksEveryMutation() = runBlocking {
        val repo = SiteSettingsRepository(RuntimeEnvironment.getApplication())
        repo.update("https://m.example.com") { it.copy(desktop = true, desktopWidth = 1280, camera = SitePermission.BLOCK) }
        assertEquals(1280, repo.get("https://www.example.com").desktopWidth)
        assertEquals(SitePermission.ASK, repo.get("https://www.example.com").camera)
        repo.repair()
        assertTrue(repo.get("https://example.com").desktop)
        repo.update("https://example.com") { it.copy(desktopWidth = 1440) }
        assertEquals(1440, repo.get("https://m.example.com").desktopWidth)
        repo.clearPermissions()
        assertTrue(repo.get("https://www.example.com").desktop)
        repo.reset("https://www.example.com")
        assertFalse(repo.get("https://example.com").desktop)
        assertFalse(repo.get("https://m.example.com").desktop)
    }

    @Test fun privatePlaybackOverridesDoNotChangeTheSavedWebsiteChoice() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = SiteSettingsRepository(context)
        repo.update("https://example.com") { it.copy(enhancedPlayback = false) }
        val private = repo.privateSession()
        assertFalse(private.get("https://example.com").useEnhancedPlayback(true))
        private.update("https://example.com") { it.copy(enhancedPlayback = true) }
        assertTrue(private.get("https://example.com").useEnhancedPlayback(false))
        assertFalse(SiteSettingsRepository(context).get("https://example.com").useEnhancedPlayback(true))
    }

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
