package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What an address is refused for, before anything is stored.
 *
 * A refused address used to be stored and then fail an hour later inside a background job nobody
 * was watching, with a message that said nothing about why. Plain HTTP is the case that matters:
 * the app blocks cleartext at the platform level, so that address was never going to work, and
 * what came back said only that the server could not be reached.
 */
class SyncAddressProblemTest {

    @Test
    fun `plain http is refused, and said to be refused for being plain http`() {
        assertThat(SyncAddress.problemWith("http://nas.local/remote.php/dav"))
            .isEqualTo(AddressProblem.NOT_ENCRYPTED)
        // However it is typed.
        assertThat(SyncAddress.problemWith("  HTTP://nas.local/dav  "))
            .isEqualTo(AddressProblem.NOT_ENCRYPTED)
    }

    @Test
    fun `an ordinary https address is accepted`() {
        listOf(
            "https://cloud.example.com/remote.php/dav/files/sam",
            "https://192.168.1.10:8443/dav",
            "https://nas.home.arpa/dav",
            "https://[fd00::1]/dav",
        ).forEach { assertThat(SyncAddress.problemWith(it)).isNull() }
    }

    @Test
    fun `nothing typed is told apart from something unreadable`() {
        assertThat(SyncAddress.problemWith("")).isEqualTo(AddressProblem.EMPTY)
        assertThat(SyncAddress.problemWith("   ")).isEqualTo(AddressProblem.EMPTY)
        assertThat(SyncAddress.problemWith("nas.local/dav")).isEqualTo(AddressProblem.UNREADABLE)
        assertThat(SyncAddress.problemWith("https://")).isEqualTo(AddressProblem.UNREADABLE)
        assertThat(SyncAddress.problemWith("https:///dav")).isEqualTo(AddressProblem.UNREADABLE)
    }

    @Test
    fun `a scheme the app does not speak is named as one`() {
        listOf("ftp://nas.local/dav", "file:///sdcard/dav", "smb://nas.local/dav").forEach {
            assertThat(SyncAddress.problemWith(it)).isEqualTo(AddressProblem.NOT_WEB)
        }
    }

    @Test
    fun `usable is the same question asked the other way round`() {
        assertThat(SyncAddress.isUsable("https://cloud.example.com/dav")).isTrue()
        assertThat(SyncAddress.isUsable("http://cloud.example.com/dav")).isFalse()
    }
}
