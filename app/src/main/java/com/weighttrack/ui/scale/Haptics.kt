package com.weighttrack.ui.scale

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one buzz the app makes.
 *
 * Somebody weighing themselves is looking down at a scale, not at a phone across the room. The
 * number lands, the app files it, and until now nothing said so: they went and picked the phone
 * up to find out whether it had worked.
 */
interface Haptics {

    /** A weight came off the scale and was filed. */
    fun weighInLanded()
}

@Singleton
class AndroidHaptics @Inject constructor(
    @ApplicationContext private val context: Context,
) : Haptics {

    override fun weighInLanded() {
        // Swallowed rather than reported. A phone with no motor, or one whose owner has turned
        // vibration off, is not a failure of the weigh-in, and the weight is already saved.
        runCatching {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private companion object {
        /** Short enough to read as confirmation rather than as an alert. */
        const val BUZZ_MILLIS = 40L
    }
}
