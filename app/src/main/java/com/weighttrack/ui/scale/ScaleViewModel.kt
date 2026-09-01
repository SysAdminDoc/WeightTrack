package com.weighttrack.ui.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.ble.ScaleConnection
import com.weighttrack.ble.ScaleConnectionEvent
import com.weighttrack.ble.ScaleDevice
import com.weighttrack.ble.ScaleKind
import com.weighttrack.ble.ScaleMatch
import com.weighttrack.ble.ScaleProblem
import com.weighttrack.ble.ScaleReadingRouter
import com.weighttrack.ble.ScaleRouting
import com.weighttrack.ble.ScaleScanEvent
import com.weighttrack.ble.ScaleScanner
import com.weighttrack.core.model.BodyComposition
import com.weighttrack.core.model.CompositionQuality
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.scale.AssembledReading
import com.weighttrack.core.scale.ScaleReading
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the weigh-in has got to. */
enum class ScaleStage {
    /** Waiting for the person to allow a scan, or to switch Bluetooth on. */
    BLOCKED,

    /** Looking for scales. */
    SEARCHING,

    /** Found one and waiting for a weight. This is the "step on the scale" moment. */
    WAITING_FOR_WEIGHT,

    /** A weight arrived and is ready to record, or is waiting to be confirmed. */
    MEASURED,

    /** Recorded. */
    SAVED,
}

data class ScaleUiState(
    val stage: ScaleStage = ScaleStage.SEARCHING,
    val problem: ScaleProblem? = null,
    val devices: List<ScaleDevice> = emptyList(),
    val connectedTo: ScaleDevice? = null,
    val rememberedName: String? = null,
    /** The number moving about while someone settles, which is not a reading yet. */
    val liveGrams: Int? = null,
    val reading: ScaleReading? = null,
    val match: ScaleMatch? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val savedGrams: Int? = null,
    /**
     * The profile this reading looks like, when it is not the one on screen.
     *
     * A shared scale is the reason profiles exist. Filing a reading under whoever happens to be
     * open, when it plainly belongs to somebody else in the house, is the mistake worth avoiding.
     */
    val suggestedProfile: Profile? = null,
    /**
     * The people this reading could equally be, when the weight cannot tell them apart.
     *
     * Empty when it can. Two adults within a couple of kilograms of each other is an ordinary
     * household, and the app used to file every weigh-in under whoever was marginally nearer
     * that morning: a step change in one trend, a hole in the other, and nothing said so.
     */
    val ambiguousProfiles: List<Profile> = emptyList(),
) {
    /** Whether the reading needs a yes before it is recorded. */
    val needsConfirming: Boolean
        get() = reading != null &&
            suggestedProfile == null &&
            ambiguousProfiles.isEmpty() &&
            match != null &&
            !ScaleReadingRouter.recordsWithoutAsking(match)
}

/**
 * The weigh-in.
 *
 * The whole thing happens while the screen is open on purpose. A background service that
 * recorded whatever walked past the scale is how a partner's weight ends up in someone else's
 * trend, and it would also mean holding a scan permission the app does not otherwise need.
 */
@HiltViewModel
class ScaleViewModel @Inject constructor(
    private val scanner: ScaleScanner,
    private val connection: ScaleConnection,
    private val weightRepository: WeightRepository,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val surfaceUpdater: SurfaceUpdater,
    private val haptics: Haptics,
) : ViewModel() {

    private val _state = MutableStateFlow(ScaleUiState())
    val state: StateFlow<ScaleUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var settleJob: Job? = null
    private var lastKnownGrams: Int? = null
    private var rememberedAddress: String? = null
    private var rememberedActiveId: Long = 1L
    private var rejectedGrams: Int? = null

    /**
     * Whether the reading in hand has already been answered for.
     *
     * A latch rather than a flag held for the length of the write. Two taps on a picker land
     * before the first insert finishes, and a scale that has been answered for goes on
     * advertising the same settled frame afterwards, so "in flight" is too short a window. It
     * is let go when a fresh reading arrives, which is the only thing there is left to record.
     */
    private var answered = false
    private var lastKnownByProfile: Map<Long, Int> = emptyMap()
    private var profiles: List<Profile> = emptyList()

    val requiredPermissions: List<String> get() = scanner.requiredPermissions()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            lastKnownGrams = weightRepository.latest()?.grams
            profiles = profileRepository.observeAll().first()
            lastKnownByProfile = weightRepository.latestPerProfile().mapValues { it.value.grams }
            // Read once. Looking it up per scan result would put a datastore round trip on the
            // path an advertising scale hits several times a second.
            rememberedAddress = settings.scaleAddress
            rememberedActiveId = profileRepository.activeId()
            _state.update {
                it.copy(weightUnit = settings.weightUnit, rememberedName = settings.scaleName)
            }
            start()
        }
    }

    /** Begins, or begins again after the person has granted the permission. */
    fun start() {
        val problem = scanner.problem()
        if (problem != null) {
            _state.update { it.copy(stage = ScaleStage.BLOCKED, problem = problem) }
            return
        }
        answered = false
        _state.update {
            it.copy(
                stage = ScaleStage.SEARCHING,
                problem = null,
                devices = emptyList(),
                reading = null,
                match = null,
                suggestedProfile = null,
                // A question nobody answered goes with the reading it was about. Left behind,
                // it is a picker whose taps land on a reading that is no longer there.
                ambiguousProfiles = emptyList(),
                liveGrams = null,
                savedGrams = null,
            )
        }
        scanJob?.cancel()
        settleJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scan().collect(::onScanEvent)
        }
    }

    private suspend fun onScanEvent(event: ScaleScanEvent) {
        when (event) {
            is ScaleScanEvent.Failed ->
                _state.update { it.copy(stage = ScaleStage.BLOCKED, problem = event.reason) }

            is ScaleScanEvent.Found -> {
                _state.update { current ->
                    if (current.devices.any { it.address == event.device.address }) {
                        current
                    } else {
                        current.copy(devices = current.devices + event.device)
                    }
                }
                if (
                    event.device.kind != ScaleKind.BROADCAST &&
                    event.device.address == rememberedAddress &&
                    _state.value.connectedTo == null
                ) {
                    connectTo(event.device)
                }
            }

            is ScaleScanEvent.Broadcast -> {
                // A broadcasting scale needs no connection at all, so the first weight off the
                // air is the whole conversation.
                _state.update {
                    it.copy(
                        stage = if (it.reading == null) ScaleStage.WAITING_FOR_WEIGHT else it.stage,
                        connectedTo = event.device,
                        liveGrams = event.broadcast.reading.grams.takeIf { g -> g > 0 },
                    )
                }
                if (event.broadcast.isFinal) {
                    // A broadcasting scale sends everything it has in one frame, so nothing
                    // better is coming.
                    onReading(
                        AssembledReading(event.broadcast.reading, revisesPrevious = false),
                        event.device,
                    )
                }
            }
        }
    }

    /** Connects to a scale that has to be talked to rather than listened to. */
    fun connectTo(device: ScaleDevice) {
        connectJob?.cancel()
        _state.update {
            it.copy(stage = ScaleStage.WAITING_FOR_WEIGHT, connectedTo = device, problem = null)
        }
        connectJob = viewModelScope.launch {
            connection.connect(device).collect { event ->
                when (event) {
                    is ScaleConnectionEvent.Connected -> Unit
                    is ScaleConnectionEvent.Live -> _state.update {
                        if (it.reading == null) it.copy(liveGrams = event.grams) else it
                    }
                    is ScaleConnectionEvent.Measured -> onReading(event.assembled, device)
                    is ScaleConnectionEvent.Failed -> {
                        // A reading already in hand outlives the connection dropping: the scale
                        // hanging up right after sending is normal behaviour, not a failure, and
                        // it means nothing more is coming, so there is nothing left to wait for.
                        if (_state.value.reading != null) {
                            settle(device, immediately = true)
                        } else {
                            _state.update {
                                it.copy(stage = ScaleStage.BLOCKED, problem = event.reason)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A reading came off the scale.
     *
     * Deliberately not a suspending function called from inside the scan or connection
     * collector. The next thing that has to happen is stopping those very collectors, and a
     * coroutine that cancels itself and then suspends never reaches its own next line.
     */
    private fun onReading(assembled: AssembledReading, device: ScaleDevice) {
        // A second, unrelated weigh-in is ignored; a better version of this one is not.
        if (_state.value.reading != null && !assembled.revisesPrevious) return
        // Somebody has already said this one is not theirs. A scale goes on broadcasting the
        // same settled weight for a while, so without this the rejected reading comes straight
        // back the moment the search starts again and cannot be got rid of.
        if (assembled.reading.grams == rejectedGrams) return

        val match = ScaleReadingRouter.match(assembled.reading.grams, lastKnownGrams)
        if (match == ScaleMatch.IMPLAUSIBLE) return

        // Once, on the frame that is actually being kept, and after the frame has survived the
        // plausibility check. Before it, a cat on the scale buzzed and filed nothing, and then
        // buzzed again for the person who stood on it next: one weigh-in, two buzzes, the first
        // of them for a reading the app threw away.
        val first = _state.value.reading == null
        // A reading of its own to record, so whatever was said about the last one is spent.
        answered = false

        // On a shared scale the weight is the only thing that says who stood on it.
        val activeId = profiles.firstOrNull { it.id == rememberedActiveId }?.id
        val routing = ScaleReadingRouter.route(assembled.reading.grams, lastKnownByProfile)
        val suggested = (routing as? ScaleRouting.Clear)?.profileId
            ?.takeIf { it != activeId }
            ?.let { id -> profiles.firstOrNull { it.id == id } }
        // Too close to call. Asked rather than guessed: the scale cannot tell them apart and
        // neither can the app, and a wrong guess is invisible on both people's trends.
        val ambiguous = (routing as? ScaleRouting.Ambiguous)
            ?.profileIds
            ?.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
            .orEmpty()

        // A body composition scale sends the weight and then the fat a heartbeat later, and
        // buzzing again for the second half would say a second weigh-in had happened.
        if (first) haptics.weighInLanded()

        _state.update {
            it.copy(
                stage = ScaleStage.MEASURED,
                reading = assembled.reading,
                match = match,
                suggestedProfile = suggested,
                ambiguousProfiles = ambiguous,
                liveGrams = null,
                connectedTo = device,
                rememberedName = device.name,
            )
        }
        settle(device, immediately = false)
    }

    /**
     * Waits a moment before recording, in case a better reading is a heartbeat behind.
     *
     * A body composition scale sends the weight and then the body fat, so recording the instant
     * the weight lands would store a weigh-in with the composition missing. The wait restarts
     * each time a better reading arrives, and is skipped when the scale hangs up, because then
     * nothing more is coming.
     */
    private fun settle(device: ScaleDevice, immediately: Boolean) {
        settleJob?.cancel()
        // On viewModelScope on purpose: this outlives the scan and connection jobs it stops.
        settleJob = viewModelScope.launch {
            if (!immediately) delay(SETTLE_MILLIS)
            scanJob?.cancel()
            connectJob?.cancel()
            settingsRepository.setScale(device.address, device.name)
            rememberedAddress = device.address
            val match = _state.value.match ?: return@launch
            if (_state.value.suggestedProfile != null) return@launch
            // A question on screen is not answered by waiting.
            if (_state.value.ambiguousProfiles.isNotEmpty()) return@launch
            if (ScaleReadingRouter.recordsWithoutAsking(match)) save()
        }
    }

    /**
     * Everything the scale said beyond the weight, with what it was and how it read it.
     *
     * Kept whole. The parsers have always carried muscle mass, lean mass, water, impedance and
     * basal metabolism up to this screen, and saving took the weight and the body-fat
     * percentage and dropped the rest without a word.
     *
     * Null for a scale that only weighs, which is a complete reading and not a failed one.
     */
    private fun compositionOf(reading: ScaleReading): BodyComposition? {
        val device = _state.value.connectedTo
        return BodyComposition(
            muscleMassGrams = reading.muscleMassGrams,
            fatFreeMassGrams = reading.fatFreeMassGrams,
            softLeanMassGrams = reading.softLeanMassGrams,
            bodyWaterMassGrams = reading.bodyWaterMassGrams,
            musclePercent = reading.musclePercent,
            impedanceOhms = reading.impedanceOhms,
            basalMetabolismKcal = reading.basalMetabolismKcal,
            scaleBmi = reading.bmi,
            heightMm = reading.heightMm,
            scaleUserId = reading.scaleUserId,
            device = device?.name,
            protocol = device?.kind?.name,
            // The scale sent these. How it arrived at them is the manufacturer's own
            // arithmetic, unpublished and different between makes, which is exactly what this
            // records rather than hides.
            quality = CompositionQuality.REPORTED_BY_SCALE,
        ).takeIf { !it.isEmpty }
    }

    /** Records the reading. Also the yes to "that does not look like you". */
    fun save() {
        val reading = _state.value.reading ?: return
        if (answered) return
        answered = true
        viewModelScope.launch {
            weightRepository.add(
                grams = reading.grams,
                // Recorded as of now, not the scale's own clock. Scale clocks are wrong far
                // more often than they are right, and the person is standing on it.
                bodyFatPercent = reading.bodyFatPercent,
                source = EntrySource.SCALE,
                composition = compositionOf(reading),
            )
            // Recorded, so say so before touching the widgets and the watch. Those are a
            // follow-up, and a slow one must not hold up the confirmation for a weight that is
            // already in the log.
            _state.update {
                it.copy(
                    stage = ScaleStage.SAVED,
                    savedGrams = reading.grams,
                    // Answered, so the offer to file it under somebody else goes away rather
                    // than sitting there ready to record the same weight twice.
                    suggestedProfile = null,
                )
            }
            surfaceUpdater.refresh()
        }
    }

    /**
     * Files the reading under the person it looks like.
     *
     * Recorded against them without switching the app over. Somebody weighing themselves on the
     * family scale has not asked to start looking at their partner's history, and leaving them
     * on it would send the next entry, the widgets and the watch to the wrong person.
     */
    fun saveToSuggested() {
        val suggested = _state.value.suggestedProfile ?: return
        saveTo(suggested.id)
    }

    /**
     * Files the reading under the person somebody picked.
     *
     * Exactly once, whatever happens: the picker goes at once and a second call while the first
     * is still in flight does nothing. Only that person's history is touched, and their last
     * known weight moving is what makes the next morning easier to tell apart.
     */
    fun saveTo(profileId: Long) {
        val reading = _state.value.reading ?: return
        if (answered) return
        answered = true
        // The reading stays put and the weight is remembered as answered. A broadcast scale goes
        // on advertising the same settled frame for a second or so after it is filed, and the
        // only thing that stopped it re-opening this picker was the reading being non-null:
        // clearing it let the question come back for a weight already recorded, and the second
        // answer wrote a second row under a name of its own that nothing would ever dedupe.
        rejectedGrams = reading.grams
        _state.update { it.copy(ambiguousProfiles = emptyList(), suggestedProfile = null) }
        viewModelScope.launch {
            weightRepository.addFor(
                profileId = profileId,
                grams = reading.grams,
                timestamp = java.time.Instant.now(),
                bodyFatPercent = reading.bodyFatPercent,
                source = EntrySource.SCALE,
                composition = compositionOf(reading),
            )
            _state.update {
                it.copy(
                    stage = ScaleStage.SAVED,
                    savedGrams = reading.grams,
                    suggestedProfile = null,
                    ambiguousProfiles = emptyList(),
                )
            }
            surfaceUpdater.refresh()
        }
    }

    /** The no to "that does not look like you". Nothing is recorded and the scan starts again. */
    fun discard() {
        rejectedGrams = _state.value.reading?.grams
        start()
    }

    fun forgetScale() {
        viewModelScope.launch {
            rejectedGrams = null
            settingsRepository.setScale(null, null)
            _state.update { it.copy(rememberedName = null) }
            start()
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        connectJob?.cancel()
        settleJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** How long to wait for a body composition to follow a weight. */
        const val SETTLE_MILLIS = 1_200L
    }
}
