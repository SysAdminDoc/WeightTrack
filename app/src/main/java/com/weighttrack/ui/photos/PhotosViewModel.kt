package com.weighttrack.ui.photos

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.data.repo.ProgressPhoto
import com.weighttrack.data.repo.ProgressPhotoRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.domain.ProgressCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject

data class PhotosUiState(
    val photos: List<ProgressPhoto> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val weightUnit: com.weighttrack.core.model.WeightUnit = com.weighttrack.core.model.WeightUnit.KG,
) {
    /**
     * The two photos being compared, oldest first.
     *
     * Only ever two: a side-by-side is the comparison people actually want, and more than two
     * on a phone screen is a contact sheet, not a comparison.
     */
    val comparison: Pair<ProgressPhoto, ProgressPhoto>?
        get() {
            if (selectedIds.size != 2) return null
            val chosen = photos.filter { it.id in selectedIds }.sortedBy { it.timestamp }
            return if (chosen.size == 2) chosen[0] to chosen[1] else null
        }
}

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photoRepository: ProgressPhotoRepository,
    private val weightRepository: WeightRepository,
    progressCalculator: ProgressCalculator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingCapture = PendingPhotoCaptureState(savedStateHandle)
    private var recordingCapturePath: String? = null

    val state: StateFlow<PhotosUiState> = combine(
        photoRepository.observeAll(),
        selectedIds,
        progressCalculator.observe(),
    ) { photos, selection, snapshot ->
        PhotosUiState(
            photos = photos,
            // A selection can outlive the photo it pointed at once one is deleted.
            selectedIds = selection intersect photos.map { it.id }.toSet(),
            weightUnit = snapshot.settings.weightUnit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosUiState())

    init {
        pendingCapture.pending()?.let { file ->
            when {
                !file.exists() -> pendingCapture.clear(file)
                file.length() > 0L -> recordCapture(file)
                // An empty file can mean the restored camera activity is still writing it.
                // Its result callback will finish or discard it when control returns here.
            }
        }
    }

    fun toggleSelection(id: Long) = selectedIds.update { current ->
        when {
            id in current -> current - id
            // Picking a third replaces the older of the two, so tapping never feels blocked.
            current.size >= 2 -> setOf(current.last(), id)
            else -> current + id
        }
    }

    fun clearSelection() = selectedIds.update { emptySet() }

    /**
     * Files a picked image under the day it was taken, not the day it was imported.
     *
     * The weight comes from the same moment, so importing a year of old photos lines each one up
     * with what the scale said at the time instead of stamping the whole lot with today.
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val takenAt = photoRepository.takenAt(uri) ?: Instant.now()
            photoRepository.importFrom(
                uri,
                weightGrams = weightGramsAt(takenAt),
                timestamp = takenAt,
            )
        }
    }

    /** Hands back the file the camera should write into, remembered until the result arrives. */
    fun prepareCapture(): File = photoRepository.newCaptureFile().also(pendingCapture::remember)

    fun onCaptureResult(success: Boolean) {
        val file = pendingCapture.pending() ?: return
        if (success) {
            recordCapture(file)
        } else {
            viewModelScope.launch {
                // A cancelled capture leaves an empty file behind that would otherwise sit
                // in storage forever.
                withContext(Dispatchers.IO) { file.delete() }
                pendingCapture.clear(file)
            }
        }
    }

    fun delete(photo: ProgressPhoto) {
        viewModelScope.launch {
            photoRepository.delete(photo)
            selectedIds.update { it - photo.id }
        }
    }

    private suspend fun weightGramsAt(at: Instant): Int? =
        weightRepository.latestAtOrBefore(at)?.grams

    private fun recordCapture(file: File) {
        if (recordingCapturePath == file.absolutePath) return
        recordingCapturePath = file.absolutePath
        viewModelScope.launch {
            try {
                val at = Instant.now()
                photoRepository.record(file, weightGrams = weightGramsAt(at), timestamp = at)
                // Clear only after the database write returns. If this coroutine is cancelled
                // by process death, the restored ViewModel sees the path and retries it.
                pendingCapture.clear(file)
            } finally {
                recordingCapturePath = null
            }
        }
    }
}

internal const val PENDING_CAPTURE_PATH_KEY = "pendingCapturePath"

internal class PendingPhotoCaptureState(
    private val savedStateHandle: SavedStateHandle,
) {
    fun remember(file: File) {
        savedStateHandle[PENDING_CAPTURE_PATH_KEY] = file.absolutePath
    }

    fun pending(): File? = savedStateHandle.get<String>(PENDING_CAPTURE_PATH_KEY)?.let(::File)

    fun clear(file: File) {
        if (pending()?.absolutePath == file.absolutePath) {
            savedStateHandle.remove<String>(PENDING_CAPTURE_PATH_KEY)
        }
    }
}
