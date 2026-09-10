package com.mybrowser.security

import com.mybrowser.R
import android.net.http.SslCertificate
import androidx.core.net.toUri

data class SecurityInfo(
    val isSecure: Boolean,
    val protocol: String,
    val hasWarnings: Boolean = false,
    val warningRes: Int? = null,
    val warningArgs: List<Any> = emptyList()
)

/** Immutable snapshot of the TLS certificate currently exposed by WebView. */
data class CertificateDetails(
    val subjectCommonName: String?,
    val subjectOrganization: String?,
    val subjectOrganizationalUnit: String?,
    val subjectDistinguishedName: String?,
    val issuerCommonName: String?,
    val issuerOrganization: String?,
    val issuerOrganizationalUnit: String?,
    val issuerDistinguishedName: String?,
    val validFromMillis: Long?,
    val validUntilMillis: Long?,
    val isCurrentlyValid: Boolean?,
)

object SecurityChecker {
    /** Copies platform certificate fields so the dialog never holds a live WebView object. */
    fun certificateDetails(
        certificate: SslCertificate?,
        nowMillis: Long = System.currentTimeMillis(),
    ): CertificateDetails? {
        certificate ?: return null
        val validFrom = runCatching { certificate.validNotBeforeDate?.time }.getOrNull()
        val validUntil = runCatching { certificate.validNotAfterDate?.time }.getOrNull()
        val issuedTo = certificate.issuedTo
        val issuedBy = certificate.issuedBy
        return CertificateDetails(
            subjectCommonName = issuedTo.cName.cleanCertificateField(),
            subjectOrganization = issuedTo.oName.cleanCertificateField(),
            subjectOrganizationalUnit = issuedTo.uName.cleanCertificateField(),
            subjectDistinguishedName = issuedTo.dName.cleanCertificateField(),
            issuerCommonName = issuedBy.cName.cleanCertificateField(),
            issuerOrganization = issuedBy.oName.cleanCertificateField(),
            issuerOrganizationalUnit = issuedBy.uName.cleanCertificateField(),
            issuerDistinguishedName = issuedBy.dName.cleanCertificateField(),
            validFromMillis = validFrom,
            validUntilMillis = validUntil,
            isCurrentlyValid = if (validFrom != null && validUntil != null) {
                nowMillis in validFrom..validUntil
            } else {
                null
            },
        )
    }

    fun getSecurityInfo(url: String?, certificateError: Boolean = false): SecurityInfo {
        if (url.isNullOrEmpty()) {
            return SecurityInfo(
                isSecure = false,
                protocol = "none",
                hasWarnings = true,
                warningRes = R.string.ui_invalid_url
            )
        }

        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()

        return when (scheme) {
            "https" -> SecurityInfo(
                isSecure = !certificateError,
                protocol = "HTTPS",
                hasWarnings = certificateError,
                warningRes = if (certificateError) R.string.ui_ssl_certificate_verification_failed else null,
            )
            "http" -> SecurityInfo(
                isSecure = false,
                protocol = "HTTP",
                hasWarnings = true,
                warningRes = R.string.ui_this_connection_is_insecure_your_information_could_be
            )
            "file" -> SecurityInfo(
                isSecure = false,
                protocol = "FILE",
                hasWarnings = true,
                warningRes = R.string.ui_this_is_a_local_file_not_an_encrypted
            )
            "data" -> SecurityInfo(
                isSecure = false,
                protocol = "DATA",
                hasWarnings = true,
                warningRes = R.string.ui_this_is_embedded_data_not_a_verifiable_network
            )
            else -> SecurityInfo(
                isSecure = false,
                protocol = scheme?.uppercase() ?: "UNKNOWN",
                hasWarnings = true,
                warningRes = R.string.ui_insecure_protocol,
                warningArgs = listOf(scheme ?: "UNKNOWN")
            )
        }
    }

    fun getSecurityLevel(url: String?, certificateError: Boolean = false): SecurityLevel {
        val info = getSecurityInfo(url, certificateError)
        return when {
            info.isSecure && !info.hasWarnings -> SecurityLevel.SECURE
            info.isSecure && info.hasWarnings -> SecurityLevel.WARNING
            !info.isSecure && info.protocol == "HTTP" -> SecurityLevel.INSECURE
            else -> SecurityLevel.DANGEROUS
        }
    }
}

private fun String?.cleanCertificateField(): String? = this
    ?.filterNot { it.isISOControl() }
    ?.trim()
    ?.take(2_048)
    ?.takeIf { it.isNotEmpty() }

enum class SecurityLevel {
    SECURE,      // 🔒 Green - HTTPS, no issues
    WARNING,     // ⚠️ Yellow - HTTPS but with warnings
    INSECURE,    // 🔓 Gray - HTTP
    DANGEROUS    // 🛑 Red - Dangerous protocol or SSL error
}
