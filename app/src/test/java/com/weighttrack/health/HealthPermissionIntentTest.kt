package com.weighttrack.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.health.HealthPermissionScreen.ACTION_REQUEST_HEALTH_PERMISSIONS
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Whether Health Connect is offered at all, on a phone that could not answer.
 *
 * From Android 14 the health permissions are ordinary runtime permissions, and the SDK reports
 * itself available on the strength of the platform version alone. Some Samsung phones on
 * Android 16 ship with no Health Connect screens behind that claim. Offering Connect there is
 * not a cosmetic mistake: the system permission dialog takes the request, tries to hand the
 * health part to an activity that does not exist, and dies with ActivityNotFoundException. The
 * dialog belongs to the system, so no catch in this app can stop it, and what somebody sees is
 * WeightTrack disappearing to the launcher. Found on a real phone, where it read as the app's
 * own crash.
 */
@RunWith(RobolectricTestRunner::class)
class HealthPermissionIntentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the action asked about is the one the manifest declares`() {
        // The manifest has to ask about the same action, or package visibility filters the
        // lookup down to nothing and every phone looks like it has no Health Connect.
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertThat(manifest).contains("android:name=\"$ACTION_REQUEST_HEALTH_PERMISSIONS\"")
    }

    @Test
    fun `a phone with no screen to grant them cannot be asked`() {
        // Nothing registered, which is the phone this was found on.
        assertThat(HealthPermissionScreen.exists(context)).isFalse()
    }

    @Test
    fun `a phone that can grant them can be asked`() {
        // The positive control. Without it the test above would still pass if the check had
        // simply been wired to answer no for everybody.
        registerPermissionScreen()

        assertThat(HealthPermissionScreen.exists(context)).isTrue()
    }

    /** Puts an activity behind the health permission request, the way a whole phone does. */
    private fun registerPermissionScreen() {
        val packageManager = shadowOf(context.packageManager)
        val component = ComponentName(
            "com.android.healthconnect.controller",
            "com.android.healthconnect.controller.permissions.request.PermissionsActivity",
        )
        packageManager.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                exported = true
            },
        )
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(ACTION_REQUEST_HEALTH_PERMISSIONS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
    }
}
