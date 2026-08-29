package com.weighttrack.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Runs [onResume] each time the screen comes back to the foreground.
 *
 * Screens in this app sit on the navigation back stack and keep their composition alive, so
 * anything read from outside the app (a granted permission, a device screen lock, a file on
 * disk) is stale until something asks again. A plain `LaunchedEffect(Unit)` runs once and does
 * not cover that.
 */
@Composable
fun ResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
