package com.weighttrack

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)

        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val locked by viewModel.locked.collectAsStateWithLifecycle()
            val lockError by viewModel.lockError.collectAsStateWithLifecycle()

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

                    // Leaving the app re-arms the lock, so returning to it asks again rather
                    // than handing the last screen to whoever picks the phone up next.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, lockEnabled) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP && lockEnabled) {
                                viewModel.lock()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    val showLock = lockEnabled && locked
                    // Prompt as soon as the lock appears; the button is for a second attempt.
                    LaunchedEffect(showLock) {
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
