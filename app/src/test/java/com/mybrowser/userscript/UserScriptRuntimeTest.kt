package com.mybrowser.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registration, fallback support and status must agree with what each document can execute. */
class UserScriptRuntimeTest {

    @Test fun oldProvidersCanExecutePlainScriptsWithoutRegisteringThem() {
        assertFalse(UserScriptRuntime.runnableOn(false, false, false))
        assertTrue(UserScriptRuntime.executionSupported(false, false, false))
        assertFalse(UserScriptRuntime.executionSupported(true, false, false))
        assertFalse(UserScriptRuntime.executionSupported(true, true, false))
        assertTrue(UserScriptRuntime.executionSupported(true, true, true))
    }

    @Test fun aPlainScriptNeedsDocumentStartOnly() {
        assertTrue(UserScriptRuntime.runnableOn(needsStorage = false, documentStart = true, bridge = false))
        assertFalse(UserScriptRuntime.runnableOn(needsStorage = false, documentStart = false, bridge = true))
    }

    @Test fun aPrivilegedScriptNeedsTheMessageBridgeAsWell() {
        // The case the two copies of this rule disagreed about: document-start alone cannot serve a
        // script that needs GM storage, because the bridge it writes through is not there.
        assertFalse(UserScriptRuntime.runnableOn(needsStorage = true, documentStart = true, bridge = false))
        assertTrue(UserScriptRuntime.runnableOn(needsStorage = true, documentStart = true, bridge = true))
        assertFalse(UserScriptRuntime.runnableOn(needsStorage = true, documentStart = false, bridge = true))
    }

    @Test fun aRegistrationMadeDuringTheLoadIsNotCreditedToIt() {
        // The snapshot is taken when the document starts. A script enabled mid-load gets a
        // registration, but this document never received it, and reporting it as running here is the
        // answer a user reads while the page shows no sign of it.
        val atStart = setOf("installed")
        val candidates = listOf("installed" to true, "enabled-mid-load" to true)
        assertEquals(setOf("installed"), UserScriptRuntime.registeredFor(candidates, atStart))
    }

    @Test fun onlyScriptsWhoseRulesCoverThePageAreCredited() {
        val snapshot = setOf("covered", "not-covered")
        val candidates = listOf("covered" to true, "not-covered" to false)
        assertEquals(setOf("covered"), UserScriptRuntime.registeredFor(candidates, snapshot))
    }
}
