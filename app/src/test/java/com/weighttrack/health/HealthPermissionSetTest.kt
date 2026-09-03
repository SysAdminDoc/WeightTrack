package com.weighttrack.health

import androidx.health.connect.client.permission.HealthPermission
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Which Health Connect permissions the app asks for, and which it can do without.
 *
 * The history grant is the one worth a test. Without it Health Connect answers every read with
 * the last thirty days whatever window was asked for, so a five-year query looks like it worked
 * and quietly returns a month. It is easy to drop from a manifest and impossible to notice.
 */
class HealthPermissionSetTest {

    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the history permission is declared in the manifest`() {
        assertThat(manifest).contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    @Test
    fun `the history permission is asked for`() {
        assertThat(HealthConnectSync.permissions)
            .contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    @Test
    fun `refusing history does not stop weight syncing`() {
        // Sync is gated on the core set alone. Somebody who allows weight and refuses the older
        // readings gets the last thirty days, not an app that reports itself unauthorised.
        assertThat(HealthConnectSync.corePermissions)
            .doesNotContain(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    @Test
    fun `every permission the app asks for is declared in the manifest`() {
        val undeclared = HealthConnectSync.permissions.filterNot { manifest.contains(it) }

        assertThat(undeclared).isEmpty()
    }

    @Test
    fun `the optional groups are all part of the full set`() {
        // Each group is asked for together but checked on its own, so a missing one would mean
        // a feature that silently never gets its grant.
        assertThat(HealthConnectSync.permissions).containsAtLeastElementsIn(
            HealthConnectSync.corePermissions +
                HealthConnectSync.historyPermissions +
                HealthConnectSync.hydrationPermissions +
                HealthConnectSync.nutritionPermissions +
                HealthConnectSync.activityPermissions +
                HealthConnectSync.sleepPermissions +
                HealthConnectSync.menstruationPermissions,
        )
    }
}
