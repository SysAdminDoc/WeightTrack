package com.weighttrack.core.medication

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SiteRotationTest {

    @Test
    fun `the first injection is offered the first site`() {
        assertThat(SiteRotation.next(emptyList())).isEqualTo(InjectionSite.ABDOMEN_LEFT)
    }

    @Test
    fun `the next site is never the one just used`() {
        InjectionSite.entries.forEach { site ->
            assertThat(SiteRotation.next(listOf(site))).isNotEqualTo(site)
        }
    }

    @Test
    fun `following the suggestion visits every site before repeating one`() {
        val used = mutableListOf<InjectionSite>()
        repeat(InjectionSite.entries.size) { used.add(0, SiteRotation.next(used)) }

        assertThat(used.toSet()).containsExactlyElementsIn(InjectionSite.entries)
    }

    @Test
    fun `somebody who has been using one spot is sent somewhere they have not`() {
        // The case the feature exists for. Weeks of the same site thickens the tissue under it
        // and absorption from a thickened site is slower, which reads as a week that worked less
        // well for no reason.
        val stuck = List(8) { InjectionSite.ABDOMEN_LEFT }

        val suggested = SiteRotation.next(stuck)

        assertThat(suggested).isNotEqualTo(InjectionSite.ABDOMEN_LEFT)
    }

    @Test
    fun `the site used longest ago is the one offered`() {
        // Newest first: arm was last week, thigh the week before, abdomen a while back.
        val recent = listOf(
            InjectionSite.UPPER_ARM_LEFT,
            InjectionSite.THIGH_RIGHT,
            InjectionSite.ABDOMEN_LEFT,
        )

        // The three never used come first, and among those the one following the last used.
        assertThat(SiteRotation.next(recent)).isEqualTo(InjectionSite.ABDOMEN_RIGHT)
    }

    @Test
    fun `no two suggestions in a row land on the same side of the body`() {
        val used = mutableListOf<InjectionSite>()
        val walked = (0 until InjectionSite.entries.size * 2).map {
            SiteRotation.next(used).also { site -> used.add(0, site) }
        }

        walked.zipWithNext().forEach { (first, second) ->
            val sameSide = first.name.endsWith("LEFT") == second.name.endsWith("LEFT")
            assertThat(sameSide).isFalse()
        }
    }
}
