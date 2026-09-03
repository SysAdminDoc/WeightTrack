package com.weighttrack.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.weighttrack.MainActivity
import com.weighttrack.R
import com.weighttrack.ui.navigation.Routes

/**
 * The two things worth reaching without opening the app first.
 *
 * Weighing is the one action people repeat every morning, and the whole app is built around
 * making it take three seconds. A long press on the icon is one tap shorter than the home screen,
 * and reading a scale is the same argument with a pen in the other hand.
 *
 * Published from code rather than declared in `res/xml`, because a static shortcut has to name
 * the package it opens and this app has four of them: the debug build, the benchmark build, and
 * the Play and F-Droid flavours. An intent built here is aimed at whichever one is running.
 */
object LauncherShortcuts {

    const val ACTION_LOG_WEIGHT = "com.weighttrack.action.LOG_WEIGHT"
    const val ACTION_READ_SCALE = "com.weighttrack.action.READ_SCALE"

    private const val ID_LOG_WEIGHT = "log-weight"
    private const val ID_READ_SCALE = "read-scale"

    /**
     * Where a shortcut lands, or null when the app was opened some other way.
     *
     * Kept apart from the publishing so the mapping can be checked without a launcher.
     */
    fun routeFor(action: String?): String? = when (action) {
        ACTION_LOG_WEIGHT -> Routes.log()
        ACTION_READ_SCALE -> Routes.SCALE
        else -> null
    }

    /**
     * Puts both on the launcher, replacing whatever was there.
     *
     * Called on every start rather than once: a label changes when the app is translated, and a
     * shortcut published by an older version would otherwise keep its old wording forever.
     */
    fun publish(context: Context) {
        // Never worth taking the app down for. A launcher that refuses shortcuts, or one that has
        // run out of room for them, is not a reason to fail to start.
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut(
                        context = context,
                        id = ID_LOG_WEIGHT,
                        short = R.string.shortcut_log_weight_short,
                        long = R.string.shortcut_log_weight_long,
                        icon = R.drawable.ic_shortcut_log_weight,
                        action = ACTION_LOG_WEIGHT,
                        rank = 0,
                    ),
                    shortcut(
                        context = context,
                        id = ID_READ_SCALE,
                        short = R.string.shortcut_read_scale_short,
                        long = R.string.shortcut_read_scale_long,
                        icon = R.drawable.ic_shortcut_read_scale,
                        action = ACTION_READ_SCALE,
                        rank = 1,
                    ),
                ),
            )
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        short: Int,
        long: Int,
        icon: Int,
        action: String,
        rank: Int,
    ): ShortcutInfoCompat = ShortcutInfoCompat.Builder(context, id)
        .setShortLabel(context.getString(short))
        .setLongLabel(context.getString(long))
        .setIcon(IconCompat.createWithResource(context, icon))
        .setRank(rank)
        .setIntent(
            // Aimed at this build's own activity, and brought to the front rather than stacked:
            // tapping the same shortcut twice must not leave two of a screen behind it.
            Intent(action)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        .build()
}
