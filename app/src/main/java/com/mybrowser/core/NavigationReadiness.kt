package com.mybrowser.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes deferred navigation at the privacy and document-start initialization boundaries.
 * Each boundary keeps only its latest request. The owning Activity supplies lifecycleScope,
 * so destroying the window also cancels both waits.
 */
internal class NavigationReadiness(
    private val scope: CoroutineScope,
    private val privacyReady: () -> Boolean,
    private val preparePrivacy: suspend () -> Boolean,
    private val scriptsReady: () -> Boolean,
    private val prepareScripts: suspend () -> Unit,
    private val refreshScripts: () -> Unit,
) {
    private var privacyJob: Job? = null
    private var scriptJob: Job? = null

    fun awaitPrivacy(action: () -> Unit): Boolean {
        if (privacyReady()) return false
        privacyJob?.cancel()
        privacyJob = scope.launch {
            if (preparePrivacy()) action()
        }
        return true
    }

    fun awaitScripts(action: () -> Unit): Boolean {
        if (scriptsReady()) {
            refreshScripts()
            return false
        }
        cancelScripts()
        scriptJob = scope.launch {
            prepareScripts()
            refreshScripts()
            action()
        }
        return true
    }

    /** A pending script load belongs to the profile being left. */
    fun cancelScripts() {
        scriptJob?.cancel()
        scriptJob = null
    }
}
