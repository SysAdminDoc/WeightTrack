package com.weighttrack.core.medication

import com.weighttrack.core.math.TrendSeries
import java.time.LocalDate

/**
 * What goes in the report somebody takes to an appointment.
 *
 * Three things and nothing else: the injections, what they felt, and where the weight went. No
 * food, no water, no fasting, no notes on a weigh-in, no name and no address. A person handing a
 * page across a desk is handing over whatever is on it, and everything on it should be something
 * they meant to share.
 *
 * The structure is worked out here, with no Android in sight, so the rule above can be a test
 * rather than a hope. Turning it into a page, in the reader's own language, happens in the app.
 */
object MedicationReport {

    data class Dose(
        val date: LocalDate,
        val drug: GlpDrug,
        val milligrams: Double,
        val site: InjectionSite,
    )

    data class Effect(
        val date: LocalDate,
        val kind: SideEffectKind,
        val severity: SideEffectSeverity,
    )

    /** One row of the weight column: the smoothed line, not a morning's reading. */
    data class TrendPoint(val date: LocalDate, val trendGrams: Int)

    data class Content(
        val from: LocalDate,
        val to: LocalDate,
        val doses: List<Dose>,
        val effects: List<Effect>,
        val trend: List<TrendPoint>,
    ) {
        val isEmpty: Boolean get() = doses.isEmpty() && effects.isEmpty() && trend.isEmpty()
    }

    /**
     * Everything inside the chosen range, in order.
     *
     * Both ends are included: somebody who picks the first to the last of a month means the whole
     * month. A range the wrong way round is read the way it was plainly meant rather than coming
     * back empty.
     */
    fun build(
        from: LocalDate,
        to: LocalDate,
        doses: List<Dose>,
        effects: List<Effect>,
        series: TrendSeries,
    ): Content {
        val first = minOf(from, to)
        val last = maxOf(from, to)
        fun LocalDate.inRange() = !isBefore(first) && !isAfter(last)
        return Content(
            from = first,
            to = last,
            doses = doses.filter { it.date.inRange() }.sortedBy { it.date },
            effects = effects.filter { it.date.inRange() }.sortedBy { it.date },
            // The trend rather than the raw readings. It is the line the weight actually followed
            // and it does not invite a conversation about one heavy Tuesday.
            trend = series.points
                .filter { it.date.inRange() }
                .map { TrendPoint(it.date, it.trendGrams.toInt()) },
        )
    }
}
