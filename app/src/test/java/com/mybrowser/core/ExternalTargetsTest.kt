package com.mybrowser.core

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The prompt names an app only when exactly one would open; ambiguity stays unnamed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalTargetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `single handler is named by its application label`() {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            matchIntent(),
            listOf(resolveInfo("com.example.bank", "中国银行")),
        )

        assertEquals("中国银行", ExternalTargets.labelOf(context, URL))
    }

    @Test
    fun `several handlers stay unnamed so the system picker decides`() {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            matchIntent(),
            listOf(
                resolveInfo("com.example.bank", "银行 A"),
                resolveInfo("com.example.other", "银行 B"),
            ),
        )

        assertNull(ExternalTargets.labelOf(context, URL))
    }

    @Test
    fun `no registered handler has no name`() {
        assertNull(ExternalTargets.labelOf(context, URL))
    }

    @Test
    fun `a scheme that must stay in the browser is never named`() {
        assertNull(ExternalTargets.labelOf(context, "file:///sdcard/secret.txt"))
    }

    @Test
    fun `an intent that wraps a web url stays in this browser`() {
        val prepared = ExternalIntentHandler.prepare(
            "intent://example.com/#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fexample.com;end",
        )
        assertTrue(prepared is ExternalIntentHandler.Prepared.Refused)
        assertEquals("https://example.com/", (prepared as ExternalIntentHandler.Prepared.Refused).fallbackUrl)
    }

    private fun matchIntent(): Intent =
        Intent(Intent.ACTION_VIEW, URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun resolveInfo(packageName: String, label: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Open"
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        }
    }

    private companion object {
        const val URL = "bocmbankpsn://startwith/N26K365Mci"
    }
}
