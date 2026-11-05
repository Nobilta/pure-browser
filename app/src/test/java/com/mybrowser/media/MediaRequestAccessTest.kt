package com.mybrowser.media

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaRequestAccessTest {
    @Test fun certificateApprovalRequiresTheExactCertificateAndOriginAndIsClearedWithTheSession() {
        fun certificate(name: String) = javaClass.getResourceAsStream("/media/$name.pem")!!.use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
        }
        val approved = certificate("approved")
        val session = MediaRequestAccess.Session()
        val url = "https://media.test/video.mp4"
        assertFalse(session.acceptsCertificate(url, approved))
        session.approveCertificate(url, approved)
        assertTrue(session.acceptsCertificate("https://media.test:443/next.mp4", certificate("approved")))
        assertFalse(session.acceptsCertificate(url, certificate("replacement")))
        assertFalse(session.acceptsCertificate("https://other.test/video.mp4", approved))
        assertFalse(session.acceptsCertificate("https://media.test:444/video.mp4", approved))
        assertFalse(session.acceptsCertificate("http://media.test/video.mp4", approved))
        session.clear()
        assertFalse(session.acceptsCertificate(url, approved))
    }

    @Test fun nativeLoginCanAuthorizeOnlyTheChallengedMediaUrl() {
        val access = MediaRequestAccess()
        val url = "https://media.test/video.mp4?token=1"
        access.challenge(url, "Basic realm=\"Video\", charset=\"UTF-8\"", true)
        assertNull(access.authenticate("other.test", "Video", "test", "pure"))
        assertNull(access.authenticate("media.test", "Wrong realm", "test", "pure"))
        assertEquals(MediaRequestAccess.AuthenticatedRequest.DOCUMENT, access.authenticate("media.test", "Video", "用户", "密码"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("用户:密码".toByteArray()), access.authorization(url))
        assertNull(access.authorization("https://media.test/other.mp4"))
        assertNull(access.authorization("http://media.test/video.mp4?token=1"))
        assertNull(access.authorization(url + "changed"))
        assertNull(MediaRequestAccess().authorization(url))
        access.challenge(url, "Digest realm=\"Video\", nonce=\"opaque\"", true)
        assertNull(access.authenticate("media.test", "Video", "test", "pure"))
        val next = MediaRequestAccess().also { it.session = access.session }
        next.challenge("https://media.test/next.mp4", "Basic realm=\"Video\", charset=UTF-8", true)
        assertTrue(next.useCachedLogin())
        assertNotNull(next.authorization("https://media.test/next.mp4"))
        next.challenge("https://other.test/next.mp4", "Basic realm=\"Video\"", true)
        assertFalse(next.useCachedLogin())
        access.session.clear()
        assertNull(access.authorization(url))
        assertNull(next.authorization("https://media.test/next.mp4"))
    }
}
