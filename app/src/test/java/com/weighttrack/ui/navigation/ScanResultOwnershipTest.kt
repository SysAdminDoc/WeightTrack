package com.weighttrack.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The scanner hands its answer to Foods, so the two have to share a view model.
 *
 * The scan route pops itself the instant it reads a barcode. A view model taken with a bare
 * `hiltViewModel()` there belongs to the scan back stack entry, which the pop destroys, taking
 * `viewModelScope` and the half-finished lookup with it: a slow network answer, a not-found and a
 * rate limit all vanish before anybody sees them. Borrowing Foods' owner is what makes the result
 * land on the screen that asked for it.
 *
 * This reads the navigation host's source because ownership is a property of the graph rather
 * than of any one function, and there is nothing to call that would reveal it.
 */
class ScanResultOwnershipTest {

    private val host = File("src/main/java/com/weighttrack/ui/WeightTrackApp.kt")

    @Test
    fun `the scan route takes its view model from the foods entry`() {
        val body = routeBody("SCAN")

        assertThat(body).contains("getBackStackEntry(Routes.FOODS)")
        assertThat(body).contains("hiltViewModel(")
    }

    @Test
    fun `the scan route never owns its own view model`() {
        assertThat(routeBody("SCAN")).doesNotContain("hiltViewModel()")
    }

    @Test
    fun `foods is the only way to reach the scanner`() {
        // Ownership through the Foods entry is only sound while every path to the scanner has
        // Foods underneath it. A second entrance would crash on `getBackStackEntry` instead.
        val source = host.readText()
        val entrances = Regex("""navigate\(Routes\.SCAN\)""").findAll(source).count()
        assertThat(entrances).isEqualTo(1)
        assertThat(routeBody("FOODS")).contains("navigate(Routes.SCAN)")
    }

    /** The lines of one `composable(Routes.X) { ... }` block, found by brace depth. */
    private fun routeBody(route: String): String {
        val source = host.readText()
        val start = source.indexOf("composable(Routes.$route)")
        assertThat(start).isGreaterThan(-1)
        val open = source.indexOf('{', start)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        error("composable(Routes.$route) is never closed")
    }
}
