package com.weighttrack.ui.medication

import androidx.annotation.StringRes
import com.weighttrack.R
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity

/**
 * The words for the injection log, in one place.
 *
 * Every one of these is a resource rather than a literal, so the whole feature can be translated
 * like the rest of the app, and so the report and the screen never disagree about what something
 * is called.
 */
@StringRes
fun drugLabel(drug: GlpDrug): Int = when (drug) {
    GlpDrug.SEMAGLUTIDE -> R.string.medication_drug_semaglutide
    GlpDrug.TIRZEPATIDE -> R.string.medication_drug_tirzepatide
    GlpDrug.DULAGLUTIDE -> R.string.medication_drug_dulaglutide
    GlpDrug.LIRAGLUTIDE -> R.string.medication_drug_liraglutide
    GlpDrug.OTHER -> R.string.medication_drug_other
}

@StringRes
fun siteLabel(site: InjectionSite): Int = when (site) {
    InjectionSite.ABDOMEN_LEFT -> R.string.medication_site_abdomen_left
    InjectionSite.ABDOMEN_RIGHT -> R.string.medication_site_abdomen_right
    InjectionSite.THIGH_LEFT -> R.string.medication_site_thigh_left
    InjectionSite.THIGH_RIGHT -> R.string.medication_site_thigh_right
    InjectionSite.UPPER_ARM_LEFT -> R.string.medication_site_arm_left
    InjectionSite.UPPER_ARM_RIGHT -> R.string.medication_site_arm_right
}

@StringRes
fun effectLabel(kind: SideEffectKind): Int = when (kind) {
    SideEffectKind.NAUSEA -> R.string.medication_effect_nausea
    SideEffectKind.VOMITING -> R.string.medication_effect_vomiting
    SideEffectKind.DIARRHOEA -> R.string.medication_effect_diarrhoea
    SideEffectKind.CONSTIPATION -> R.string.medication_effect_constipation
    SideEffectKind.HEARTBURN -> R.string.medication_effect_heartburn
    SideEffectKind.FATIGUE -> R.string.medication_effect_fatigue
    SideEffectKind.HEADACHE -> R.string.medication_effect_headache
    SideEffectKind.APPETITE_LOSS -> R.string.medication_effect_appetite
    SideEffectKind.INJECTION_SITE_REACTION -> R.string.medication_effect_site_reaction
    SideEffectKind.OTHER -> R.string.medication_effect_other
}

@StringRes
fun severityLabel(severity: SideEffectSeverity): Int = when (severity) {
    SideEffectSeverity.MILD -> R.string.medication_severity_mild
    SideEffectSeverity.MODERATE -> R.string.medication_severity_moderate
    SideEffectSeverity.SEVERE -> R.string.medication_severity_severe
}
