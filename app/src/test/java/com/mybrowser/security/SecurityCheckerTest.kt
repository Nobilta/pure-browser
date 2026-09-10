package com.mybrowser.security

import android.app.Application
import android.net.http.SslCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecurityCheckerTest {

    @Test
    fun `certificate snapshot exposes identity issuer and validity`() {
        val certificate = SslCertificate(
            "CN=www.example.com,O=Example Org,OU=Web",
            "CN=Example CA,O=Issuer Org,OU=Trust",
            Date(1_000L),
            Date(3_000L),
        )

        val details = SecurityChecker.certificateDetails(certificate, nowMillis = 2_000L)!!

        assertEquals("www.example.com", details.subjectCommonName)
        assertEquals("Example Org", details.subjectOrganization)
        assertEquals("Example CA", details.issuerCommonName)
        assertEquals(1_000L, details.validFromMillis)
        assertEquals(3_000L, details.validUntilMillis)
        assertEquals(true, details.isCurrentlyValid)
    }

    @Test
    fun `missing certificate has no stale details`() {
        assertNull(SecurityChecker.certificateDetails(null))
    }

    @Test
    fun `expired certificate is reported outside validity`() {
        val certificate = SslCertificate("CN=expired.example", "CN=CA", Date(1L), Date(2L))

        assertFalse(SecurityChecker.certificateDetails(certificate, nowMillis = 3L)!!.isCurrentlyValid!!)
    }

    @Test
    fun `overriding a certificate error does not become a secure HTTPS claim`() {
        val url = "https://untrusted.example/"
        assertEquals(SecurityLevel.SECURE, SecurityChecker.getSecurityLevel(url))
        assertEquals(SecurityLevel.DANGEROUS, SecurityChecker.getSecurityLevel(url, certificateError = true))
        val info = SecurityChecker.getSecurityInfo(url, certificateError = true)
        assertFalse(info.isSecure)
        assertEquals(true, info.hasWarnings)
        assertEquals("HTTPS", info.protocol)
    }

    @Test
    fun `SSL warning identity survives paths and reloads without affecting other origins`() {
        val warnings = CertificateWarnings()
        warnings.remember("https://EXAMPLE.com/a")
        assertEquals(true, warnings.contains("https://example.com:443/b?reload=1"))
        assertFalse(warnings.contains("https://sub.example.com/a"))
        assertFalse(warnings.contains("https://example.com:8443/a"))
        assertFalse(warnings.contains("http://example.com/a"))
        warnings.clear()
        assertFalse(warnings.contains("https://example.com/a"))
    }
}
