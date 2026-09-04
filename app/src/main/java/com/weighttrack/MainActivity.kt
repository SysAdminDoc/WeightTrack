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

    /**
     * The screen something outside the app asked for, if it did.
     *
     * Health Connect opens the app with this action when somebody taps through from its own
     * settings to ask what an access is for. Landing them on the home screen, which is what used
     * to happen, is not an answer.
     */
    private fun openAt(intent: android.content.Intent?): String? = when (intent?.action) {
        "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
        "android.intent.action.VIEW_PERMISSION_USAGE",
        -> com.weighttrack.ui.navigation.Routes.HEALTH_RATIONALE
        // A file opened from Files or a share sheet. It lands on Settings, because that is where
        // the picker for the same two kinds of file already lives and where a restore is shown
        // before it happens.
        android.content.Intent.ACTION_VIEW ->
            if (intent.data != null) com.weighttrack.ui.navigation.Routes.SETTINGS else null
        else -> com.weighttrack.shortcuts.LauncherShortcuts.routeFor(intent?.action)
    }

    /**
     * The file the phone asked this app to open, while the screen still needs it.
     *
     * Held on the activity and nowhere else. The read permission on it belongs to this activity
     * and this launch: writing it down somewhere that outlives them would leave a stored address
     * the app is no longer allowed to open.
     */
    private var openedFile by mutableStateOf<android.net.Uri?>(null)

    /**
     * The address a screen has already taken, kept across a recreation.
     *
     * The intent that carried it is never consumed: it is still there on the next `onCreate`,
     * and a rotation, a change of theme or a restore after the app was killed all run one. Held
     * only in the field beside this, the fact that somebody had already been shown the file was
     * the one thing that did not survive, so a spreadsheet imported itself again and a backup
     * asked to be restored a second time, after the answer had been given.
     */
    private var takenFile: android.net.Uri? = null

    /** Whether the screen this launch asked for has already been opened. See [openRoute]. */
    private var routeAnswered = false

    /**
     * The screen asked for, and how many times it has been asked for.
     *
     * Cleared the moment the graph has been sent there, and that fact is kept across a
     * configuration change. The intent behind it is never consumed, so anything that builds the
     * activity again reads the same request off it: left standing, a rotation, an unlock or a
     * theme change would drag somebody back onto a screen they had already left.
     */
    private var openRoute by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Somebody asking again, deliberately. A fresh request is not the old one coming back
        // round, so a route already answered does not silence it.
        openAt(intent)?.let { route ->
            routeAnswered = false
            openRoute = route
        }
        // Somebody asking again, deliberately, even for the same file. A fresh request is not
        // the old one coming back round, so what was taken before does not silence it.
        fileFrom(intent)?.let {
            takenFile = null
            openedFile = it
        }
    }

    private fun fileFrom(intent: android.content.Intent?): android.net.Uri? =
        intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.data

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ROUTE_ANSWERED, routeAnswered)
        // Only across a configuration change. Once the process has been killed, the screen that
        // was holding the file has gone with it, so a restore nobody had answered yet has to be
        // offered again rather than quietly dropped.
        if (isChangingConfigurations) {
            takenFile?.let { outState.putString(TAKEN_FILE, it.toString()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)
        // Published on every start rather than once: a label changes when the app is translated,
        // and a shortcut left by an older version would otherwise keep its old wording.
        com.weighttrack.shortcuts.LauncherShortcuts.publish(this)
        routeAnswered = savedInstanceState?.getBoolean(ROUTE_ANSWERED) == true
        openRoute = if (routeAnswered) null else openAt(intent)
        takenFile = savedInstanceState?.getString(TAKEN_FILE)?.let(android.net.Uri::parse)
        openedFile = fileStillToShow(fileFrom(intent), takenFile)

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
                        // Reached only once the lock is satisfied, and onboarding is answered
                        // inside, so a shortcut cannot skip either of them.
                        else -> WeightTrackApp(
                            onboardingComplete = loaded.onboardingComplete,
                            openAt = openRoute,
                            // Forgotten as soon as the graph has been sent there, so nothing
                            // that builds this screen again goes back a second time.
                            onOpenAtTaken = {
                                routeAnswered = true
                                openRoute = null
                            },
                            openedFile = openedFile,
                            // Forgotten the moment the screen has taken it, so a rotation does
                            // not offer to restore the same file all over again.
                            onOpenedFileTaken = {
                                takenFile = openedFile
                                openedFile = null
                            },
                        )
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

    private companion object {
        const val TAKEN_FILE = "openedFileTaken"
        const val ROUTE_ANSWERED = "openRouteAnswered"
    }
}

/**
 * Whether a file the phone handed over still needs putting in front of somebody.
 *
 * Its own function so the rule can be checked without a screen: the intent is still on the
 * activity after it is recreated, so the only thing separating "opened this" from "opened this
 * again" is whether the same address has already been shown.
 */
internal fun fileStillToShow(
    handedOver: android.net.Uri?,
    alreadyShown: android.net.Uri?,
): android.net.Uri? = handedOver?.takeIf { it != alreadyShown }
