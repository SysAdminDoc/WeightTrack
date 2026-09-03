package com.weighttrack.core.medication

/**
 * The GLP-1 medicines this can work with, and how long each stays in the body.
 *
 * The half-lives are the ones on the manufacturers' own labels. They are what makes it possible
 * to draw the level between doses rather than only the doses themselves, which is the thing
 * somebody wants when a week feels different at one end than the other.
 *
 * Deliberately no brand names in the code. One molecule is sold under several, and the dose that
 * matters is the milligrams either way.
 */
enum class GlpDrug(
    /** Terminal half-life in hours, or null where there is no single published figure. */
    val halfLifeHours: Double?,
    /** How often it is normally taken. Used only to suggest a date, never to enforce one. */
    val usualIntervalDays: Int,
) {
    /** About a week. Ozempic, Wegovy, Rybelsus. */
    SEMAGLUTIDE(halfLifeHours = 165.0, usualIntervalDays = 7),

    /** About five days. Mounjaro, Zepbound. */
    TIRZEPATIDE(halfLifeHours = 120.0, usualIntervalDays = 7),

    /** About 4.7 days. Trulicity. */
    DULAGLUTIDE(halfLifeHours = 112.8, usualIntervalDays = 7),

    /** About thirteen hours, taken daily. Saxenda, Victoza. */
    LIRAGLUTIDE(halfLifeHours = 13.0, usualIntervalDays = 1),

    /**
     * Something else.
     *
     * Doses and side effects are still recorded and still drawn; the level between them is not,
     * because inventing a half-life would put a curve on the screen that means nothing.
     */
    OTHER(halfLifeHours = null, usualIntervalDays = 7),
}

/**
 * Where an injection went.
 *
 * The order is the rotation. Sides alternate within each area rather than running left to right,
 * so following the list never puts two in a row on the same side of the body.
 */
enum class InjectionSite {
    ABDOMEN_LEFT,
    THIGH_RIGHT,
    UPPER_ARM_LEFT,
    ABDOMEN_RIGHT,
    THIGH_LEFT,
    UPPER_ARM_RIGHT,
}

/** What somebody felt, and how much of it. */
enum class SideEffectKind {
    NAUSEA,
    VOMITING,
    DIARRHOEA,
    CONSTIPATION,
    HEARTBURN,
    FATIGUE,
    HEADACHE,
    APPETITE_LOSS,
    INJECTION_SITE_REACTION,
    OTHER,
}

enum class SideEffectSeverity { MILD, MODERATE, SEVERE }
