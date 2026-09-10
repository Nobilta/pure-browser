package com.mybrowser.core

import com.mybrowser.site.SitePermission

/** Monotonic-clock budget belongs to a tab, and is not reset by a briefly loaded page. */
class RendererRecovery(private val windowMs: Long = 60_000, private val autoRetries: Int = 2) {
    private data class Failures(var first: Long, var count: Int)
    private val failures = LinkedHashMap<String, Failures>()
    fun shouldReload(tab: String, now: Long): Boolean {
        val state = failures[tab]?.takeIf { now - it.first in 0 until windowMs }
            ?: Failures(now, 0).also { failures[tab] = it }
        while (failures.size > 32) failures.remove(failures.keys.first())
        return ++state.count <= autoRetries
    }
    fun retry(tab: String) { failures.remove(tab) }
}

class ExternalNavigationGate {
    enum class Decision { ASK, OPEN, BLOCK }
    private var lastRequestAt: Long? = null
    private val recent = ArrayDeque<Long>()

    /** Even an allowed site cannot continuously steal focus; no page-controlled timer resets this. */
    fun decide(permission: SitePermission, hasGesture: Boolean, mainFrame: Boolean,
        promptOpen: Boolean, now: Long): Decision {
        if (permission == SitePermission.BLOCK || (!mainFrame && !hasGesture) || promptOpen) return Decision.BLOCK
        if (lastRequestAt?.let { now - it in 0 until 2_500 } == true) return Decision.BLOCK
        while (recent.isNotEmpty() && now - recent.first() >= 30_000) recent.removeFirst()
        if (recent.size >= 3) return Decision.BLOCK
        lastRequestAt = now
        recent.addLast(now)
        return if (permission == SitePermission.ALLOW) Decision.OPEN else Decision.ASK
    }
}

enum class PageFailureKind { NETWORK, RENDERER }
data class PageFailure(val url: String, val kind: PageFailureKind, val details: String = "")
