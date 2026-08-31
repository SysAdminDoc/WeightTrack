package com.weighttrack.ui.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.weighttrack.R
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.repo.ProgressPhoto
import com.weighttrack.ui.components.EmptyState
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

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
    /** What went wrong with the last picture, or null when nothing did. */
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val context = LocalContext.current
    val snackbarHostState = androidx.compose.runtime.remember {
        androidx.compose.material3.SnackbarHostState()
    }
    androidx.compose.runtime.LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onDismissMessage()
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onImport) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> onCaptureResult(success) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_progress_photos)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (state.selectedIds.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) { Text(stringResource(R.string.goal_clear)) }
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
                    Text(stringResource(R.string.photos_gallery))
                }
                OutlinedButton(
                    onClick = { takePhoto.launch(captureUri(context, onPrepareCapture())) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Text(stringResource(R.string.photos_camera))
                }
            }

            state.comparison?.let { (before, after) ->
                ComparisonCard(before, after, state.weightUnit, today)
            }

            if (state.photos.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.PhotoCamera,
                    title = stringResource(R.string.photos_no_photos_yet),
                    message = stringResource(R.string.photos_photos_stay_on_this_phone_and),
                    modifier = Modifier.padding(top = 32.dp),
                )
                return@Column
            }

            Text(
                text = if (state.selectedIds.size == 1) {
                    stringResource(R.string.photosscreen_pick_one_more_compare)
                } else {
                    stringResource(R.string.photosscreen_tap_two_photos_compare_them)
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
        SectionHeading(stringResource(R.string.photos_side_by_side))
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
                        text = photo.weightGrams?.let { WeightFormatter.full(it, unit) } ?: stringResource(R.string.photosscreen_no_weight),
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
                text = stringResource(R.string.photos_between_these_two, WeightFormatter.delta((to - from).toDouble(), unit)),
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
                    contentDescription = stringResource(R.string.photos_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        TextButton(
            onClick = onLongClick,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Text(stringResource(R.string.photos_remove), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PhotoImage(file: File, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val targetHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            file.path,
            file.lastModified(),
            targetWidth,
            targetHeight,
        ) {
            value = decodeSampledBitmap(file, targetWidth, targetHeight)
        }
        if (bitmap == null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Reads only enough pixels for the rendered box and always does the file work off the caller's
 * thread. Camera JPEGs routinely decode to tens of megabytes when read at their original size.
 */
internal suspend fun decodeSampledBitmap(
    file: File,
    requestedWidth: Int,
    requestedHeight: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Bitmap? = withContext(dispatcher) {
    if (requestedWidth <= 0 || requestedHeight <= 0) return@withContext null
    if (!file.isFile || file.length() == 0L) return@withContext null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= requestedWidth &&
        bounds.outHeight / (sampleSize * 2) >= requestedHeight
    ) {
        sampleSize *= 2
    }

    val decoded = runCatching {
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull() ?: return@withContext null

    val scale = max(
        requestedWidth.toFloat() / decoded.width,
        requestedHeight.toFloat() / decoded.height,
    ).coerceAtMost(1f)
    val outputWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
    val outputHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
    if (outputWidth == decoded.width && outputHeight == decoded.height) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, outputWidth, outputHeight, true).also { decoded.recycle() }
    }
}

private fun captureUri(context: Context, file: File): android.net.Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
