package com.weighttrack.diagnostics

import android.os.Build
import android.os.Process
import com.weighttrack.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Catches whatever killed the app and leaves a report behind.
 *
 * Crashes and lost data are the top reason weight trackers get one star, and the reports users
 * can actually send are the difference between a bug that gets fixed and one that does not.
 * Nothing is uploaded: the file sits in app-private storage until someone opens Settings and
 * chooses to share it.
 */
@Singleton
class CrashReporter @Inject constructor(
    private val store: CrashLogStore,
) {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let reporting replace the crash. A failure in here would surface a
            // misleading exception and could stop the system handler from ever running,
            // leaving the app frozen instead of closing.
            runCatching { store.write(throwable, thread.name, buildInfo()) }
            val previous = previousHandler
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Android always installs one, but if it ever did not, returning here would
                // leave the process alive with a dead main thread: a frozen black screen
                // instead of an app that closes.
                Process.killProcess(Process.myPid())
                exitProcess(EXIT_CRASH)
            }
        }
    }

    fun buildInfo(): String = buildString {
        appendLine("App: WeightTrack ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}, ${BuildConfig.FLAVOR})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private companion object {
        /** Any non-zero status; the process is being torn down after an unhandled crash. */
        const val EXIT_CRASH = 10
    }
}
