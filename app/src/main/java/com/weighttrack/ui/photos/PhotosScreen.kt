package com.weighttrack.ui.photos

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.repo.ProgressPhoto
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.format.WeightFormatter
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    state: PhotosUiState,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onPrepareCapture: () -> File,
    onCaptureResult: (Boolean) -> Unit,
    onDelete: (ProgressPhoto) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val context = LocalContext.current

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onImport) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> onCaptureResult(success) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Progress photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.selectedIds.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) { Text("Clear") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text("  Gallery")
                }
                OutlinedButton(
                    onClick = { takePhoto.launch(captureUri(context, onPrepareCapture())) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Text("  Camera")
                }
            }

            state.comparison?.let { (before, after) ->
                ComparisonCard(before, after, state.weightUnit, today)
            }

            if (state.photos.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.PhotoCamera,
                    title = "No photos yet",
                    message = "Photos stay on this phone and are never uploaded. Pick two to see them side by side with the weight change between them.",
                    modifier = Modifier.padding(top = 32.dp),
                )
                return@Column
            }

            Text(
                text = if (state.selectedIds.size == 1) {
                    "Pick one more to compare."
                } else {
                    "Tap two photos to compare them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.photos, key = { it.id }) { photo ->
                    PhotoTile(
                        photo = photo,
                        selected = photo.id in state.selectedIds,
                        unit = state.weightUnit,
                        today = today,
                        onClick = { onToggleSelection(photo.id) },
                        onLongClick = { onDelete(photo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    before: ProgressPhoto,
    after: ProgressPhoto,
    unit: WeightUnit,
    today: LocalDate,
) {
    SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionHeading("Side by side")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(before, after).forEach { photo ->
                Column(Modifier.weight(1f)) {
                    PhotoImage(
                        file = photo.file,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = DateFormatters.shortDate(photo.localDate, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = photo.weightGrams?.let { WeightFormatter.full(it, unit) } ?: "No weight",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val from = before.weightGrams
        val to = after.weightGrams
        if (from != null && to != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${WeightFormatter.delta((to - from).toDouble(), unit)} between these two.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    photo: ProgressPhoto,
    selected: Boolean,
    unit: WeightUnit,
    today: LocalDate,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        PhotoImage(file = photo.file, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            Text(
                text = DateFormatters.shortDate(photo.localDate, today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            photo.weightGrams?.let {
                Text(
                    text = WeightFormatter.full(it, unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        TextButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Text("x", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Decodes the file straight to a bitmap.
 *
 * No image library: these are a handful of local files, and pulling in a loader to read a file
 * the app already owns would be more moving parts than the feature needs.
 */
@Composable
private fun PhotoImage(file: File, modifier: Modifier = Modifier) {
    val bitmap = remember(file.path, file.lastModified()) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
    }
    if (bitmap == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

private fun captureUri(context: Context, file: File): android.net.Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
