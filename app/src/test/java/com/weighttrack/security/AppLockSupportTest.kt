package com.weighttrack.security

import androidx.biometric.BiometricManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockSupportTest {

    @Test
    fun `an enrolled device can lock the app`() {
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_SUCCESS))
            .isEqualTo(AppLockAvailability.AVAILABLE)
    }

    @Test
    fun `nothing enrolled asks the person to set a screen lock`() {
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
            .isEqualTo(AppLockAvailability.NO_SCREEN_LOCK)
    }

    @Test
    fun `missing or unusable hardware reports unavailable`() {
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
    }

    @Test
    fun `an uncertain status is treated as unavailable rather than assumed working`() {
        // Offering a lock that cannot actually prompt would strand someone outside their own
        // data, so anything short of a clear success is refused.
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
        assertThat(
            AppLockSupport.fromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            ),
        ).isEqualTo(AppLockAvailability.UNAVAILABLE)
    }

    @Test
    fun `an unrecognised code is treated as unavailable`() {
        assertThat(AppLockSupport.fromCanAuthenticate(9_999))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
    }

    @Test
    fun `the request accepts a screen lock as well as a biometric`() {
        // A phone with no fingerprint reader still has to be able to lock the app.
        assertThat(AppLockSupport.AUTHENTICATORS and BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .isNotEqualTo(0)
        assertThat(AppLockSupport.AUTHENTICATORS and BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .isNotEqualTo(0)
    }
}
