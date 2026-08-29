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
    fun `a device that can never authenticate reports unavailable`() {
        // Only these two mean "there is nothing here to authenticate against, ever". They are
        // the cases where leaving the lock standing would strand someone outside their own data.
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
            .isEqualTo(AppLockAvailability.UNAVAILABLE)
    }

    @Test
    fun `a busy sensor is temporary, not a reason to unlock the app`() {
        // The sensor being unavailable this second says nothing about whether the phone has a
        // working PIN. Treating it as "cannot lock" opens the whole app with no prompt, which
        // is a bypass rather than a convenience.
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
            .isEqualTo(AppLockAvailability.TEMPORARILY_UNAVAILABLE)
    }

    @Test
    fun `an uncertain status is treated as temporary`() {
        assertThat(AppLockSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
            .isEqualTo(AppLockAvailability.TEMPORARILY_UNAVAILABLE)
    }

    @Test
    fun `a sensor blocked pending a security update is not treated as temporary`() {
        // The request asks for the device credential as well, so this code only comes back when
        // there is no screen lock behind the sensor either. On a phone that has stopped getting
        // updates it never clears, and calling it temporary leaves the lock standing forever
        // over data the owner can then never reach.
        assertThat(
            AppLockSupport.fromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            ),
        ).isEqualTo(AppLockAvailability.NEEDS_SECURITY_UPDATE)
    }

    @Test
    fun `only a lock that could be answered is allowed to stand`() {
        // Everything that can never be met has to let the app open, or the promise that there
        // is no permanent lockout is not kept.
        assertThat(AppLockSupport.canBeSatisfied(AppLockAvailability.AVAILABLE)).isTrue()
        assertThat(AppLockSupport.canBeSatisfied(AppLockAvailability.TEMPORARILY_UNAVAILABLE))
            .isTrue()

        assertThat(AppLockSupport.canBeSatisfied(AppLockAvailability.NO_SCREEN_LOCK)).isFalse()
        assertThat(AppLockSupport.canBeSatisfied(AppLockAvailability.UNAVAILABLE)).isFalse()
        assertThat(AppLockSupport.canBeSatisfied(AppLockAvailability.NEEDS_SECURITY_UPDATE))
            .isFalse()
    }

    @Test
    fun `a broken sensor with no screen lock behind it opens the app`() {
        // End to end from the Android status code, which is what MainActivity asks for.
        val availability = AppLockSupport.fromCanAuthenticate(
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        )
        assertThat(AppLockSupport.canBeSatisfied(availability)).isFalse()
    }

    @Test
    fun `an unrecognised code errs towards keeping the lock up`() {
        // A code this build has never heard of is not evidence that the device cannot
        // authenticate, so it must not be treated as permission to open the app.
        assertThat(AppLockSupport.fromCanAuthenticate(9_999))
            .isEqualTo(AppLockAvailability.TEMPORARILY_UNAVAILABLE)
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
