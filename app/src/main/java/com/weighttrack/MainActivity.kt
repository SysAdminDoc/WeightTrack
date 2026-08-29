package com.weighttrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weighttrack.notifications.Notifications
import com.weighttrack.ui.AppViewModel
import com.weighttrack.ui.WeightTrackApp
import com.weighttrack.ui.theme.WeightTrackTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)

        setContent {
            val viewModel: AppViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            // Settings are read before anything is drawn, so the app never flashes the wrong
            // theme or shows onboarding to someone who finished it long ago.
            WeightTrackTheme(
                themeMode = settings?.themeMode ?: com.weighttrack.core.model.ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: false,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val loaded = settings
                    if (loaded == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        WeightTrackApp(onboardingComplete = loaded.onboardingComplete)
                    }
                }
            }
        }
    }
}
