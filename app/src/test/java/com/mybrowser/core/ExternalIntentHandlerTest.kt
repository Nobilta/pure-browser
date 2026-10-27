package com.mybrowser.core

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalIntentHandlerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Shadows.shadowOf(context).checkActivities(true)
    }

    @Test
    fun `intent uri keeps an app scheme but strips the page-chosen component and package`() {
        Shadows.shadowOf(context).checkActivities(false)
        val result = ExternalIntentHandler.handle(
            context,
            "intent://scan/#Intent;scheme=evilapp;package=com.example.evil;end",
        )
        assertEquals(ExternalIntentHandler.Result.Launched, result)
        val started = Shadows.shadowOf(context).nextStartedActivity
        assertEquals("evilapp", started.data?.scheme)
        assertNull(started.component)
        assertNull(started.`package`)
        assertTrue(started.categories?.contains(android.content.Intent.CATEGORY_BROWSABLE) == true)
    }

    @Test
    fun `intent uri with a blocked inner scheme uses valid http fallback`() {
        val result = ExternalIntentHandler.handle(
            context,
            "intent://scan/#Intent;scheme=file;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Finstall;end",
        )
        assertEquals(ExternalIntentHandler.Result.Fallback("https://example.com/install"), result)
    }

    @Test
    fun `arbitrary app scheme is launched after sanitising`() {
        Shadows.shadowOf(context).checkActivities(false)
        val result = ExternalIntentHandler.handle(context, "bocmbankpsn://startwith/N26K365Mci")
        assertEquals(ExternalIntentHandler.Result.Launched, result)
        val started = Shadows.shadowOf(context).nextStartedActivity
        assertEquals("bocmbankpsn://startwith/N26K365Mci", started.data.toString())
        assertNull(started.component)
        assertNull(started.`package`)
    }

    @Test
    fun `blocked scheme is rejected without a fallback`() {
        assertEquals(
            ExternalIntentHandler.Result.Rejected,
            ExternalIntentHandler.handle(context, "file:///sdcard/private.txt"),
        )
    }

    @Test
    fun `intent uri with non-http fallback url ignores fallback`() {
        // javascript: is a blocked inner scheme, and the page may only fall back to http(s).
        val result = ExternalIntentHandler.handle(
            context,
            "intent://scan/#Intent;scheme=javascript;S.browser_fallback_url=javascript%3Aalert(1);end",
        )
        assertEquals(ExternalIntentHandler.Result.Rejected, result)
    }

    @Test
    fun `opaque market uri extracts package id without throwing UnsupportedOperationException`() {
        val result = ExternalIntentHandler.handle(
            context,
            "market:details?id=com.example.app&referrer=utm_source%3Dtest",
        )
        assertEquals(
            ExternalIntentHandler.Result.Fallback("https://play.google.com/store/apps/details?id=com.example.app"),
            result,
        )
    }

    @Test
    fun `hierarchical market uri extracts package id for play store web fallback`() {
        val result = ExternalIntentHandler.handle(
            context,
            "market://details?id=com.example.app",
        )
        assertEquals(
            ExternalIntentHandler.Result.Fallback("https://play.google.com/store/apps/details?id=com.example.app"),
            result,
        )
        assertFalse(result is ExternalIntentHandler.Result.Launched)
    }

    @Test
    fun `encoded separator inside an earlier market parameter is not read as a second id`() {
        // The opaque form's `schemeSpecificPart` comes back already decoded, so reading it would
        // turn the `%26id%3D` above into a real `&id=` and hand the Play fallback a package the
        // link never named.
        val result = ExternalIntentHandler.handle(
            context,
            "market:details?referrer=utm_source%3Dtest%26id%3Dcom.evil.app&id=com.real.app",
        )
        assertEquals(
            ExternalIntentHandler.Result.Fallback("https://play.google.com/store/apps/details?id=com.real.app"),
            result,
        )
    }

    @Test
    fun `market package id is percent-decoded exactly once`() {
        // `%2525` is an encoded `%25`; decoding twice would leave a bare `%` that the Play URL
        // encoder then turns into `%25` again, so the id it opens is not the one that was named.
        val result = ExternalIntentHandler.handle(context, "market:details?id=com.example.app%2525x")
        assertEquals(
            ExternalIntentHandler.Result.Fallback("https://play.google.com/store/apps/details?id=com.example.app%2525x"),
            result,
        )
    }
}
