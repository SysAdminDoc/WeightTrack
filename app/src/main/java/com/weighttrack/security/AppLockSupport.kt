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
     * The sensor is switched off until Android ships a security fix, and nothing else answers.
     *
     * Asking for both a biometric and the device credential means this only comes back when
     * neither can be used, so it is a lock that can never be satisfied. On a phone that has
     * stopped receiving updates it never clears either, so it is treated as permanent.
     */
    NEEDS_SECURITY_UPDATE,

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
     * Kept separate from the Android call so the awkward codes are testable. An unknown status
     * keeps the lock up, because it is not evidence that the device cannot authenticate. A
     * pending security update is different: it means the sensor is off and, since the request
     * includes the device credential, that there is no screen lock behind it either.
     */
    fun fromCanAuthenticate(code: Int): AppLockAvailability = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockAvailability.NO_SCREEN_LOCK
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        -> AppLockAvailability.UNAVAILABLE
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            AppLockAvailability.NEEDS_SECURITY_UPDATE
        // Anything else is a maybe: the sensor is busy, the status is unknown. Those are
        // transient, and treating them as "cannot lock" would open the whole app without a
        // prompt on a phone that still has a PIN.
        else -> AppLockAvailability.TEMPORARILY_UNAVAILABLE
    }

    /**
     * Whether a lock in this state could ever be satisfied.
     *
     * A transient failure keeps the lock up: a busy sensor says nothing about whether the phone
     * has a working PIN. Everything that can never be met has to stand the lock down, because
     * that is the case where leaving it up puts someone's own history permanently out of reach.
     *
     * Lives here rather than in the activity so the one rule the no-lockout promise rests on is
     * written once and can be tested.
     */
    fun canBeSatisfied(availability: AppLockAvailability): Boolean = when (availability) {
        AppLockAvailability.AVAILABLE, AppLockAvailability.TEMPORARILY_UNAVAILABLE -> true
        AppLockAvailability.NO_SCREEN_LOCK,
        AppLockAvailability.UNAVAILABLE,
        AppLockAvailability.NEEDS_SECURITY_UPDATE,
        -> false
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
