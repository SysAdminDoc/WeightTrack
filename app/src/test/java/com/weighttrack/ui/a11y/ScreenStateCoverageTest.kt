package com.weighttrack.ui.a11y

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.ui.theme.WeightTrackTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, in the states nobody looks at, at the sizes and directions nobody tries.
 *
 * The screenshots in `docs/screenshots` are populated AMOLED screens in English at the default
 * font size. Everything outside that was unchecked: a light theme nobody had opened, the 200
 * percent font somebody with poor eyesight actually uses, a right-to-left layout, an empty
 * screen before anything is recorded, a refused permission, a recoverable error. Those are the
 * states most likely to be wrong and the least likely to be noticed.
 *
 * Three things are checked, and each is one that a screenshot cannot show: that a control a
 * person can press says what it is, that it is big enough to press, and that nothing has been
 * pushed off the side of the screen.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// An ordinary phone width, on a window tall enough that a scrolling screen is laid out to the
// bottom. A node that is never placed reports no size at all, which reads as a control too small
// to press and hides whatever the real answer was.
@org.robolectric.annotation.Config(qualifiers = "w411dp-h6000dp")
internal class ScreenStateCoverageTest(
    private val fixture: ScreenFixture,
    private val appearance: Appearance,
) {

    @get:Rule
    val compose = createComposeRule()

    /** One way the app can look: a theme, a font size, and a direction to read in. */
    internal data class Appearance(
        val name: String,
        val theme: ThemeMode = ThemeMode.AMOLED,
        val fontScale: Float = 1f,
        val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        val localeTag: String? = null,
    ) {
        override fun toString(): String = name
    }

    private fun render() {
        compose.setContent {
            val density = LocalDensity.current
            val context = LocalContext.current
            val activityResultRegistryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(baseConfiguration, appearance.localeTag) {
                Configuration(baseConfiguration).apply {
                    appearance.localeTag?.let { setLocales(LocaleList.forLanguageTags(it)) }
                }
            }
            val localizedContext = remember(context, configuration) {
                context.createConfigurationContext(configuration)
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, appearance.fontScale),
                LocalLayoutDirection provides appearance.layoutDirection,
                LocalConfiguration provides configuration,
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
            ) {
                WeightTrackTheme(themeMode = appearance.theme) {
                    Box(Modifier.fillMaxSize()) { fixture.content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun everyNode(): List<SemanticsNode> {
        val root = compose.onRoot().fetchSemanticsNode()
        val found = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            found += node
            node.children.forEach(::walk)
        }
        walk(root)
        return found
    }

    /** Whether a node, or anything inside it, says what it is. */
    private fun SemanticsNode.saysWhatItIs(): Boolean {
        val own = config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() }
            ?: false
        val text = config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } ?: false
        val editable = config.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank()
            ?: false
        return own || text || editable || children.any { it.saysWhatItIs() }
    }

    private fun pressable(): List<SemanticsNode> = everyNode().filter { node ->
        node.config.contains(SemanticsActions.OnClick)
    }

    /** Lazy containers retain semantics for items they have not placed in the viewport yet. */
    private fun placedPressable(): List<SemanticsNode> = pressable().filter { node ->
        val bounds = node.touchBoundsInRoot
        bounds.width > 0f && bounds.height > 0f
    }

    @Test
    fun `everything a person can press says what it is`() {
        render()

        val silent = pressable().filterNot { it.saysWhatItIs() }

        assertThat(silent.map(::describe)).isEmpty()
    }

    @Test
    fun `everything a person can press is big enough to press`() {
        render()

        val density = compose.density
        val minimum = with(density) { MINIMUM_TOUCH_TARGET_DP.dp.toPx() }
        val tooSmall = placedPressable().filter { node ->
            val bounds = node.touchBoundsInRoot
            bounds.width < minimum - TOLERANCE_PX || bounds.height < minimum - TOLERANCE_PX
        }

        assertThat(tooSmall.map { describe(it) }).isEmpty()
    }

    @Test
    fun `nothing is pushed off the side of the screen`() {
        render()

        // Sideways only. A screen taller than the phone scrolls, which is ordinary; a control
        // past the right edge is simply gone, and at 200 percent font that is where things go.
        val root = compose.onRoot().fetchSemanticsNode()
        val width = root.boundsInRoot.right
        val clipped = placedPressable().filter { node ->
            node.boundsInRoot.right > width + TOLERANCE_PX ||
                node.boundsInRoot.left < -TOLERANCE_PX
        }

        assertThat(clipped.map { describe(it) }).isEmpty()
    }

    private fun describe(node: SemanticsNode): String {
        val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "node ${node.id} ${node.config}"
        return "$label ${node.boundsInRoot}"
    }

    internal companion object {

        /** What the platform asks for, and what a thumb needs. */
        const val MINIMUM_TOUCH_TARGET_DP = 48

        /** Rounding, not a licence to be a pixel short. */
        const val TOLERANCE_PX = 1f

        private val APPEARANCES = listOf(
            Appearance("dark"),
            Appearance("light", theme = ThemeMode.LIGHT),
            Appearance("dark at 200 percent font", fontScale = 2f),
            Appearance("light at 200 percent font", theme = ThemeMode.LIGHT, fontScale = 2f),
            Appearance("right to left", layoutDirection = LayoutDirection.Rtl),
            Appearance("accented pseudo-locale", localeTag = "en-XA"),
            Appearance(
                "bidirectional pseudo-locale",
                layoutDirection = LayoutDirection.Rtl,
                localeTag = "ar-XB",
            ),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} — {1}")
        fun cases(): List<Array<Any>> = ScreenFixtures.all.flatMap { fixture ->
            APPEARANCES.map { appearance -> arrayOf<Any>(fixture, appearance) }
        }
    }
}

private val Int.dp: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(toFloat())
