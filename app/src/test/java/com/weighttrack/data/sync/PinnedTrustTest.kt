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

    /**
     * A certificate authority, and a leaf it signed for a host nobody meant to trust.
     *
     * The first version of this installed the picked certificate as a trust anchor and let path
     * building run against it, so somebody talked into picking a file like the first of these
     * handed over every host the second kind could be minted for.
     */
    private val helpfulCa = certificate(
        """
        MIIDGTCCAgGgAwIBAgIJAJ+BR6j0a+gUMA0GCSqGSIb3DQEBDAUAMDExGjAYBgNVBAoTEVdlaWdo
        dFRyYWNrIHRlc3RzMRMwEQYDVQQDEwpIZWxwZnVsIENBMCAXDTI2MDgzMTIxNDMzOFoYDzIxMjYw
        ODA3MjE0MzM4WjAxMRowGAYDVQQKExFXZWlnaHRUcmFjayB0ZXN0czETMBEGA1UEAxMKSGVscGZ1
        bCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAK9IcZUdtTKQUX+g8N6+aCb/G7no
        oTfBsBn7ZZGFurMmstSKAZDMC4ULck6qbQqQzrsKYwBQoiqjLnoiFDBkbAWtYo5pZ1Q+fmBJHFDB
        LEmuHZBCGNH93/NEB7DxrYsCZIHh0QTuho9+vGmy/BHy0yPEF/l0IdzWtEciKoPi5SvCz8KVTJ3H
        WFD8t/ROPn53cCbjkKbZKmZcwym/cEOeNKNTGOvr3iZPhv/9+NyLXo4BCCTEgzpgEXB/I6hfuKLm
        tGKGSvLA6owG/iNQXfuXNM2BsqEHQ8Dw0tLjgL9SEAp2Gb8rIHnWvmNPqVxXvADS9QBI06H+ySI8
        7NUF2aFa2GkCAwEAAaMyMDAwHQYDVR0OBBYEFMyi5ZETjG3tIGf3meLVJnSBbqoRMA8GA1UdEwEB
        /wQFMAMBAf8wDQYJKoZIhvcNAQEMBQADggEBAIez61byR/rtaOEil6vOLY+wqPPNiN8FM1IESRJY
        2vuESW4kQ37HHQq+OPLOAvqPelZoSw9Lr3aK2pLNt0ymEnWuYA2aHmgonOSSdiilEhf65SN+AY/v
        h7Y/n3TisFvi9qUoMmp2YRTZUA7OJrO1PV1L9U3Kw3BQmBNkLmeP18MBC68hHxDB3ly1v6/oBNKL
        Dhn2nfe0a7UoKvhn8f0V7MJhZqn2XPdg5nCTfw+zKD6LVHw7OXdE+0Vg7i4jk/di3WU9CRKGWevb
        GhwNwvmJKi+SmZvZE9Rtivhc+xdCzTaL/moBZUuT18eder3dHpuqmsLAGKaECTmhB2aErRJehLQ=
        """,
    )

    /** CN=victim.example, signed by the authority above. */
    private val victimLeaf = certificate(
        """
        MIIDLDCCAhSgAwIBAgIIF3+fNOHDzkwwDQYJKoZIhvcNAQEMBQAwMTEaMBgGA1UEChMRV2VpZ2h0
        VHJhY2sgdGVzdHMxEzARBgNVBAMTCkhlbHBmdWwgQ0EwIBcNMjYwODMxMjE0MzM5WhgPMjEyNTAz
        MjUyMTQzMzlaMDUxGjAYBgNVBAoTEVdlaWdodFRyYWNrIHRlc3RzMRcwFQYDVQQDEw52aWN0aW0u
        ZXhhbXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOWHES9Jdd4B/uMGIbtU4wKV
        Yzienjtna1oU/SW3kd24Arw4KqyUjiT7OamyrZavon8zGJQUQmX4VLUOJSdtc+3/dfTGTvCv1bQb
        RBYY2tz0CfXNT5LYVqzelxcTuaNgZUEl+kbhO7SZKndI+VVA4yhIx90yspT2OELQjPyt10/Palb2
        hz/lPwBOYGL6Xls6qncs3v0zFsHugSVUEQRDgf4+ebBiuofpkbmUmalwquVzF0jOLb/0UH6IasYA
        hGS3f/lxsDF13azwmUfxEbPQ0hNcFemc8CZ5djQa19q9QaXTDp0fUn6jrGiU+7v5joccc20i5MTL
        J5Ms322L8ety3iUCAwEAAaNCMEAwHQYDVR0OBBYEFI7X79gcJKCunqYuD7yEPOQH88NiMB8GA1Ud
        IwQYMBaAFMyi5ZETjG3tIGf3meLVJnSBbqoRMA0GCSqGSIb3DQEBDAUAA4IBAQBj+EI56hqoBgSm
        epIUoQyhBpj5jBBWuoxRd4ZaP1ezAvVO61fpRql9ik1PeSwErvNZ5vgbfy7pkfoHvD3q5UTIqlYO
        Zwg/5GPOViVeSYYlQV7O9SvmzjatIU45xrItkCaJVBnie2PNZzIYymyNbMi7Ue742WoDPcnZpuuY
        5EAo0OeHOr8yQPyctZUWONcvPA1ZXUGTYal6VHF0+Pij/I0lsfx4LNOGiYAF39YpWTVg79K4rkIN
        /WMsdzIm2hGpOp0NwH+4RWjvXnoYLW9gCoWxoApQyMIWtY3f7PJjTw36B6nVL/pw09wdmenu1vwj
        M04OQpMes+pRjW35ad1kzvjc
        """,
    )

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
    fun `with nothing picked, the same server is refused`() {
        // The before half of "fails before the certificate is picked, passes after". The phone's
        // own trust store, with no pin anywhere near it.
        val plain = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>()
            .first()

        assertThrows(CertificateException::class.java) {
            plain.checkServerTrusted(arrayOf(nasCertificate), "RSA")
        }
    }

    @Test
    fun `picking a certificate authority does not hand over everything it signs`() {
        // The defect the first version had. Installed as a trust anchor, path building runs
        // against it and any leaf it has signed is accepted, for any host: somebody talked into
        // picking a file from a forum post loses the password and the whole history.
        val trust = PinnedTrust.trustManagerFor(helpfulCa)

        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(victimLeaf, helpfulCa), "RSA")
        }
        // The authority's own certificate is still accepted, because that is the one that was
        // picked. Nothing it vouches for is.
        trust.checkServerTrusted(arrayOf(helpfulCa), "RSA")
    }

    @Test
    fun `only the leaf the server presents is compared`() {
        // A chain whose first certificate is the pinned one is the pinned server. A chain that
        // merely contains it somewhere is not.
        val trust = PinnedTrust.trustManagerFor(nasCertificate)

        trust.checkServerTrusted(arrayOf(nasCertificate, otherCertificate), "RSA")
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(otherCertificate, nasCertificate), "RSA")
        }
    }

    @Test
    fun `the pinned certificate is not offered as an issuer of anything`() {
        val issuers = PinnedTrust.trustManagerFor(nasCertificate).acceptedIssuers

        // Whatever the platform ships, and not the pin. Naming it here would let a chain cleaner
        // build a path through it, which is the thing the pin exists to prevent.
        assertThat(issuers.toList()).doesNotContain(nasCertificate)
    }

    @Test
    fun `an empty chain is refused`() {
        val trust = PinnedTrust.trustManagerFor(nasCertificate)

        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(emptyArray(), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(null, "RSA")
        }
    }
}
