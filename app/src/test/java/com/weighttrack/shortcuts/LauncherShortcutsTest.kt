package com.weighttrack.shortcuts

import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.ui.navigation.Routes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two long-press shortcuts, and where they land.
 *
 * The mapping is the part worth pinning down: a shortcut that opens the app and then shows the
 * home screen is a shortcut that does nothing, and it looks like it worked.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherShortcutsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `logging a weight lands on the weight screen`() {
        assertThat(LauncherShortcuts.routeFor(LauncherShortcuts.ACTION_LOG_WEIGHT))
            .isEqualTo(Routes.log())
    }

    @Test
    fun `reading a scale lands on the scale screen`() {
        assertThat(LauncherShortcuts.routeFor(LauncherShortcuts.ACTION_READ_SCALE))
            .isEqualTo(Routes.SCALE)
    }

    @Test
    fun `an ordinary launch lands nowhere in particular`() {
        // The app opened from its own icon, or from a notification, must not be dragged onto a
        // screen nobody asked for.
        assertThat(LauncherShortcuts.routeFor(null)).isNull()
        assertThat(LauncherShortcuts.routeFor(android.content.Intent.ACTION_MAIN)).isNull()
        assertThat(LauncherShortcuts.routeFor("com.weighttrack.action.SOMETHING_ELSE")).isNull()
    }

    @Test
    fun `both shortcuts are published, aimed at this build's own activity`() {
        LauncherShortcuts.publish(context)

        val published = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertThat(published.map { it.id }).containsExactly("log-weight", "read-scale")
        published.forEach { shortcut ->
            // Not the package name written down anywhere: this app has four of them, and a
            // shortcut aimed at the wrong one opens nothing.
            assertThat(shortcut.intent?.component?.packageName).isEqualTo(context.packageName)
            assertThat(shortcut.shortLabel?.toString()).isNotEmpty()
            assertThat(shortcut.longLabel?.toString()).isNotEmpty()
        }
    }

    @Test
    fun `publishing twice leaves two shortcuts rather than four`() {
        // Called on every start, so a label can follow a change of language. It has to replace
        // what is there rather than add to it.
        LauncherShortcuts.publish(context)
        LauncherShortcuts.publish(context)

        assertThat(ShortcutManagerCompat.getDynamicShortcuts(context)).hasSize(2)
    }

    @Test
    fun `a shortcut brings the app forward rather than stacking a second copy`() {
        LauncherShortcuts.publish(context)

        val flags = ShortcutManagerCompat.getDynamicShortcuts(context)
            .mapNotNull { it.intent?.flags }

        flags.forEach { value ->
            assertThat(value and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        }
        assertThat(flags).hasSize(2)
    }
}
