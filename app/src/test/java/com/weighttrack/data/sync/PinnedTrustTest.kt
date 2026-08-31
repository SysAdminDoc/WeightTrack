package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Trusting one server's own certificate, and only that one.
 *
 * The alternative to this is either sending somebody's weights over plain HTTP, which the app
 * refuses, or turning certificate checking off, which is worse than either. So the thing worth
 * being sure about is that picking a certificate for a server in the spare room does not quietly
 * make the phone accept anybody's certificate anywhere.
 *
 * The two fixtures are self-signed certificates generated for this test with keytool. They carry
 * no private key and expire in 2126.
 */
class PinnedTrustTest {

    /** CN=nas.example, the one somebody would pick for their own server. */
    private val nasCertificate = certificate(
        """
        MIIDRzCCAi+gAwIBAgIJAMPfnPoz+xm8MA0GCSqGSIb3DQEBDAUAMEgxFDASBgNVBAoTC1dlaWdo
        dFRyYWNrMRowGAYDVQQLExFXZWlnaHRUcmFjayB0ZXN0czEUMBIGA1UEAxMLbmFzLmV4YW1wbGUw
        IBcNMjYwODMxMjEwMTM0WhgPMjEyNjA4MDcyMTAxMzRaMEgxFDASBgNVBAoTC1dlaWdodFRyYWNr
        MRowGAYDVQQLExFXZWlnaHRUcmFjayB0ZXN0czEUMBIGA1UEAxMLbmFzLmV4YW1wbGUwggEiMA0G
        CSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDMuHjKxrbPfP5sQW3xauAvQN7PqRqxF6a4ONcsRhu1
        ZxZi7XRnDcIrHeiiiWQ+YldDxcOTiWOg5AUEaOunt8FFGN+5n9uCcRhaG59Id4MxpQI9G7xhcB0M
        ObM2PtuzSdJSN32p3rBlwqtl2JqbF5o6885HcwgCNqTZrYlRjsAgyYxV3+cm7x2fpSaNiGlolHOG
        8DnYjjWs/fy5adQyBvMP58FH0VyHdNqzgp5HfIY1G7jusl6/jiszResNMeZ68AnSCGKznXaiUTzo
        VP0NmicYj7ZgjoptJ9cIbEWDCN5iOiB5yF71rzJE7Xd64rnQ077fKRf6gjE0kD5WCCc9zrRBAgMB
        AAGjMjAwMB0GA1UdDgQWBBSEB5Jr493LekJb5yjL/W/RSS7obTAPBgNVHRMBAf8EBTADAQH/MA0G
        CSqGSIb3DQEBDAUAA4IBAQCKjiMjiZjXZcaCBN6wXrhZZn/FO1Gnv8cZxrYXhI/prSD3wNHYfga0
        BCTQojMtvl5ehPn1qBg6oVak4TZG7hILxerALI8Zs1e0QfciFrU6d3z/oukmDTC26TZ+boXkiHBW
        wVgPGoPCz/q6hQETRQMcpLT58NeS8CRxZl9b8dyNi+APbOfaQYmsIkd+eofYP3B+rp/uV/1wN/7S
        9uLCe8+z753IPJTLVUlUKgrOaCiU1YQya0sgv/GM1Ma4eeAc76g3Rhn7IMb7BG+WXyLwbhrBzlnH
        nPULq1lw4PRth2c+LlY9utZ1kxUDJf6A11SAUs/m8HOhphDACl1kz465iRuZ
        """,
    )

    /** CN=other.example, somebody else's, signed by nobody the phone knows either. */
    private val otherCertificate = certificate(
        """
        MIIDSzCCAjOgAwIBAgIJANL0urbVdBWsMA0GCSqGSIb3DQEBDAUAMEoxFDASBgNVBAoTC1dlaWdo
        dFRyYWNrMRowGAYDVQQLExFXZWlnaHRUcmFjayB0ZXN0czEWMBQGA1UEAxMNb3RoZXIuZXhhbXBs
        ZTAgFw0yNjA4MzEyMTAxMzRaGA8yMTI2MDgwNzIxMDEzNFowSjEUMBIGA1UEChMLV2VpZ2h0VHJh
        Y2sxGjAYBgNVBAsTEVdlaWdodFRyYWNrIHRlc3RzMRYwFAYDVQQDEw1vdGhlci5leGFtcGxlMIIB
        IjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsyF8bkBlhl0LJiYaZNGJ5aSU4SQCq1TaYTYj
        vWWQTyc8v1mSlb+EaJ06zbOMVaZ3DWvmCidBJ+ZZKOCKafvUqsfy+lUj+dEdPXIVQi1w3Zb7dBKK
        DVlbLuWEqNGqPaBMujl5HC5tUxmPoIOAQo27V5W4iDHwd7d2kkzMCfMLFqu1KDXtsW7QrbnH5Y7X
        iG8z/mieC8J9XxKsbEV+dAxlIs2yRCHUbwByqHxAgI1JB64hJVEsjH9N4mdoZNg61GYZBJm3lR3F
        /yf4AOwjWjvtH9R0U+Pvq4tLRCC/KfK7GKxTIZ9/d+tVhF1Hl+nsJloAcg0MO2EtFqBSzWk1eoeX
        +wIDAQABozIwMDAdBgNVHQ4EFgQUpjcpw5yYis6s8KYwg4wq1a3NmQEwDwYDVR0TAQH/BAUwAwEB
        /zANBgkqhkiG9w0BAQwFAAOCAQEAP6zvRxvTRfEeimv+0iBl/h4PymwZOZn7R8v5xD8LHT5rxn1B
        Dv0VI9J1mXdnRUaasJNmaQu1kxvLJgtfwL+WbvsjXjvIwarRBKuAzg1cfAqU7bpbMp04BB2yXrmF
        Copo2qAdZ2VJj1elDgTA28nzMQyppdK8Ag5pG1Omd3JbfprgNlednKa58mWfNN/jdTvGtLKp+dxu
        EgQA/HCFnG1gYX8BIguUckw5tWgCzVWlXcmP9R2JMUygSILCO5pMrekuOyHBuEFZ3lYmsE9WqDVL
        Z0zhlupxyMFS1NKjjXKtJkRpWb/1zO1KxTUykCSkChdEV8JuP0RHipR38Y4IYOngNg==
        """,
    )

    private fun certificate(base64: String): X509Certificate =
        PinnedTrust.certificateFrom(Base64.getMimeDecoder().decode(base64))!!

    @Test
    fun `a file that is not a certificate is refused rather than half read`() {
        assertThat(PinnedTrust.certificateFrom(byteArrayOf(1, 2, 3, 4))).isNull()
        assertThat(PinnedTrust.certificateFrom(ByteArray(0))).isNull()
        assertThat(PinnedTrust.certificateFrom("not a certificate".toByteArray())).isNull()
    }

    @Test
    fun `the picked certificate is accepted`() {
        val trust = PinnedTrust.trustManagerFor(nasCertificate)

        trust.checkServerTrusted(arrayOf(nasCertificate), "RSA")
    }

    @Test
    fun `nobody else's certificate is`() {
        // The whole point. Pinning a server in the spare room must not turn into accepting
        // anything anybody signs for themselves.
        val trust = PinnedTrust.trustManagerFor(nasCertificate)

        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(otherCertificate), "RSA")
        }
    }

    @Test
    fun `without the certificate the same server is refused`() {
        // The before half of "fails before the certificate is picked, passes after".
        val plain = PinnedTrust.trustManagerFor(otherCertificate)

        assertThrows(CertificateException::class.java) {
            plain.checkServerTrusted(arrayOf(nasCertificate), "RSA")
        }
    }

    @Test
    fun `the public authorities are kept, not replaced`() {
        val system = PinnedTrust.trustManagerFor(nasCertificate).acceptedIssuers

        // Whatever the platform ships, plus the one that was picked. Replacing the store would
        // mean pinning a home server quietly weakened the check on a hosted one.
        assertThat(system.size).isGreaterThan(1)
        assertThat(system.toList()).contains(nasCertificate)
    }

    @Test
    fun `an empty chain is refused`() {
        val trust = PinnedTrust.trustManagerFor(nasCertificate)

        assertThrows(Exception::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
    }
}
