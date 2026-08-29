package com.weighttrack.barcode

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit, for the Play build.
 *
 * Restricted to the retail formats. A scanner that also reads QR codes finds the poster behind
 * the shelf, and there is nothing useful to do with that in a food app.
 */
@Singleton
class MlKitBarcodeReader @Inject constructor() : BarcodeReader {

    override val name: String = "ML Kit"

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_ITF,
                )
                .build(),
        )
    }

    @androidx.camera.core.ExperimentalGetImage
    override suspend fun read(image: ImageProxy): String? {
        val media = image.image ?: return null
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        return runCatching { scanner.process(input).await() }
            .getOrNull()
            ?.firstNotNullOfOrNull { it.rawValue }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeModule {
    @Binds
    @Singleton
    abstract fun bindBarcodeReader(reader: MlKitBarcodeReader): BarcodeReader
}
