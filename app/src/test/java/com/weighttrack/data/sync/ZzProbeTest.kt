package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import okhttp3.internal.tls.OkHostnameVerifier
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Base64

class ZzProbeTest {

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

    private fun certificate(base64: String): X509Certificate =
        PinnedTrust.certificateFrom(Base64.getMimeDecoder().decode(base64))!!

    @Test
    fun `okhttp hostname verification against the pinned fixture`() {
        val verified = OkHostnameVerifier.verify("nas.example", nasCertificate)
        println("PROBE hostname verify(nas.example) = $verified")
        println("PROBE subjectAltNames = " + OkHostnameVerifier.allSubjectAltNames(nasCertificate))
        assertThat(verified).isTrue()
    }
}
