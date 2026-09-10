package com.mybrowser.site

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Site consent and Android permission are separate gates. Every callback completes once. */
class WebsitePermissions(
    private val scope: CoroutineScope,
    private val isGranted: (String) -> Boolean,
    private val launchRuntime: (Array<String>) -> Unit,
    private val onSaveError: () -> Unit,
) {
    data class Prompt(val origin: String, val capabilities: List<SiteCapability>, val privateSession: Boolean)
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    private class Pending(
        val key: Any, val prompt: Prompt, val repository: SiteSettingsRepository,
        val isCurrent: () -> Boolean, val reply: (Boolean) -> Unit,
    ) {
        @Volatile var done = false
        var remember = false
        fun finish(allowed: Boolean) {
            if (done) return
            done = true
            runCatching { reply(allowed && isCurrent()) }
        }
    }
    private var pending: Pending? = null
    private var runtimePending: Pending? = null

    fun request(key: Any, url: String, capabilities: List<SiteCapability>, privateSession: Boolean,
        repository: SiteSettingsRepository, isCurrent: () -> Boolean, reply: (Boolean) -> Unit) {
        val origin = SiteOrigin.of(url)
        if (origin == null || !SiteOrigin.canRequestPermission(origin) || capabilities.isEmpty() ||
            !isCurrent() || runtimePending != null) { reply(false); return }
        cancel()
        val request = Pending(key, Prompt(origin, capabilities.distinct(), privateSession), repository, isCurrent, reply)
        val settings = repository.get(origin)
        if (capabilities.any { settings.permission(it) == SitePermission.BLOCK }) { request.finish(false); return }
        pending = request
        if (capabilities.all { settings.permission(it) == SitePermission.ALLOW }) grantRuntime(request)
        else prompt = request.prompt
    }

    fun respond(allow: Boolean, remember: Boolean) {
        val request = pending ?: return
        if (prompt == null || runtimePending != null) return
        prompt = null
        if (!request.isCurrent()) { cancel(); return }
        request.remember = remember
        if (allow) grantRuntime(request) else complete(request, false)
    }

    private fun grantRuntime(request: Pending) {
        val missing = runtimePermissions(request.prompt.capabilities).filterNot(isGranted)
        if (missing.isEmpty()) complete(request, true) else {
            runtimePending = request
            runCatching { launchRuntime(missing.toTypedArray()) }.onFailure {
                runtimePending = null
                complete(request, false, saveChoice = false)
            }
        }
    }

    fun onRuntimeResult() {
        val request = runtimePending ?: return
        runtimePending = null
        val granted = runtimePermissions(request.prompt.capabilities).all(isGranted)
        // A failed Android prompt must not create a remembered website denial.
        complete(request, granted, saveChoice = granted)
    }

    private fun complete(request: Pending, allowed: Boolean, saveChoice: Boolean = true) {
        scope.launch {
            if (request.done || pending !== request || !request.isCurrent()) { request.finish(false); return@launch }
            var result = allowed
            if (request.remember && saveChoice) {
                try {
                    request.repository.update(request.prompt.origin) { current ->
                        if (request.done) current else request.prompt.capabilities.fold(current) { settings, capability ->
                            settings.withPermission(capability, if (allowed) SitePermission.ALLOW else SitePermission.BLOCK)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    request.finish(false)
                    throw cancelled
                } catch (_: Exception) { onSaveError(); result = false }
            }
            request.finish(result)
            if (pending === request) { pending = null; prompt = null }
        }
    }

    fun cancel(key: Any? = null) {
        val request = pending ?: return
        if (key != null && request.key != key) return
        request.finish(false)
        pending = null
        prompt = null
        // Keep the cancelled runtime token until Android returns; never apply that
        // result to a later site's request through the same ActivityResult launcher.
    }

    private fun runtimePermissions(capabilities: List<SiteCapability>): List<String> = capabilities.mapNotNull {
        when (it) {
            SiteCapability.CAMERA -> Manifest.permission.CAMERA
            SiteCapability.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            SiteCapability.LOCATION -> if (isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) null
                else Manifest.permission.ACCESS_FINE_LOCATION
            SiteCapability.PROTECTED_MEDIA -> null
        }
    }.let { permissions ->
        if (Manifest.permission.ACCESS_FINE_LOCATION in permissions) permissions + Manifest.permission.ACCESS_COARSE_LOCATION
        else permissions
    }
}
