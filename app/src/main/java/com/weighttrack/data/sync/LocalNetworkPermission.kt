package com.weighttrack.data.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permission to talk to another machine on this network.
 *
 * New in Android 17. Before it, an app with the internet permission could open a socket to the
 * NAS in the cupboard as freely as to a server on the other side of the world, and plenty did so
 * for reasons the owner of the network would not have agreed to. Now it is asked for.
 *
 * It only matters for syncing to a WebDAV server inside the house. A hosted Nextcloud, and the
 * folder mode that never opens a socket at all, are unaffected and never prompt.
 *
 * Spelled out rather than taken from `Manifest.permission`, which would put an API 37 constant in
 * a minSdk 26 file for no gain. [LocalNetworkPermissionTest] holds it to what the manifest says.
 */
object LocalNetworkPermission {

    const val NAME: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 17. There is no named constant for it at this compile level. */
    private const val FIRST_VERSION_THAT_ASKS = 37

    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= FIRST_VERSION_THAT_ASKS

    /**
     * True on every version that does not ask, so a caller never has to check both.
     */
    fun isGranted(context: Context): Boolean =
        !isRequired() ||
            ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}
