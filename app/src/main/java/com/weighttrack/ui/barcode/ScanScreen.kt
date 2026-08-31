package com.weighttrack.ui.barcode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weighttrack.R
import com.weighttrack.barcode.BarcodeReader
import com.weighttrack.barcode.Barcodes
import com.weighttrack.ui.components.SectionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The camera, pointed at a barcode.
 *
 * Frames are analysed as they arrive and dropped if one is still being read, so a slow decode
 * makes the scan slower rather than filling memory with a queue of stale frames.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    reader: BarcodeReader,
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var found by remember { mutableStateOf<String?>(null) }
    val onScannedNow by rememberUpdatedState(onScanned)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.barcode_scan_a_barcode)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!granted) {
                SectionCard {
                    Text(
                        text = stringResource(R.string.barcode_reading_a_barcode_needs_the_camera),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.barcode_allow_the_camera))
                    }
                }
                return@Column
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                CameraPreview(
                    reader = reader,
                    enabled = found == null,
                    onBarcode = { code ->
                        if (found == null) {
                            found = code
                            onScannedNow(code)
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = found?.let { "Read $it" }
                    ?: stringResource(R.string.scanscreen_hold_barcode_inside_frame_reading_with, reader.name),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraPreview(
    reader: BarcodeReader,
    enabled: Boolean,
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    DisposableEffect(Unit) {
        onDispose {
            scope.cancel()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(enabled) {
        val provider = ProcessCameraProvider.getInstance(context).let { future ->
            runCatching { future.get() }.getOrNull()
        } ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            // Keeping only the newest frame is what stops a slow decode building a queue of
            // stale ones and running the phone out of memory.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        if (enabled) {
            analysis.setAnalyzer(analysisExecutor) { image ->
                scope.launch {
                    try {
                        val code = reader.read(image)
                        // Handed over on the interface thread. The decode runs on a camera
                        // thread of its own, and what a caller does with a barcode is close this
                        // screen, which the navigation controller's lifecycle accepts only from
                        // the main thread and throws for anywhere else.
                        if (Barcodes.isProductCode(code)) {
                            withContext(Dispatchers.Main) { onBarcode(code!!) }
                        }
                    } finally {
                        // Closed whatever happened, or the camera stops sending frames.
                        image.close()
                    }
                }
            }
        }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
