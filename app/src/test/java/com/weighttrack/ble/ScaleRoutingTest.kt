package com.weighttrack.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which of two people in a household a weight belongs to.
 *
 * "Nearest last weight inside eight kilograms" was treated as an answer however close the
 * second-nearest was. Two adults within a couple of kilograms of each other is an ordinary
 * household, and in one the app was filing every weigh-in under whoever happened to be
 * marginally nearer that morning: a step change in one person's trend, a hole in the other's,
 * and nothing on any screen saying a choice had been made at all.
 */
class ScaleRoutingTest {

    private val alice = 1L
    private val bob = 2L

    @Test
    fun `one person clearly nearest is filed without asking`() {
        // The whole point of a family scale. Ten kilograms apart is not a question.
        val routing = ScaleReadingRouter.route(80_000, mapOf(alice to 80_400, bob to 92_000))

        assertThat(routing).isEqualTo(ScaleRouting.Clear(alice))
    }

    @Test
    fun `two people a hair apart are asked about`() {
        val routing = ScaleReadingRouter.route(80_000, mapOf(alice to 80_400, bob to 80_900))

        assertThat(routing).isInstanceOf(ScaleRouting.Ambiguous::class.java)
        // Nearest first, so a picker leads with the likeliest.
        assertThat((routing as ScaleRouting.Ambiguous).profileIds).containsExactly(alice, bob)
            .inOrder()
    }

    @Test
    fun `a clear margin is still clear even when both are in range`() {
        // Both inside the eight-kilogram tolerance, but one is four kilograms nearer. That is a
        // margin, and asking about it would be asking a question with an obvious answer.
        val routing = ScaleReadingRouter.route(80_000, mapOf(alice to 80_200, bob to 84_500))

        assertThat(routing).isEqualTo(ScaleRouting.Clear(alice))
    }

    @Test
    fun `nobody near enough is nobody`() {
        val routing = ScaleReadingRouter.route(60_000, mapOf(alice to 80_000, bob to 92_000))

        assertThat(routing).isEqualTo(ScaleRouting.Unknown)
    }

    @Test
    fun `a household of one is never a question`() {
        assertThat(ScaleReadingRouter.route(80_000, mapOf(alice to 80_400)))
            .isEqualTo(ScaleRouting.Clear(alice))
    }

    @Test
    fun `a weight nobody has is nobody's`() {
        assertThat(ScaleReadingRouter.route(5_000, mapOf(alice to 80_000)))
            .isEqualTo(ScaleRouting.Unknown)
        assertThat(ScaleReadingRouter.route(500_000, mapOf(alice to 80_000)))
            .isEqualTo(ScaleRouting.Unknown)
    }

    @Test
    fun `a dead heat asks rather than picking by row number`() {
        // Exactly the same distance each way. Whatever the map's order, this is a question.
        val routing = ScaleReadingRouter.route(80_000, mapOf(alice to 79_000, bob to 81_000))

        assertThat(routing).isInstanceOf(ScaleRouting.Ambiguous::class.java)
        assertThat((routing as ScaleRouting.Ambiguous).profileIds).containsExactly(alice, bob)
    }

    @Test
    fun `only the people it could be are offered, not the whole household`() {
        val carol = 3L
        val routing = ScaleReadingRouter.route(
            80_000,
            mapOf(alice to 80_100, bob to 80_500, carol to 86_000),
        )

        // Carol is in range and nowhere near. Offering her would turn a two-way question into a
        // list, which is how a picker stops being read.
        assertThat((routing as ScaleRouting.Ambiguous).profileIds).containsExactly(alice, bob)
    }

    @Test
    fun `the margin itself counts as clear`() {
        // Exactly two kilograms further away is a margin, not a tie: a boundary that refuses its
        // own value makes every explanation of it off by one.
        val routing = ScaleReadingRouter.route(80_000, mapOf(alice to 80_000, bob to 82_000))

        assertThat(routing).isEqualTo(ScaleRouting.Clear(alice))
    }

    @Test
    fun `the old answer is still the answer wherever it was a good one`() {
        // `owner` is what the rest of the app used and its behaviour has not moved for the
        // unambiguous cases, so nothing that relied on it has quietly changed meaning.
        val nearest = mapOf(alice to 80_400, bob to 92_000)

        assertThat(ScaleReadingRouter.owner(80_000, nearest)).isEqualTo(alice)
        assertThat(ScaleReadingRouter.route(80_000, nearest))
            .isEqualTo(ScaleRouting.Clear(checkNotNull(ScaleReadingRouter.owner(80_000, nearest))))
    }
}
