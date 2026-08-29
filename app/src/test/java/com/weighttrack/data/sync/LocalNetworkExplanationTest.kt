package com.weighttrack.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Explaining a sync that could not reach a server on the phone's own network.
 *
 * The first version of this refused the sync before trying it, on a guess made from the host
 * name. That guess is wrong for a machine reached over a VPN or through a corporate DNS suffix,
 * and because a refusal is never retried it would have left background sync permanently dead
 * against a server that worked perfectly well. Everything here is about only ever making an
 * already-failed sync easier to understand.
 */
class LocalNetworkExplanationTest {

    private val message = "Android needs your permission to reach this server."
    private val unreachable = SyncResult.Unreachable("The server could not be reached.")

    private fun explain(
        result: SyncResult = unreachable,
        mode: SyncMode = SyncMode.WEBDAV,
        url: String? = "https://192.168.1.50/dav",
        granted: Boolean = false,
    ) = explainLocalNetwork(result, mode, url, granted, message)

    @Test
    fun `a server in the house that could not be reached is explained`() {
        val explained = explain()

        assertThat((explained as SyncResult.Unreachable).reason).isEqualTo(message)
    }

    @Test
    fun `it stays unreachable, so the hourly job keeps trying`() {
        // Refused is never retried. Once the permission is granted this has to heal on its own,
        // without anybody going back into Settings to press a button.
        assertThat(explain()).isInstanceOf(SyncResult.Unreachable::class.java)
    }

    @Test
    fun `a sync that worked is left alone`() {
        val done = SyncResult.Done(SyncChanges(), devices = 2)

        assertThat(explain(result = done)).isSameInstanceAs(done)
    }

    @Test
    fun `a wrong password is left alone`() {
        // Refused means the person can fix it and the message already says how. Replacing it with
        // a sentence about permissions would send them somewhere useless.
        val refused = SyncResult.Refused("The username or password was not accepted.")

        assertThat(explain(result = refused)).isSameInstanceAs(refused)
    }

    @Test
    fun `sync that was never set up is left alone`() {
        assertThat(explain(result = SyncResult.NotSetUp)).isSameInstanceAs(SyncResult.NotSetUp)
    }

    @Test
    fun `a folder on the phone is left alone`() {
        // Folder mode opens no socket at all, so the permission has nothing to do with it.
        assertThat(explain(mode = SyncMode.FOLDER, url = null)).isSameInstanceAs(unreachable)
    }

    @Test
    fun `a hosted server is left alone`() {
        val explained = explain(url = "https://cloud.example.com/remote.php/dav")

        assertThat(explained).isSameInstanceAs(unreachable)
    }

    @Test
    fun `a permission already granted means the failure was something else`() {
        assertThat(explain(granted = true)).isSameInstanceAs(unreachable)
    }

    @Test
    fun `no address at all is left alone`() {
        assertThat(explain(url = null)).isSameInstanceAs(unreachable)
    }

    @Test
    fun `the names people give their own servers are explained too`() {
        listOf(
            "https://nas.local/dav",
            "https://nas.lan/dav",
            "https://fritz.box/dav",
            "https://nas.home.arpa/dav",
            "https://[fd00::1]/dav",
            "https://10.0.0.8:8443/dav",
        ).forEach { address ->
            val explained = explain(url = address)
            assertThat((explained as SyncResult.Unreachable).reason).isEqualTo(message)
        }
    }
}
