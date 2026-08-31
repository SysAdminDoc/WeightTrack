package com.weighttrack.ui.format

import android.content.Context

/**
 * What to call the app a reading came from.
 *
 * A package name is what Health Connect stores and it is not something to put in front of
 * somebody: "com.withings.wiscale2" is the answer to a question nobody asked. The launcher name
 * is looked up while the app is still installed, and the package name stands in when it is not,
 * which is better than nothing at all: the reading stays in the log after the app that wrote it
 * has gone, and the row still has to say where it came from.
 */
object OriginNames {

    /** The app's own name, or its package name when the app is no longer here. */
    fun label(context: Context, packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

    /** The app, and the scale it was talking to when it said. */
    fun describe(context: Context, packageName: String, device: String?): String {
        val app = label(context, packageName)
        return if (device.isNullOrBlank()) app else "$app · $device"
    }
}
