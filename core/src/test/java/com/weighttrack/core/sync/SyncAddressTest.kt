package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Telling a server in the spare room from one on the internet.
 *
 * The consequence of getting this wrong runs both ways. Too eager and somebody syncing to a
 * hosted Nextcloud is asked for a permission the app has no use for; too shy and syncing to the
 * box under the stairs fails on Android 17 with nothing on screen explaining why.
 */
class SyncAddressTest {

    @Test
    fun `the host comes out of an ordinary address`() {
        assertThat(SyncAddress.hostOf("https://cloud.example.com/remote.php/dav/files/me/"))
            .isEqualTo("cloud.example.com")
    }

    @Test
    fun `a port is not part of the host`() {
        assertThat(SyncAddress.hostOf("https://192.168.1.50:8443/dav")).isEqualTo("192.168.1.50")
    }

    @Test
    fun `credentials in the address are not part of the host`() {
        assertThat(SyncAddress.hostOf("https://me:secret@nas.local/dav")).isEqualTo("nas.local")
    }

    @Test
    fun `a bracketed address keeps its colons`() {
        assertThat(SyncAddress.hostOf("https://[fd00::1]:8443/dav")).isEqualTo("fd00::1")
    }

    @Test
    fun `an address with no scheme still gives a host`() {
        assertThat(SyncAddress.hostOf("cloud.example.com/dav")).isEqualTo("cloud.example.com")
    }

    @Test
    fun `nonsense gives no host`() {
        assertThat(SyncAddress.hostOf("")).isNull()
        assertThat(SyncAddress.hostOf("https://")).isNull()
    }

    @Test
    fun `the private ranges are on the local network`() {
        val private = listOf(
            "https://192.168.1.50/dav",
            "https://10.0.0.8/dav",
            "https://172.16.4.1/dav",
            "https://172.31.255.254/dav",
            "https://169.254.10.2/dav",
        )

        private.forEach { assertThat(SyncAddress.isOnLocalNetwork(it)).isTrue() }
    }

    @Test
    fun `addresses either side of the private ranges are not`() {
        // 172.15 and 172.32 sit just outside the block, and getting the bounds wrong here would
        // prompt for somebody else's server or fail to prompt for the one in the house.
        val public = listOf(
            "https://172.15.0.1/dav",
            "https://172.32.0.1/dav",
            "https://192.169.1.1/dav",
            "https://11.0.0.1/dav",
            "https://8.8.8.8/dav",
        )

        public.forEach { assertThat(SyncAddress.isOnLocalNetwork(it)).isFalse() }
    }

    @Test
    fun `loopback is not the local network`() {
        // A socket to the phone itself never reaches the network, and Android does not ask.
        assertThat(SyncAddress.isOnLocalNetwork("http://127.0.0.1:8080/dav")).isFalse()
        assertThat(SyncAddress.isOnLocalNetwork("http://localhost:8080/dav")).isFalse()
        assertThat(SyncAddress.isOnLocalNetwork("http://[::1]:8080/dav")).isFalse()
    }

    @Test
    fun `the private IPv6 ranges are on the local network`() {
        assertThat(SyncAddress.isOnLocalNetwork("https://[fd12:3456::1]/dav")).isTrue()
        assertThat(SyncAddress.isOnLocalNetwork("https://[fc00::1]/dav")).isTrue()
        assertThat(SyncAddress.isOnLocalNetwork("https://[fe80::1%25wlan0]/dav")).isTrue()
    }

    @Test
    fun `a routable IPv6 address is not`() {
        assertThat(SyncAddress.isOnLocalNetwork("https://[2001:db8::1]/dav")).isFalse()
    }

    @Test
    fun `names only the house can resolve are on the local network`() {
        assertThat(SyncAddress.isOnLocalNetwork("https://nas.local/dav")).isTrue()
        assertThat(SyncAddress.isOnLocalNetwork("https://NAS.LOCAL/dav")).isTrue()
        assertThat(SyncAddress.isOnLocalNetwork("https://nextcloud/dav")).isTrue()
    }

    @Test
    fun `a hosted server is not on the local network`() {
        assertThat(SyncAddress.isOnLocalNetwork("https://cloud.example.com/remote.php/dav")).isFalse()
        assertThat(SyncAddress.isOnLocalNetwork("https://my.nextcloud.co.uk/dav")).isFalse()
    }

    @Test
    fun `the endings home routers actually hand out are on the local network`() {
        // Missing these was the difference between a helpful sentence and a bare timeout for
        // most people who run a server at home, since almost nobody types a raw IP address.
        val local = listOf(
            "https://nas.lan/dav",
            "https://nextcloud.lan:8443/remote.php/dav",
            "https://nas.home.arpa/dav",
            "https://fritz.box/dav",
            "https://nextcloud.fritz.box/dav",
            "https://nas.home/dav",
            "https://nas.localdomain/dav",
            "https://synology.internal/dav",
        )

        local.forEach { assertThat(SyncAddress.isOnLocalNetwork(it)).isTrue() }
    }

    @Test
    fun `a name ending in the root dot is the same name`() {
        assertThat(SyncAddress.hostOf("https://nas.local./dav")).isEqualTo("nas.local")
        assertThat(SyncAddress.isOnLocalNetwork("https://nas.local./dav")).isTrue()
    }

    @Test
    fun `an IPv6 address written without brackets is still read whole`() {
        // Splitting on the first colon left "2001", which is not an address and read as a
        // dotless local name.
        assertThat(SyncAddress.hostOf("https://2001:db8::1/dav")).isEqualTo("2001:db8::1")
        assertThat(SyncAddress.isOnLocalNetwork("https://2001:db8::1/dav")).isFalse()
    }

    @Test
    fun `an empty address is not on the local network`() {
        assertThat(SyncAddress.isOnLocalNetwork("")).isFalse()
    }
}
