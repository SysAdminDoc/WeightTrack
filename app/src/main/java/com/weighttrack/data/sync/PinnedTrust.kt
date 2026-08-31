package com.weighttrack.data.sync

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusting one server's own certificate, on top of everything the phone already trusts.
 *
 * A Nextcloud on somebody's own network usually has a certificate it signed itself, which nothing
 * on the phone has any reason to believe. The choices were to send their weights over plain HTTP,
 * which this app refuses, or to turn certificate checking off, which is worse than either. So the
 * person picks the certificate their server presents and this trusts that one, and only that one,
 * in addition to the public authorities.
 *
 * Deliberately additive rather than replacing: pinning a home server must not stop the phone
 * checking a public one properly, and somebody who later points sync at a hosted Nextcloud should
 * not silently be running with a weakened check.
 */
object PinnedTrust {

    /** What the phone already trusts, so a pinned certificate never replaces the public ones. */
    private fun systemTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** A trust manager for exactly one extra certificate. */
    private fun pinnedOnly(certificate: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("pinned", certificate)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    /** Reads a certificate somebody picked, or null when the file is not one. */
    fun certificateFrom(bytes: ByteArray): X509Certificate? = runCatching {
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrNull()

    /**
     * The public authorities, plus one certificate of somebody's own.
     *
     * A server is accepted if either would accept it. The pinned one is tried second so an
     * ordinary public certificate still goes through the checks it should.
     */
    fun trustManagerFor(certificate: X509Certificate): X509TrustManager {
        val system = systemTrustManager()
        val pinned = pinnedOnly(certificate)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (untrusted: CertificateException) {
                    // Not a public certificate. It is only accepted if it is the exact one the
                    // person picked, which is what makes this a pin rather than a way off.
                    try {
                        pinned.checkServerTrusted(chain, authType)
                    } catch (_: CertificateException) {
                        throw untrusted
                    }
                }
            }

            // Both sets, so a caller reading the accepted issuers sees the truth about what
            // this will accept rather than half of it.
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + pinned.acceptedIssuers
        }
    }

    /** The socket factory that goes with it. */
    fun socketFactoryFor(trustManager: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory
}
