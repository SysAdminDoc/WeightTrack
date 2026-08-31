package com.weighttrack.ui.settings

import android.net.Uri
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.data.io.BackupPreview
import com.weighttrack.health.HealthConnectAvailability

/** A backup that has been read and described, waiting for somebody to say yes to it. */
data class PendingRestore(
    val uri: Uri,
    val preview: BackupPreview,
)

data class HealthConnectState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_SUPPORTED,
    val granted: Boolean = false,
    /**
     * Whether food, water and steps were allowed as well as weight.
     *
     * False on any install that connected before those existed, which is every one of them.
     */
    val grantedEverything: Boolean = false,
    /**
     * Somebody connected once and the access has gone since.
     *
     * Worth saying rather than quietly showing the Connect button again, because from where they
     * are sitting the sync simply stopped and nothing said why.
     */
    val accessWithdrawn: Boolean = false,
    val syncing: Boolean = false,
    /** Which way readings may move, which is also what the app asks permission for. */
    val direction: HealthDirection = HealthDirection.TWO_WAY,
    /**
     * The apps whose readings have arrived here, each with whether they are still wanted.
     *
     * Read from what is actually in the log rather than from a list of known scale apps: the one
     * writing into somebody's Health Connect is whichever app they happen to use.
     */
    val origins: List<HealthOrigin> = emptyList(),
)

/** One app that has written a reading into this log, and whether it still may. */
data class HealthOrigin(
    val packageName: String,
    val device: String?,
    val excluded: Boolean,
)
