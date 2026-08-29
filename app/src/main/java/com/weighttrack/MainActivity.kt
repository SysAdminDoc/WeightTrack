package com.weighttrack

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.notifications.Notifications
import com.weighttrack.security.AppLockSupport
import com.weighttrack.ui.AppViewModel
import com.weighttrack.ui.WeightTrackApp
import com.weighttrack.ui.lock.LockScreen
import com.weighttrack.ui.theme.WeightTrackTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * A FragmentActivity rather than a plain ComponentActivity because the biometric prompt is
 * hosted in a fragment. Nothing else in the app uses fragments.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /** Whether the lock can be satisfied at all on this device, right now. */
    private fun lockIsUsable(): Boolean =
        AppLockSupport.canBeSatisfied(AppLockSupport.availability(BiometricManager.from(this)))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)

        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val locked by viewModel.locked.collectAsStateWithLifecycle()
            val lockError by viewModel.lockError.collectAsStateWithLifecycle()
            val promptRequest by viewModel.promptRequest.collectAsStateWithLifecycle()

            // Settings are read before anything is drawn, so the app never flashes the wrong
            // theme or shows onboarding to someone who finished it long ago.
            WeightTrackTheme(
                themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: false,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val loaded = settings
                    val lockEnabled = loaded?.appLockEnabled == true

                    // Re-checked whenever the app comes back, because someone can add or
                    // remove a device screen lock while WeightTrack sits in the background.
                    var lockUsable by remember { mutableStateOf(lockIsUsable()) }

                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, lockEnabled) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_START -> lockUsable = lockIsUsable()
                                Lifecycle.Event.ON_STOP ->
                                    // A rotation, a multi-window change or a theme change stops
                                    // the activity too. Re-locking on those would demand a
                                    // fingerprint every time the phone is turned.
                                    if (lockEnabled && !isChangingConfigurations) viewModel.lock()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    // Keeps the unlocked screen out of the recents switcher and out of
                    // screenshots, which the lock would otherwise leak straight past.
                    DisposableEffect(lockEnabled) {
                        if (lockEnabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                        onDispose { }
                    }

                    // If the device can no longer authenticate, the lock is skipped rather
                    // than left standing. Otherwise removing the screen lock after enabling
                    // this would put someone's whole history permanently out of reach, and
                    // there is no secret left to check against anyway.
                    val showLock = lockEnabled && locked && lockUsable

                    LaunchedEffect(showLock, promptRequest) {
                        if (showLock) requestUnlock(viewModel)
                    }

                    when {
                        loaded == null -> Box(Modifier.fillMaxSize())
                        showLock -> LockScreen(
                            error = lockError,
                            onUnlock = { requestUnlock(viewModel) },
                        )
                        else -> WeightTrackApp(onboardingComplete = loaded.onboardingComplete)
                    }
                }
            }
        }
    }

    private fun requestUnlock(viewModel: AppViewModel) {
        AppLockSupport.prompt(
            activity = this,
            onSuccess = viewModel::unlock,
            onFailure = viewModel::onUnlockFailed,
        )
    }
}
