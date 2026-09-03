package com.weighttrack.ui.medication

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.MedicationLevel
import com.weighttrack.core.medication.MedicationReport
import com.weighttrack.core.medication.ProteinTarget
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.io.MedicationPdfWriter
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.MedicationDose
import com.weighttrack.data.repo.MedicationRepository
import com.weighttrack.data.repo.SideEffect
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

data class MedicationUiState(
    val doses: List<MedicationDose> = emptyList(),
    val sideEffects: List<SideEffect> = emptyList(),
    /** Where the rotation says the next one should go. */
    val suggestedSite: InjectionSite = InjectionSite.ABDOMEN_LEFT,
    /** The medicine to offer first, which is whatever the last dose was. */
    val drug: GlpDrug = GlpDrug.SEMAGLUTIDE,
    val lastMilligrams: Double = 0.5,
    /** How much is roughly still in the body, one point every six hours. Empty for OTHER. */
    val level: List<MedicationLevel.Point> = emptyList(),
    val proteinGrams: IntRange? = null,
    val unit: WeightUnit = WeightUnit.KG,
)

/**
 * The injection log.
 *
 * Never reached unless the toggle is on, and it holds nothing that any other screen reads, so a
 * phone with the feature off behaves exactly as it did before this existed.
 */
@HiltViewModel
class MedicationViewModel @Inject constructor(
    private val medication: MedicationRepository,
    private val settingsRepository: SettingsRepository,
    private val progress: ProgressCalculator,
    private val pdf: MedicationPdfWriter,
    private val strings: AppStrings,
    private val undoOffers: UndoCoordinator,
) : ViewModel() {

    private val suggested = MutableStateFlow(InjectionSite.ABDOMEN_LEFT)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<MedicationUiState> = combine(
        medication.observeDoses(),
        medication.observeSideEffects(),
        suggested,
        settingsRepository.settings,
        progress.observe(),
    ) { doses, effects, site, settings, snapshot ->
        val drug = doses.firstOrNull()?.drug ?: GlpDrug.SEMAGLUTIDE
        MedicationUiState(
            doses = doses,
            sideEffects = effects,
            suggestedSite = site,
            drug = drug,
            lastMilligrams = doses.firstOrNull()?.milligrams ?: 0.5,
            level = curveFor(drug, doses),
            // From the trend rather than the last reading, for the same reason every other
            // derived number here is: one mistyped morning must not move a target.
            proteinGrams = snapshot.series.latestTrendGrams
                ?.let { ProteinTarget.dailyGrams(it.roundToInt()) },
            unit = settings.weightUnit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationUiState())

    init {
        refreshSuggestion()
    }

    fun onScreenResumed() = refreshSuggestion()

    fun clearMessage() {
        _message.value = null
    }

    private fun refreshSuggestion() {
        viewModelScope.launch { suggested.value = medication.suggestedSite() }
    }

    fun addDose(
        drug: GlpDrug,
        milligrams: Double,
        site: InjectionSite,
        timestamp: Instant = Instant.now(),
        note: String? = null,
    ) {
        if (milligrams <= 0) return
        viewModelScope.launch {
            medication.addDose(drug, milligrams, site, timestamp, note)
            refreshSuggestion()
            _message.value = strings[R.string.medication_dose_saved]
        }
    }

    fun deleteDose(id: Long) {
        viewModelScope.launch {
            val removed = medication.deleteDose(id)
            refreshSuggestion()
            undoOffers.offer(removed, strings[R.string.medication_dose_removed]) {
                refreshSuggestion()
            }
        }
    }

    fun addSideEffect(
        kind: SideEffectKind,
        severity: SideEffectSeverity,
        timestamp: Instant = Instant.now(),
        note: String? = null,
    ) {
        viewModelScope.launch {
            medication.addSideEffect(kind, severity, timestamp, note)
            _message.value = strings[R.string.medication_effect_saved]
        }
    }

    fun deleteSideEffect(id: Long) {
        viewModelScope.launch {
            val removed = medication.deleteSideEffect(id)
            undoOffers.offer(removed, strings[R.string.medication_effect_removed]) {}
        }
    }

    /**
     * Writes the report for the last [REPORT_DAYS] days to wherever somebody picked.
     *
     * The range is deliberately a plain window rather than a picker: what a person is asked for
     * at an appointment is "how has it been going", and three months is the answer to that.
     */
    fun exportReport(uri: Uri, zone: ZoneId = ZoneId.systemDefault()) {
        viewModelScope.launch {
            val to = LocalDate.now(zone)
            val from = to.minusDays(REPORT_DAYS - 1)
            val snapshot = progress.observe().first()
            val content = MedicationReport.build(
                from = from,
                to = to,
                doses = medication.observeDoses().first().map {
                    MedicationReport.Dose(it.localDate, it.drug, it.milligrams, it.site)
                },
                effects = medication.observeSideEffects().first().map {
                    MedicationReport.Effect(it.localDate, it.kind, it.severity)
                },
                series = snapshot.series,
            )
            val written = pdf.write(uri, content, state.value.unit)
            _message.value = strings[
                if (written.isSuccess) {
                    R.string.medication_export_done
                } else {
                    R.string.medication_export_failed
                },
            ]
        }
    }

    private fun curveFor(drug: GlpDrug, doses: List<MedicationDose>): List<MedicationLevel.Point> {
        val halfLife = drug.halfLifeHours ?: return emptyList()
        if (doses.isEmpty()) return emptyList()
        val newest = doses.maxOf { it.timestamp.toEpochMilli() }
        val from = newest - LEVEL_WINDOW_DAYS * DAY_MILLIS
        return MedicationLevel.curve(
            doses = doses.map { MedicationLevel.Dose(it.timestamp.toEpochMilli(), it.milligrams) },
            fromUtcMillis = from,
            // A little past the last dose, so the line shows where it is going rather than
            // stopping dead on the day of the injection.
            toUtcMillis = newest + LEVEL_AHEAD_DAYS * DAY_MILLIS,
            halfLifeHours = halfLife,
        )
    }

    companion object {
        const val REPORT_DAYS = 90L
        private const val LEVEL_WINDOW_DAYS = 56L
        private const val LEVEL_AHEAD_DAYS = 7L
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
