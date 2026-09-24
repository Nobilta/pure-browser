package com.mybrowser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity is the only place a mode-dependent download policy is decided, so the matrix is
 * pinned here rather than through the manager: each row is a policy, each column an identity.
 */
class DownloadIdentityTest {

    @Test
    fun eachIdentityAnswersEveryPolicy() {
        val normal = DownloadIdentity.Normal
        assertFalse(normal.isPrivate)
        assertEquals(CookiePolicy.READ_FROM_COOKIE_JAR, normal.cookiePolicy)
        assertTrue(normal.persistSourceUrl)
        assertTrue(normal.notifyOnCompletion)
        assertTrue(normal.showsFilenameInNotification)
        assertTrue(normal.resumesAfterRestart)

        val session = DownloadIdentity.PrivateSession("7-1")
        assertTrue(session.isPrivate)
        assertEquals(CookiePolicy.SESSION_ONLY, session.cookiePolicy)
        assertFalse(session.persistSourceUrl)
        assertFalse(session.notifyOnCompletion)
        assertFalse(session.showsFilenameInNotification)
        assertFalse(session.resumesAfterRestart)
    }

    @Test
    fun aNormalTaskIsResumableFromAnywhereAndAPrivateOneOnlyFromItsOwnSession() {
        val normal = DownloadIdentity.Normal
        val first = DownloadIdentity.PrivateSession("7-1")
        val second = DownloadIdentity.PrivateSession("9-1")

        assertTrue(normal.canResumeIn(normal))
        assertTrue(normal.canResumeIn(first))

        assertTrue(first.canResumeIn(first))
        assertFalse(first.canResumeIn(second))
        assertFalse(first.canResumeIn(normal))
        assertFalse(second.canResumeIn(first))
    }

    /**
     * A record migrated from a build that did not record a scope carries the placeholder. Nothing
     * resumes it, which is the point: its session's cookies are gone and its persisted URL has no
     * query left to fetch. This is the case an earlier guard got wrong by comparing two absent
     * scopes for equality.
     */
    @Test
    fun aTaskWhoseSessionIsNotRecordedIsNeverResumed() {
        val unrecorded = DownloadIdentity.UNRECORDED
        assertTrue(unrecorded.isPrivate)
        assertFalse(unrecorded.resumesAfterRestart)
        assertFalse(unrecorded.canResumeIn(DownloadIdentity.Normal))
        assertFalse(unrecorded.canResumeIn(unrecorded))
        assertFalse(unrecorded.canResumeIn(DownloadIdentity.PrivateSession(DownloadIdentity.nextScope())))
    }

    /**
     * Scope equality is what decides whether a session may resume a task, so a repeated or empty
     * scope would silently merge two sessions. The generator is the only source of live scopes.
     */
    @Test
    fun generatedScopesAreUniqueAndNeverEmpty() {
        val scopes = List(2_000) { DownloadIdentity.nextScope() }
        assertTrue("a scope must not be empty", scopes.none { it.isEmpty() })
        assertEquals("scopes must be unique", scopes.size, scopes.toSet().size)
        assertTrue(scopes.none { it == DownloadIdentity.UNRECORDED_SCOPE })
    }

    @Test
    fun thePersistedFormRoundTripsAndRejectsWhatCannotBeRead() {
        val normal = DownloadIdentity.Normal
        val session = DownloadIdentity.PrivateSession("7-1")
        assertEquals(normal, DownloadIdentity.decode(DownloadIdentity.encode(normal)))
        assertEquals(session, DownloadIdentity.decode(DownloadIdentity.encode(session)))

        // Absent or unusable values are the signal to fall back to the legacy pair.
        assertNull(DownloadIdentity.decode(null))
        assertNull(DownloadIdentity.decode(""))
        assertNull(DownloadIdentity.decode("PRIVATE:7-1"))
        assertNull(DownloadIdentity.decode("private"))
        assertNull(DownloadIdentity.decode("something-else"))
        assertNull(
            "an oversized scope is not accepted",
            DownloadIdentity.decode("private:" + "s".repeat(DownloadIdentity.MAX_SCOPE_LENGTH + 1)),
        )
        // The placeholder is a value the format can express, so it survives a round trip.
        assertEquals(DownloadIdentity.UNRECORDED, DownloadIdentity.decode(DownloadIdentity.encode(DownloadIdentity.UNRECORDED)))
    }
}
