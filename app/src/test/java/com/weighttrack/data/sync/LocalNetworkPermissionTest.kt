package com.weighttrack.data.sync

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Android 17 grant for reaching a server on the phone's own network.
 *
 * The name is written out as a string because the constant for it only exists at API 37, so the
 * one thing that could quietly go wrong is the manifest and the code disagreeing about its
 * spelling. Nothing would fail to compile; sync to a NAS would simply never work.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionTest {

    @Test
    fun `the permission asked for is the one the manifest declares`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertThat(manifest).contains("android:name=\"${LocalNetworkPermission.NAME}\"")
    }

    @Test
    fun `versions that do not ask count as granted`() {
        // Robolectric runs these at API 34, which is every Android before the permission existed.
        assertThat(LocalNetworkPermission.isRequired()).isFalse()
        assertThat(LocalNetworkPermission.isGranted(ApplicationProvider.getApplicationContext()))
            .isTrue()
    }
}
