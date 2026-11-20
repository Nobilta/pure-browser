package com.mybrowser.download

/** How a transfer that is starting or restarting obtains its credentials. */
enum class CookiePolicy {
    /**
     * Read the browser's cookie jar as the transfer starts, so a retry uses whatever the user is
     * signed in as right now.
     */
    READ_FROM_COOKIE_JAR,

    /**
     * Use only the cookie the session captured in memory when the task was created. A private
     * task must never pick up the ordinary profile's credentials, and the captured value is what
     * the session's own WebView was using.
     */
    SESSION_ONLY,
}

/**
 * Who a download belongs to.
 *
 * Downloads are managed by one list, one pause/retry/cancel path and one transfer engine whatever
 * the mode; what differs is the identity a transfer runs as, and every policy that follows from
 * it. This replaces a single boolean (`autoResumeAllowed`) that stood in for all of them at once,
 * which left each difference spelled as another comparison of the same flag.
 *
 * The name is deliberately not "public": records and files of a private-session download are kept
 * and stay visible in the one downloads list, so the ordinary identity says nothing about
 * visibility. [Normal] is the identity of browsing outside a private session.
 */
sealed interface DownloadIdentity {

    /** True for a session whose tasks must not leave traces or borrow another identity. */
    val isPrivate: Boolean

    /** How a (re)started transfer obtains credentials; see [CookiePolicy]. */
    val cookiePolicy: CookiePolicy

    /**
     * Whether the persisted record keeps the download URL and the page it was started from.
     * A private record keeps neither: the session ends, the record does not, and an address left
     * on disk is a trace even without its query. A page-file record is the exception — its
     * placeholder is what keeps the record recognisable after a restart.
     */
    val persistSourceUrl: Boolean

    /**
     * Whether a notification may name the file.
     *
     * Both modes announce a finished download, but a private task's notification outlives the
     * session that caused it and is readable from the lock screen, so it stays generic there.
     */
    val showsFilenameInNotification: Boolean

    /** Whether a task interrupted by a process exit is resumed on the next launch. */
    val resumesAfterRestart: Boolean

    /**
     * Whether a task that ran as this identity may be resumed by [current] — the session running
     * now.
     *
     * A private task belongs to the session that created it: resuming it elsewhere would use that
     * session's cookies and, because a private record keeps no address, no fetchable location.
     * Any other session therefore refuses, including ordinary browsing, which is also what a
     * record written before scopes existed compares against.
     */
    fun canResumeIn(current: DownloadIdentity): Boolean

    /** Browsing outside a private session. */
    data object Normal : DownloadIdentity {
        override val isPrivate = false
        override val cookiePolicy = CookiePolicy.READ_FROM_COOKIE_JAR
        override val persistSourceUrl = true
        override val showsFilenameInNotification = true
        override val resumesAfterRestart = true
        override fun canResumeIn(current: DownloadIdentity) = true
    }

    /**
     * A private session.
     *
     * [scope] is empty only for a record migrated from a build that did not record one; no live
     * session can hold that value, so such a task is viewable and deletable but never resumed —
     * its session's cookies and its address are both gone.
     */
    data class PrivateSession(val scope: String) : DownloadIdentity {
        override val isPrivate = true
        override val cookiePolicy = CookiePolicy.SESSION_ONLY
        override val persistSourceUrl = false
        override val showsFilenameInNotification = false
        override val resumesAfterRestart = false

        // An empty scope means the session was never recorded, so there is no session whose
        // cookies and URL this task could be restarted with. Stated here rather than left to the
        // convention that no live session holds that value: the rule then holds for every pair,
        // including the placeholder compared with itself.
        override fun canResumeIn(current: DownloadIdentity) = scope.isNotEmpty() && current == this
    }

    companion object {
        /** Longest scope a persisted record may carry. */
        const val MAX_SCOPE_LENGTH = 128

        /** The scope of a session that was never recorded; see [PrivateSession]. */
        const val UNRECORDED_SCOPE = ""

        /** The identity of a private task whose session is not recorded. */
        val UNRECORDED = PrivateSession(UNRECORDED_SCOPE)

        private const val NORMAL_TOKEN = "normal"
        private const val PRIVATE_PREFIX = "private:"

        /**
         * A scope for a new private session.
         *
         * Task identity is decided by equality of these strings, so uniqueness carries the whole
         * boundary: a repeated or empty value would make one session's download resumable in
         * another. Random rather than clock- or counter-derived: those reset, `nanoTime` from the
         * device's uptime, so a session after a reboot could reproduce an earlier offset and a
         * record from that session would then match the new one exactly.
         */
        fun nextScope(): String = java.util.UUID.randomUUID().toString()

        /** The value written for [identity] into a persisted record. */
        fun encode(identity: DownloadIdentity): String = when (identity) {
            Normal -> NORMAL_TOKEN
            is PrivateSession -> PRIVATE_PREFIX + identity.scope
        }

        /** Reads the identity field, or null when the value is unusable and must be migrated. */
        fun decode(raw: String?): DownloadIdentity? {
            val value = raw?.takeIf { it.isNotEmpty() && it.length <= PRIVATE_PREFIX.length + MAX_SCOPE_LENGTH }
                ?: return null
            if (value == NORMAL_TOKEN) return Normal
            if (!value.startsWith(PRIVATE_PREFIX)) return null
            return PrivateSession(value.removePrefix(PRIVATE_PREFIX))
        }
    }
}
