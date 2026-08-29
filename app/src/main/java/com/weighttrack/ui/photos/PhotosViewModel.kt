package com.weighttrack.ui.photos

import android.net.Uri
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
) : ViewModel() {

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    private val _pendingCapture = MutableStateFlow<File?>(null)
    val pendingCapture: StateFlow<File?> = _pendingCapture.asStateFlow()

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

    fun toggleSelection(id: Long) = selectedIds.update { current ->
        when {
            id in current -> current - id
            // Picking a third replaces the older of the two, so tapping never feels blocked.
            current.size >= 2 -> setOf(current.last(), id)
            else -> current + id
        }
    }

    fun clearSelection() = selectedIds.update { emptySet() }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            photoRepository.importFrom(uri, weightGrams = currentWeightGrams())
        }
    }

    /** Hands back the file the camera should write into, remembered until the result arrives. */
    fun prepareCapture(): File = photoRepository.newCaptureFile().also { _pendingCapture.value = it }

    fun onCaptureResult(success: Boolean) {
        val file = _pendingCapture.value ?: return
        _pendingCapture.value = null
        viewModelScope.launch {
            if (success) {
                photoRepository.record(file, weightGrams = currentWeightGrams())
            } else {
                // A cancelled capture leaves an empty file behind that would otherwise sit
                // in storage forever.
                file.delete()
            }
        }
    }

    fun delete(photo: ProgressPhoto) {
        viewModelScope.launch {
            photoRepository.delete(photo)
            selectedIds.update { it - photo.id }
        }
    }

    private suspend fun currentWeightGrams(): Int? = weightRepository.latest()?.grams
}
