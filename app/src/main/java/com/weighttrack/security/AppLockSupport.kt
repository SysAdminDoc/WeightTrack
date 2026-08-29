package com.weighttrack.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Whether this device can lock the app, and if not, why not. */
enum class AppLockAvailability {
    /** A fingerprint, face or screen lock is enrolled and usable. */
    AVAILABLE,

    /** The hardware is there but nothing is enrolled, so the person must set a screen lock first. */
    NO_SCREEN_LOCK,

    /** No usable authentication on this device at all. */
    UNAVAILABLE,

    /**
     * Cannot authenticate right now, but might in a moment.
     *
     * The lock stays up for this: a busy sensor is not a reason to hand over someone's data.
     */
    TEMPORARILY_UNAVAILABLE,
}

object AppLockSupport {

    /**
     * Both a biometric and the device PIN, pattern or password are accepted.
     *
     * Offering the screen lock as well as a fingerprint is what makes this usable: plenty of
     * phones have no biometric hardware, and a lock that only works with a fingerprint would
     * simply be unavailable to those people.
     */
    const val AUTHENTICATORS: Int = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL

    /**
     * Maps a [BiometricManager.canAuthenticate] result to something a settings screen can act on.
     *
     * Kept separate from the Android call so the awkward codes are testable. An unknown or
     * update-required status is treated as unavailable rather than assumed working, because
     * offering a lock that cannot actually prompt would leave someone locked out of their own
     * data.
     */
    fun fromCanAuthenticate(code: Int): AppLockAvailability = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockAvailability.NO_SCREEN_LOCK
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        -> AppLockAvailability.UNAVAILABLE
        // Anything else is a maybe: the sensor is busy, the status is unknown, a security
        // update is pending. Those are all transient, and treating them as "cannot lock"
        // would open the whole app without a prompt on a phone that still has a PIN.
        else -> AppLockAvailability.TEMPORARILY_UNAVAILABLE
    }

    fun availability(manager: BiometricManager): AppLockAvailability =
        fromCanAuthenticate(manager.canAuthenticate(AUTHENTICATORS))

    /**
     * Shows the system unlock prompt.
     *
     * [onFailure] fires for cancellation as well as for a genuine error; both leave the app
     * locked, which is the only safe reading of "the person did not authenticate".
     */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure(errString.toString())
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock WeightTrack")
            .setSubtitle("Your weight history is locked on this device")

        // Combining a biometric with the device credential only became supported in API 30.
        // Below that the older flag is the one that actually offers the PIN fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            info.setDeviceCredentialAllowed(true)
        }
        runCatching { prompt.authenticate(info.build()) }
            .onFailure { onFailure(it.message) }
    }
}
