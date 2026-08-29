package com.weighttrack.ui

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Words for the places a composable cannot reach.
 *
 * Screens read their text with `stringResource`, which only works inside a composable. The
 * messages a view model puts in a snackbar are just as much text somebody reads, and without
 * something like this they stay in English forever while the rest of the app is translated.
 *
 * The application context, so it outlives any screen and cannot hold one alive.
 */
@Singleton
class AppStrings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    operator fun get(@StringRes id: Int, vararg arguments: Any): String =
        context.getString(id, *arguments)
}
