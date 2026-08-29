package com.weighttrack.core.share

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.math.Milestone
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class MilestoneCardTest {

    private val start = LocalDate.of(2026, 6, 1)
    private val today = LocalDate.of(2026, 8, 29)
    private val locale = Locale.UK

    private fun series(days: Int = 60, from: Int = 90_000, gramsPerDay: Double = -100.0) =
        TrendEngine.computeSeries(
            (0 until days).map {
                DailyWeight(start.plusDays(it.toLong()), (from + gramsPerDay * it).toInt())
            },
            TrendEngine.DEFAULT_WINDOW_DAYS,
        )

    private fun milestone(grams: Int, reachedOn: LocalDate?) =
        Milestone(grams = grams, index = 1, total = 3, reachedOn = reachedOn)

    @Test
    fun `the card says how far, not how much`() {
        val content = MilestoneCard.forMilestone(
            milestone = milestone(85_000, start.plusDays(50)),
            startGrams = 90_000,
            series = series(),
            unit = WeightUnit.KG,
            today = today,
            locale = locale,
        )

        assertThat(content.headline).contains("5")
        assertThat(content.headline).contains("down")
        // The whole point. Somebody sharing that they have lost five kilograms has not agreed to
        // tell anybody what they weigh.
        assertThat(content.headline).doesNotContain("85")
        assertThat(content.footer).doesNotContain("85")
        assertThat(content.line).doesNotContain("85")
    }

    @Test
    fun `their weight appears only when they ask for it`() {
        val content = MilestoneCard.forMilestone(
            milestone = milestone(85_000, start.plusDays(50)),
            startGrams = 90_000,
            series = series(),
            unit = WeightUnit.KG,
            includeWeight = true,
            today = today,
            locale = locale,
        )

        assertThat(content.footer).contains("85")
    }

    @Test
    fun `gaining reads as gaining`() {
        // A card that called a gain a loss would be worse than no card.
        val content = MilestoneCard.forMilestone(
            milestone = milestone(93_000, start.plusDays(30)),
            startGrams = 90_000,
            series = series(gramsPerDay = 100.0),
            unit = WeightUnit.KG,
            today = today,
            locale = locale,
        )

        assertThat(content.headline).contains("up")
        assertThat(content.headline).doesNotContain("-")
    }

    @Test
    fun `holding steady is not called nothing`() {
        // Three months of holding a weight is worth a card, and "0 kg lost" is the wrong words
        // for it.
        val content = MilestoneCard.forProgress(
            fromGrams = 90_000,
            toGrams = 90_000,
            days = 90,
            series = series(gramsPerDay = 0.0),
            unit = WeightUnit.KG,
            today = today,
            locale = locale,
        )

        assertThat(content.headline).contains("steady")
    }

    @Test
    fun `one day is a day and two are days`() {
        val oneDay = MilestoneCard.forProgress(
            fromGrams = 90_000, toGrams = 89_500, days = 1,
            series = series(), unit = WeightUnit.KG, today = today, locale = locale,
        )
        val more = MilestoneCard.forProgress(
            fromGrams = 90_000, toGrams = 89_500, days = 12,
            series = series(), unit = WeightUnit.KG, today = today, locale = locale,
        )

        assertThat(oneDay.subhead).isEqualTo("in 1 day")
        assertThat(more.subhead).isEqualTo("in 12 days")
    }

    @Test
    fun `the written line says the same as the card`() {
        val content = MilestoneCard.forMilestone(
            milestone = milestone(85_000, start.plusDays(50)),
            startGrams = 90_000,
            series = series(),
            unit = WeightUnit.KG,
            today = today,
            locale = locale,
        )

        // Shared as text or as a picture, it has to be the same claim.
        assertThat(content.line).contains(content.headline)
        assertThat(content.line).contains(content.subhead)
    }

    @Test
    fun `pounds are shown to somebody who reads pounds`() {
        val content = MilestoneCard.forMilestone(
            milestone = milestone(85_000, start.plusDays(50)),
            startGrams = 90_000,
            series = series(),
            unit = WeightUnit.LB,
            today = today,
            locale = locale,
        )

        assertThat(content.headline.lowercase()).contains("lb")
    }

    // ---- the line's shape ----

    @Test
    fun `the shape carries no weights`() {
        val shape = MilestoneCard.shapeOf(series())

        // Between nothing and one. The axis never leaves the phone: a card that carried it would
        // tell everybody what the person weighs whatever the footer said.
        assertThat(shape).isNotEmpty()
        for (value in shape) {
            assertThat(value).isAtLeast(0.0)
            assertThat(value).isAtMost(1.0)
        }
        assertThat(shape.min()).isWithin(1e-9).of(0.0)
        assertThat(shape.max()).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `a falling line still falls once it is scaled`() {
        val shape = MilestoneCard.shapeOf(series(gramsPerDay = -100.0))

        assertThat(shape.first()).isGreaterThan(shape.last())
    }

    @Test
    fun `a flat line is drawn down the middle rather than divided by zero`() {
        val shape = MilestoneCard.shapeOf(series(gramsPerDay = 0.0))

        assertThat(shape).isNotEmpty()
        for (value in shape) assertThat(value).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `a long history is sampled down and still ends where it ended`() {
        val shape = MilestoneCard.shapeOf(series(days = 900), points = 60)

        assertThat(shape).hasSize(60)
        // The end of the line is the part somebody is sharing, so it has to be on the card.
        assertThat(shape.last()).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `too little history is no shape rather than a crash`() {
        assertThat(MilestoneCard.shapeOf(TrendSeries(emptyList(), 0.1))).isEmpty()
        assertThat(MilestoneCard.shapeOf(series(days = 1))).isEmpty()
    }
}
