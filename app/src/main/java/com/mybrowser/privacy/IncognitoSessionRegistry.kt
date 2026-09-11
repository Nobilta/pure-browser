package com.mybrowser.privacy

import java.util.UUID

/** Never reuses a retired session, even when WebView refuses to delete a loaded profile. */
internal class IncognitoSessionRegistry(
    private val profileNames: () -> Collection<String>,
    private val deleteProfile: (String) -> Boolean,
) {
    var activeName: String? = null
        private set

    fun begin(): String {
        activeName?.let { return it }
        cleanupStale()
        return (PREFIX + UUID.randomUUID()).also { activeName = it }
    }

    fun end(): Boolean {
        val retired = activeName ?: return true
        // Retire before deletion: a Provider exception must never make this name reusable.
        activeName = null
        return delete(retired)
    }

    fun cleanupStale(): Boolean {
        val names = try { profileNames() } catch (_: RuntimeException) { return false }
        var complete = true
        names.filter { (it == LEGACY_NAME || it.startsWith(PREFIX)) && it != activeName }.forEach {
            if (!delete(it)) complete = false
        }
        return complete
    }

    private fun delete(name: String): Boolean = try {
        deleteProfile(name) // false means the profile was already absent.
        true
    } catch (_: IllegalStateException) {
        false // Loaded profiles can only be deleted by a later browser process.
    } catch (_: IllegalArgumentException) {
        false
    }

    private companion object {
        const val LEGACY_NAME = "incognito"
        const val PREFIX = "pure_private_"
    }
}
