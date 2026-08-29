package com.weighttrack

import android.app.Application
import com.weighttrack.diagnostics.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WeightTrackApplication : Application() {

    @Inject lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        // Installed first so a crash anywhere later in startup still leaves a report.
        crashReporter.install()
    }
}
