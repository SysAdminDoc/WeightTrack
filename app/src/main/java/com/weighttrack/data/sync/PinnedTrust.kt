package com.weighttrack.data.sync

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusting one server's own certificate, and nothing else it might vouch for.
 *
 * A Nextcloud on somebody's own network usually has a certificate it signed itself, which nothing
 * on the phone has any reason to believe. The choices were to send their weights over plain HTTP,
 * which this app refuses, or to turn certificate checking off, which is worse than either. So the
 * person picks the certificate their server presents and this accepts that exact certificate.
 *
 * Exact is the whole point, and the first version of this got it wrong. Installing the picked
 * certificate as a trust anchor and letting the usual path building run against it means that
 * anybody who talks somebody into picking a certificate authority's file owns every host that
 * authority will sign for, on this connection and on any redirect from it. A person cannot read a
 * `.crt` and tell which kind they have been handed. So the leaf the server presents is compared
 * against the picked bytes and that is the whole test: no chain building, no delegation, and
 * nothing gained by handing over a certificate authority instead of a server.
 *
 * Additive: a server the public authorities already vouch for is accepted the ordinary way, so
 * pinning a home server cannot weaken the check on a hosted one.
 */
object PinnedTrust {

    /** What the phone already trusts, so a pinned certificate never replaces the public ones. */
    private fun systemTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** Reads a certificate somebody picked, or null when the file is not one. */
    fun certificateFrom(bytes: ByteArray): X509Certificate? = runCatching {
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrNull()

    /**
     * The public authorities, plus one exact certificate.
     *
     * The pinned one is checked only when the ordinary check has already refused, and it is
     * checked by comparing the encoded bytes of what the server actually presented. It is also
     * checked for expiry: a trust anchor's validity period is not verified by path building, so
     * a pin installed as an anchor outlives the certificate for ever, and a key stolen from a
     * server three years ago would still open the connection.
     */
    fun trustManagerFor(certificate: X509Certificate): X509TrustManager {
        val system = systemTrustManager()
        val pinned = certificate.encoded
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                // Said plainly rather than left to the platform, which throws an argument
                // error for this and not a certificate one.
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("the server presented no certificate")
                }
                try {
                    system.checkServerTrusted(chain, authType)
                    return
                } catch (untrusted: CertificateException) {
                    val presented = chain.first()
                    if (!MessageDigest.isEqual(presented.encoded, pinned)) throw untrusted
                    // The same bytes, so this is the server whose certificate was picked. It
                    // still has to be inside its own validity period.
                    presented.checkValidity()
                }
            }

            /**
             * Only the public ones.
             *
             * The pinned certificate is not an issuer of anything: nothing it signs is trusted,
             * and saying otherwise here would let a chain cleaner build a path through it.
             */
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
    }

    /** The socket factory that goes with it. */
    fun socketFactoryFor(trustManager: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory

    /**
     * Accepts the pinned server by its certificate, and everything else the ordinary way.
     *
     * OkHttp checks the host name against the certificate's subject alternative names and
     * nothing else, and `keytool` and a plain `openssl req` both produce a certificate with a
     * common name and no alternative names at all. That is exactly the certificate a home server
     * has, so without this the person picks their certificate, is told it is trusted, and the
     * sync goes on failing: the case the feature exists for.
     *
     * Safe because the certificate is pinned by its exact bytes. Being handed the one certificate
     * that was picked, for the one host it was picked for, is the identity check; a name inside
     * it would add nothing. Every other host still goes through the default verifier.
     */
    fun hostnameVerifierFor(
        certificate: X509Certificate,
        pinnedHost: String,
        default: HostnameVerifier,
    ): HostnameVerifier {
        val pinned = certificate.encoded
        return HostnameVerifier { host, session ->
            if (default.verify(host, session)) return@HostnameVerifier true
            if (!host.equals(pinnedHost, ignoreCase = true)) return@HostnameVerifier false
            val presented = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
            presented is X509Certificate && MessageDigest.isEqual(presented.encoded, pinned)
        }
    }
}
