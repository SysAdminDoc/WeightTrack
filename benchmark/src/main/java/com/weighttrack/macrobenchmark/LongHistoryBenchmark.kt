package com.weighttrack.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app does with a history it was never sized for.
 *
 * Weight trackers accumulate. Somebody who starts today and keeps going has five thousand
 * readings in fifteen years, and the screens that get slow are the ones that read all of them:
 * the trend line, the chart, and the aggregate behind the home card. Nothing in this repo
 * measured that, so nothing would have noticed a query losing its index or a chart drawing every
 * point it was handed.
 *
 * The fixture is twenty years of daily weigh-ins, five years of meals and twenty years of monthly
 * measurements, seeded deterministically before each measured pass so two runs are comparable.
 */
// Peak memory is an experimental metric and the acceptance asks for it by name. Opted in here
// rather than silently dropped: a fixture that does not record memory cannot catch a history
// that starts being held twice.
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class LongHistoryBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun homeOnTwentyYears() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { seedFixture() },
    ) {
        startActivityAndWait()
        // Waited for by name rather than by a fixed sleep: the trend card is the thing that has
        // to read the whole history, so a run that timed out here is the finding.
        device.wait(Until.hasObject(By.textContains("kg")), SCREEN_TIMEOUT_MS)
    }

    @Test
    fun chartsOnTwentyYears() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { seedFixture() },
    ) {
        startActivityAndWait()
        openCharts()
        device.waitForIdle()
    }

    /**
     * Switching the range a chart is drawn over, which is the interaction that redraws everything.
     *
     * Measured as frame timing rather than as a duration, because what somebody notices is the
     * chart stuttering rather than a number in a log.
     */
    @Test
    fun rangeSwitchOnTwentyYears() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { seedFixture() },
    ) {
        startActivityAndWait()
        openCharts()
        RANGES.forEach { range ->
            val chip = device.findObject(By.text(range))
            checkNotNull(chip) { "the chart has no $range range to switch to" }
            chip.click()
            device.waitForIdle()
        }
    }

    /**
     * Moves to the chart, and refuses to carry on if it did not.
     *
     * A benchmark that taps nothing still reports frame timings, because the app drew a few
     * frames arriving at the screen it was already on. Five frames and a green run is a fixture
     * measuring nothing at all, which is worse than not having one.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openCharts() {
        val tab = device.wait(Until.findObject(By.text("Charts")), SCREEN_TIMEOUT_MS)
        checkNotNull(tab) { "no Charts tab on screen; the app is not where this expects it" }
        tab.click()
        val range = device.wait(Until.findObject(By.text("All")), SCREEN_TIMEOUT_MS)
        checkNotNull(range) { "the chart's range chips never appeared" }
    }

    /**
     * Fills the database and clears whatever the last pass left.
     *
     * The broadcast is answered by a receiver that only the benchmark build type carries, and the
     * shell waits for it, so measurement never starts against a half-written history.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.seedFixture() {
        killProcess()
        val result = device.executeShellCommand(
            "am broadcast -a $SEED_ACTION -n $TARGET/com.weighttrack.benchmark.FixtureSeeder",
        )
        check(result.contains("result=$SEEDED")) { "the fixture did not seed: $result" }
    }

    private companion object {
        const val TARGET = "com.weighttrack.benchmark"
        const val SEED_ACTION = "com.weighttrack.benchmark.SEED"
        const val SEEDED = 42
        const val ITERATIONS = 8
        const val SCREEN_TIMEOUT_MS = 10_000L

        /** The ranges the chart offers, in the order somebody would tap through them. */
        val RANGES = listOf("3M", "1Y", "All")
    }
}
