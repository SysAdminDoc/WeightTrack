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
import com.weighttrack.ble.ScaleScanEvent
import com.weighttrack.ble.ScaleScanner
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.scale.ScaleReading
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
) {
    /** Whether the reading needs a yes before it is recorded. */
    val needsConfirming: Boolean
        get() = reading != null && match != null && !ScaleReadingRouter.recordsWithoutAsking(match)
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
    private val settingsRepository: SettingsRepository,
    private val surfaceUpdater: SurfaceUpdater,
) : ViewModel() {

    private val _state = MutableStateFlow(ScaleUiState())
    val state: StateFlow<ScaleUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var lastKnownGrams: Int? = null

    val requiredPermissions: List<String> get() = scanner.requiredPermissions()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            lastKnownGrams = weightRepository.latest()?.grams
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
        _state.update {
            it.copy(
                stage = ScaleStage.SEARCHING,
                problem = null,
                devices = emptyList(),
                reading = null,
                match = null,
                liveGrams = null,
                savedGrams = null,
            )
        }
        scanJob?.cancel()
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
                val remembered = settingsRepository.settings.first().scaleAddress
                if (
                    event.device.kind == ScaleKind.STANDARD_SERVICE &&
                    event.device.address == remembered &&
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
                    onReading(event.broadcast.reading, event.device)
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
            connection.connect(device.address).collect { event ->
                when (event) {
                    is ScaleConnectionEvent.Connected -> Unit
                    is ScaleConnectionEvent.Measured -> onReading(event.reading, device)
                    is ScaleConnectionEvent.Failed -> _state.update {
                        // A reading already in hand outlives the connection dropping: the scale
                        // hanging up right after sending is normal behaviour, not a failure.
                        if (it.reading != null) {
                            it
                        } else {
                            it.copy(stage = ScaleStage.BLOCKED, problem = event.reason)
                        }
                    }
                }
            }
        }
    }

    private suspend fun onReading(reading: ScaleReading, device: ScaleDevice) {
        if (_state.value.reading != null) return
        val match = ScaleReadingRouter.match(reading.grams, lastKnownGrams)
        if (match == ScaleMatch.IMPLAUSIBLE) return

        scanJob?.cancel()
        connectJob?.cancel()
        settingsRepository.setScale(device.address, device.name)
        _state.update {
            it.copy(
                stage = ScaleStage.MEASURED,
                reading = reading,
                match = match,
                liveGrams = null,
                connectedTo = device,
                rememberedName = device.name,
            )
        }
        if (ScaleReadingRouter.recordsWithoutAsking(match)) save()
    }

    /** Records the reading. Also the yes to "that does not look like you". */
    fun save() {
        val reading = _state.value.reading ?: return
        viewModelScope.launch {
            weightRepository.add(
                grams = reading.grams,
                // Recorded as of now, not the scale's own clock. Scale clocks are wrong far
                // more often than they are right, and the person is standing on it.
                bodyFatPercent = reading.bodyFatPercent,
                source = EntrySource.SCALE,
            )
            surfaceUpdater.refresh()
            _state.update { it.copy(stage = ScaleStage.SAVED, savedGrams = reading.grams) }
        }
    }

    /** The no to "that does not look like you". Nothing is recorded and the scan starts again. */
    fun discard() {
        start()
    }

    fun forgetScale() {
        viewModelScope.launch {
            settingsRepository.setScale(null, null)
            _state.update { it.copy(rememberedName = null) }
            start()
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        connectJob?.cancel()
        super.onCleared()
    }
}
