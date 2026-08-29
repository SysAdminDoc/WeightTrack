package com.weighttrack.ble

import android.bluetooth.BluetoothDevice
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.diagnostics.LogEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A scale that has forgotten the pairing.
 *
 * It happens after a factory reset, and on some models after a battery change. The phone still
 * holds its half of the pairing, so it connects, gets nowhere and drops, which looks exactly like
 * a scale that is switched off. The advice for the two is completely different, and until Android
 * 16 there was no way to tell them apart at all.
 */
@RunWith(RobolectricTestRunner::class)
class ScaleBondTest {

    @Test
    fun `the action listened for is the one the platform broadcasts`() {
        // Spelled out in the source because the constant is API 36 and this module builds against
        // minSdk 26. A typo would compile, and the scale would simply never be diagnosed.
        assertThat(KEY_MISSING_ACTION).isEqualTo("android.bluetooth.device.action.KEY_MISSING")
    }

    @Test
    fun `the platform constant has not moved`() {
        // Robolectric runs at API 34, where the constant does not exist yet, so this reads the
        // value from the compile SDK through reflection rather than referring to it.
        val field = runCatching { BluetoothDevice::class.java.getField("ACTION_KEY_MISSING") }
            .getOrNull()

        // Present from API 36. When it is there, it must match what the app listens for.
        if (field != null) {
            assertThat(field.get(null)).isEqualTo("android.bluetooth.device.action.KEY_MISSING")
        }
    }

    @Test
    fun `the broadcast for this scale is read as a lost bond`() {
        assertThat(
            isBondLost(
                action = "android.bluetooth.device.action.KEY_MISSING",
                broadcastAddress = "AA:BB:CC:DD:EE:FF",
                connectedTo = "AA:BB:CC:DD:EE:FF",
            ),
        ).isTrue()
    }

    @Test
    fun `somebody else's device losing its bond is not this scale`() {
        // Headphones unpaired at the same moment must not make the scale screen say the scale
        // forgot the pairing.
        assertThat(
            isBondLost(
                action = "android.bluetooth.device.action.KEY_MISSING",
                broadcastAddress = "11:22:33:44:55:66",
                connectedTo = "AA:BB:CC:DD:EE:FF",
            ),
        ).isFalse()
    }

    @Test
    fun `another broadcast is not a lost bond`() {
        assertThat(
            isBondLost(
                action = "android.bluetooth.device.action.ACL_DISCONNECTED",
                broadcastAddress = "AA:BB:CC:DD:EE:FF",
                connectedTo = "AA:BB:CC:DD:EE:FF",
            ),
        ).isFalse()
        assertThat(isBondLost(null, "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF")).isFalse()
        assertThat(
            isBondLost("android.bluetooth.device.action.KEY_MISSING", null, "AA:BB:CC:DD:EE:FF"),
        ).isFalse()
    }

    @Test
    fun `a lost bond is its own problem, not a lost connection`() {
        // Reporting it as CONNECTION_LOST would send somebody to check the batteries in a scale
        // whose batteries are fine.
        assertThat(ScaleProblem.entries).contains(ScaleProblem.BOND_LOST)
        assertThat(ScaleProblem.BOND_LOST).isNotEqualTo(ScaleProblem.CONNECTION_LOST)
    }

    @Test
    fun `there is an event for it in the activity log`() {
        assertThat(LogEvent.entries).contains(LogEvent.SCALE_BOND_LOST)
    }

    @Test
    fun `every problem the app can report has something to say about it`() {
        // A problem with no wording renders as a blank card on the scale screen.
        val screen = File("src/main/java/com/weighttrack/ui/scale/ScaleScreen.kt").readText()

        ScaleProblem.entries.forEach { problem ->
            assertThat(screen).contains("ScaleProblem.${problem.name}")
        }
    }

    @Test
    fun `the log store the connection writes to is the shared one`() {
        // Guards against a second log file appearing beside the first, which would split the
        // record of one failure across two places.
        assertThat(File("src/main/java/com/weighttrack/di/DataModule.kt").readText())
            .contains("RuntimeLog.FILE_NAME")
    }

    @Test
    fun `the context it needs is available`() {
        assertThat(ApplicationProvider.getApplicationContext<android.content.Context>()).isNotNull()
    }
}
