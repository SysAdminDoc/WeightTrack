package com.weighttrack.barcode

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZXing, for the F-Droid build.
 *
 * Reads the luminance plane straight out of the frame. Every camera format this app asks for
 * puts brightness in plane zero, and brightness is all a barcode is: converting to a bitmap
 * first would allocate a few megabytes per frame to throw away.
 */
@Singleton
class ZxingBarcodeReader @Inject constructor() : BarcodeReader {

    override val name: String = "ZXing"

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to RETAIL_FORMATS))
    }

    override suspend fun read(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return decode(bytes, image.width, image.height, plane.rowStride)
    }

    /**
     * Decodes a luminance plane.
     *
     * Separate from the camera so it can be given an image this app generated and checked
     * against what went in, which is the only way to test a scanner with no barcode to point at.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0) return null
        // A row stride wider than the image means padding at the end of every row, which is
        // normal from a camera and nonsense to a decoder that assumes they are the same.
        val packed = if (rowStride == width) {
            luminance
        } else {
            ByteArray(width * height).also { out ->
                for (row in 0 until height) {
                    val from = row * rowStride
                    if (from + width > luminance.size) return@also
                    luminance.copyInto(out, row * width, from, from + width)
                }
            }
        }
        if (packed.size < width * height) return null

        val source = PlanarYUVLuminanceSource(
            packed, width, height, 0, 0, width, height, false,
        )
        return listOf(source, source.invert()).firstNotNullOfOrNull { candidate ->
            runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text
            }.getOrElse { error ->
                if (error is NotFoundException) null else null
            }
        }.also { reader.reset() }
    }

    private companion object {
        /** Only the formats a food carries. A QR code on a poster is not a product. */
        val RETAIL_FORMATS = listOf(
            com.google.zxing.BarcodeFormat.EAN_13,
            com.google.zxing.BarcodeFormat.EAN_8,
            com.google.zxing.BarcodeFormat.UPC_A,
            com.google.zxing.BarcodeFormat.UPC_E,
            com.google.zxing.BarcodeFormat.ITF,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeModule {
    @Binds
    @Singleton
    abstract fun bindBarcodeReader(reader: ZxingBarcodeReader): BarcodeReader
}
