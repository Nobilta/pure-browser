package com.mybrowser.media

import androidx.core.net.toUri
import com.mybrowser.tabs.MediaNavigation
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** User decisions scoped to their origin/challenge and current privacy session, never persisted. */
internal class MediaRequestAccess {
    /** Switching privacy sessions clears both credentials and certificate decisions. */
    class Session {
        private data class Credentials(val username: String, val password: String)
        private val credentials = ConcurrentHashMap<Pair<String, String>, Credentials>()
        private val certificates = ConcurrentHashMap<String, ApprovedCertificate>()
        fun rememberLogin(url: String, realm: String, username: String, password: String) {
            credentials[MediaNavigation.origin(url) to realm] = Credentials(username, password)
        }
        fun basic(url: String, realm: String, utf8: Boolean): String? = credentials[MediaNavigation.origin(url) to realm]?.let {
            if (':' in it.username) return null
            "Basic " + Base64.getEncoder().encodeToString("${it.username}:${it.password}".toByteArray(if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1))
        }
        fun approveCertificate(url: String, certificate: X509Certificate) {
            certificates[MediaNavigation.origin(url)] = ApprovedCertificate(certificate)
        }
        fun acceptsCertificate(url: String, certificate: X509Certificate): Boolean =
            certificates[MediaNavigation.origin(url)]?.matches(certificate) == true
        fun configure(connection: HttpsURLConnection) {
            certificates[MediaNavigation.origin(connection.url.toString())]?.configure(connection)
        }
        fun clear() { credentials.clear(); certificates.clear() }
    }
    var session = Session()
    private data class Challenge(val url: String, val realm: String, val utf8: Boolean)
    @Volatile private var challenge: Challenge? = null
    private val authorized = ConcurrentHashMap<String, Challenge>()

    fun challenge(url: String, header: String) {
        challenge = if (header.trimStart().startsWith("Basic ", true)) {
            val realm = REALM.find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            realm?.let { Challenge(url, it.replace("\\\"", "\"").replace("\\\\", "\\"),
                Regex("charset\\s*=\\s*\"?UTF-8", RegexOption.IGNORE_CASE).containsMatchIn(header)) }
        } else null
    }

    fun useCachedLogin(): Boolean {
        val pending = challenge ?: return false
        if (session.basic(pending.url, pending.realm, pending.utf8) == null) return false
        authorized[pending.url] = pending
        return true
    }

    fun authenticate(host: String, realm: String, username: String, password: String): Boolean {
        val pending = challenge ?: return false
        if (!pending.url.toUri().host.equals(host, true) || pending.realm != realm || ':' in username) return false
        session.rememberLogin(pending.url, realm, username, password)
        authorized[pending.url] = pending
        challenge = null
        return true
    }

    fun authorization(url: String): String? = authorized[url]?.let { session.basic(url, it.realm, it.utf8) }

    fun configure(connection: HttpsURLConnection) = session.configure(connection)

    private class ApprovedCertificate(certificate: X509Certificate) {
        private val encoded = certificate.encoded
        fun matches(certificate: X509Certificate) = MessageDigest.isEqual(encoded, certificate.encoded)
        fun configure(connection: HttpsURLConnection) {
            connection.sslSocketFactory = context.socketFactory
            val normalVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                normalVerifier.verify(host, session) || (host.equals(connection.url.host, true) &&
                    runCatching { matches(session.peerCertificates.first() as X509Certificate) }.getOrDefault(false))
            }
        }
        val context: SSLContext by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            val system = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        try { system.checkServerTrusted(chain, authType) }
                        catch (error: CertificateException) { if (chain.isEmpty() || !matches(chain[0])) throw error }
                    }
                }), null)
            }
        }
    }

    companion object {
        private val REALM = Regex("realm\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))", RegexOption.IGNORE_CASE)
    }
}
