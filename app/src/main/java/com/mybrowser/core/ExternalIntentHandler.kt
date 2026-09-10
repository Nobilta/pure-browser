package com.mybrowser.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import java.net.URISyntaxException

/**
 * Handing non-web URLs to other apps.
 *
 * This is the single most security-sensitive function in a WebView browser, and the
 * naive implementation is a component-hijacking hole. `Intent.parseUri` with
 * [Intent.URI_INTENT_SCHEME] honours an explicit `component=` in the URL, so
 *
 *     intent://x#Intent;component=com.victim/.SomeExportedActivity;...;end
 *
 * would let any page start any exported component of any installed app with attacker-
 * controlled extras. The mitigations below mirror what Chrome does: strip the explicit
 * component and selector, force the BROWSABLE category, drop URI-permission grant flags,
 * and refuse to target ourselves.
 */
object ExternalIntentHandler {

    private const val TAG = "ExternalIntent"

    /** Chrome's documented escape hatch: a web URL to use when the app is absent. */
    private const val EXTRA_FALLBACK_URL = "browser_fallback_url"

    sealed interface Result {
        /** Launched an external app. */
        data object Launched : Result

        /** Nothing could handle it; load this URL in the WebView instead. */
        data class Fallback(val url: String) : Result

        /** Refused, and there is no fallback. Caller should stay put. */
        data object Rejected : Result
    }

    fun handle(context: Context, url: String): Result {
        if (!UrlUtils.isAllowedExternalScheme(url)) {
            Log.i(TAG, "scheme not allow-listed: ${UrlUtils.schemeOf(url)}")
            return Result.Rejected
        }

        val intent = parse(url) ?: return Result.Rejected
        // Intent fallbacks are page-controlled data. Only ordinary web URLs may be
        // loaded here; accepting javascript:, data:, or content: would turn an absent
        // external app into a script/opaque-URL execution primitive.
        val fallback = intent.getStringExtra(EXTRA_FALLBACK_URL)
            ?.takeIf {
                val scheme = UrlUtils.schemeOf(it)
                scheme == "http" || scheme == "https"
            }

        sanitize(intent)

        if (intent.resolveActivity(context.packageManager)?.packageName == context.packageName) {
            return fallback?.let(Result::Fallback) ?: Result.Rejected
        }

        return try {
            context.startActivity(intent)
            Result.Launched
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "no handler for ${UrlUtils.schemeOf(url)}", e)
            when {
                fallback != null -> Result.Fallback(fallback)
                // market:// is worth one more try via the Play web UI.
                UrlUtils.schemeOf(url) == "market" -> {
                    val id = url.toUri().getQueryParameter("id")
                    if (id != null) {
                        Result.Fallback("https://play.google.com/store/apps/details?id=$id")
                    } else {
                        Result.Rejected
                    }
                }
                else -> Result.Rejected
            }
        } catch (e: SecurityException) {
            // A target that requires a permission we lack.
            Log.w(TAG, "security exception launching intent", e)
            Result.Rejected
        }
    }

    private fun parse(url: String): Intent? = try {
        if (UrlUtils.schemeOf(url) == "intent") {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, url.toUri())
        }
    } catch (e: URISyntaxException) {
        Log.w(TAG, "unparseable intent url", e)
        null
    }

    /**
     * Removes everything a page must not be allowed to control.
     *
     * Order matters: the component must be cleared before any resolution attempt, or a
     * resolve would confirm the attacker's target rather than the system's choice.
     */
    private fun sanitize(intent: Intent) {
        intent.action = Intent.ACTION_VIEW
        // The two hijacking vectors.
        intent.component = null
        intent.selector = null

        // Only activities a browser is allowed to open. An app that has not opted into
        // BROWSABLE has not agreed to be reachable from web content.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)

        // Not our job to hand out access to our own content providers.
        intent.flags = intent.flags and
            Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION.inv()

        // A page may also encode an explicit package without a component. Keeping it
        // would let the page pin resolution to an arbitrary installed app and bypass
        // the normal resolver policy. Let Android resolve the sanitized BROWSABLE intent.
        intent.`package` = null

        // Launching from a WebView callback, not from an Activity result chain.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Extras a page has no business setting on an outbound intent.
        intent.removeExtra(EXTRA_FALLBACK_URL)
    }
}
