package com.mybrowser.userscript

/**
 * What the userscript layer is doing for the page that is on screen right now.
 *
 * Scripts are silent by construction — they run inside the document and the browser has nothing to
 * show for them — so "did anything happen?" has no answer from the UI today apart from a script's
 * own visible effect. This is that answer: who was registered for this document, and why nothing
 * was when nothing was.
 */
data class UserScriptStatus(
    /** Names of the scripts registered for the current document, in install order. */
    val running: List<String> = emptyList(),

    /**
     * Ids of the same scripts.
     *
     * Two scripts may share a name — nothing stops it — so a per-script verdict has to key off the
     * id. [running] stays as it is because it is what the menu shows.
     */
    val runningIds: Set<String> = emptySet(),
    /**
     * Scripts installed and enabled that did not match this page. Counted, not listed: the reason
     * to show this is "the extension is installed but this page is not covered", and the settings
     * sheet is where the individual rules live.
     */
    val installedNotMatching: Int = 0,
    /** Installed and enabled, but held back because this WebView's runtime is incomplete. */
    val unsupportedRuntime: Int = 0,
    /**
     * True while browsing privately. Scripts do run here — what is private is the data they save,
     * which the store keeps in memory for the session and drops when it ends.
     */
    val incognito: Boolean = false,
    /** The URL this status describes; a stale status must never be shown for another page. */
    val url: String = "",
)
