package com.mybrowser.site

import android.Manifest
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsitePermissionsTest {
    private fun repo() = SiteSettingsRepository(RuntimeEnvironment.getApplication())

    @Test fun androidGrantStillRequiresWebsiteConsentAndRepeatedClicksCompleteOnce() = runBlocking {
        val replies = mutableListOf<Boolean>()
        val gate = WebsitePermissions(this, { true }, { fail("No OS prompt expected") }, { fail() })
        gate.request(1, "https://example.com", listOf(SiteCapability.CAMERA), false, repo(), { true }, replies::add)
        assertNotNull(gate.prompt)
        assertTrue(replies.isEmpty())
        gate.respond(true, false)
        gate.respond(false, false)
        yield()
        assertEquals(listOf(true), replies)
        assertNull(gate.prompt)
    }

    @Test fun cancelledAndroidResultCannotGrantALaterWebsite() = runBlocking {
        val granted = mutableSetOf<String>()
        val requested = mutableListOf<List<String>>()
        val replies = mutableListOf<Pair<Int, Boolean>>()
        val gate = WebsitePermissions(this, granted::contains, { requested += it.toList() }, { fail() })
        gate.request(1, "https://one.example", listOf(SiteCapability.CAMERA), false, repo(), { true }) { replies += 1 to it }
        gate.respond(true, true)
        gate.respond(true, true)
        assertEquals(1, requested.size)
        gate.cancel(1)
        gate.request(2, "https://two.example", listOf(SiteCapability.CAMERA), false, repo(), { true }) { replies += 2 to it }
        granted += Manifest.permission.CAMERA
        gate.onRuntimeResult()
        yield()
        assertEquals(listOf(1 to false, 2 to false), replies)
        assertEquals(SitePermission.ASK, repo().get("https://one.example").camera)
        gate.request(3, "https://two.example", listOf(SiteCapability.CAMERA), false, repo(), { true }) { replies += 3 to it }
        assertNotNull(gate.prompt)
        gate.cancel()
    }

    @Test fun rememberedChoiceIsSavedBeforeReplyAndBlockedSitesDoNotPrompt() = runBlocking {
        val repository = repo()
        val reply = CompletableDeferred<Boolean>()
        val gate = WebsitePermissions(this, { true }, { fail() }, { fail() })
        gate.request(1, "https://example.com", listOf(SiteCapability.MICROPHONE), false, repository, { true }) { reply.complete(it) }
        gate.respond(false, true)
        assertFalse(withTimeout(5_000) { reply.await() })
        assertEquals(SitePermission.BLOCK, repo().get("https://example.com").microphone)
        val blocked = mutableListOf<Boolean>()
        gate.request(2, "https://example.com", listOf(SiteCapability.MICROPHONE), false, repository, { true }, blocked::add)
        assertEquals(listOf(false), blocked)
        assertNull(gate.prompt)
    }

    @Test fun navigationAndInsecureOriginsCannotReceivePermission() = runBlocking {
        var current = true
        val replies = mutableListOf<Boolean>()
        val gate = WebsitePermissions(this, { true }, { fail() }, { fail() })
        gate.request(1, "http://example.com", listOf(SiteCapability.LOCATION), false, repo(), { true }, replies::add)
        gate.request(2, "https://example.com", listOf(SiteCapability.LOCATION), false, repo(), { current }, replies::add)
        current = false
        gate.respond(true, true)
        yield()
        assertEquals(listOf(false, false), replies)
        assertEquals(SitePermission.ASK, repo().get("https://example.com").location)
    }

    @Test fun approximateAndroidLocationIsEnoughAndOsDenialDoesNotBlockTheWebsite() = runBlocking {
        val granted = mutableSetOf<String>()
        val requested = mutableListOf<String>()
        val repository = repo()
        val gate = WebsitePermissions(this, granted::contains, { requested += it }, { fail() })
        val allowed = CompletableDeferred<Boolean>()
        gate.request(1, "https://example.com", listOf(SiteCapability.LOCATION), true, repository.privateSession(), { true }) { allowed.complete(it) }
        gate.respond(true, false)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in requested)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in requested)
        granted += Manifest.permission.ACCESS_COARSE_LOCATION
        gate.onRuntimeResult()
        assertTrue(allowed.await())
        val denied = CompletableDeferred<Boolean>()
        gate.request(2, "https://example.com", listOf(SiteCapability.CAMERA), false, repository, { true }) { denied.complete(it) }
        gate.respond(true, true)
        gate.onRuntimeResult()
        assertFalse(denied.await())
        assertEquals(SitePermission.ASK, repository.get("https://example.com").camera)
    }
}
