package com.weighttrack.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Test

/**
 * The F-Droid scanner, given barcodes this test draws itself.
 *
 * There is no barcode to point a camera at on a build machine, so the decoder is handed the
 * luminance plane it would have got from one. That covers the part worth covering: everything
 * between the pixels and the number.
 */
class ZxingBarcodeReaderTest {

    private val reader = ZxingBarcodeReader()

    /** Draws a barcode the way a camera would see it: one byte of brightness per pixel. */
    private fun luminanceOf(
        code: String,
        format: BarcodeFormat = BarcodeFormat.EAN_13,
        width: Int = 400,
        height: Int = 200,
        rowStride: Int = width,
    ): ByteArray {
        val matrix = MultiFormatWriter().encode(code, format, width, height)
        val bytes = ByteArray(rowStride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Black bars are dark, everything else is bright, which is what a sensor sees.
                bytes[y * rowStride + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return bytes
    }

    @Test
    fun `a thirteen digit product code comes back exactly as it went in`() {
        val decoded = reader.decode(luminanceOf("3017624010701"), width = 400, height = 200)

        assertThat(decoded).isEqualTo("3017624010701")
    }

    @Test
    fun `the shorter retail formats read too`() {
        assertThat(reader.decode(luminanceOf("96385074", BarcodeFormat.EAN_8), 400, 200))
            .isEqualTo("96385074")
        assertThat(reader.decode(luminanceOf("036000291452", BarcodeFormat.UPC_A), 400, 200))
            .isEqualTo("036000291452")
    }

    @Test
    fun `padding at the end of every camera row does not break the decode`() {
        // A camera almost never hands back rows exactly as wide as the image. A decoder that
        // assumes it does reads the padding as part of the next row and finds nothing.
        val stride = 448
        val decoded = reader.decode(
            luminanceOf("3017624010701", rowStride = stride),
            width = 400,
            height = 200,
            rowStride = stride,
        )

        assertThat(decoded).isEqualTo("3017624010701")
    }

    @Test
    fun `a frame with nothing in it is not a barcode`() {
        val blank = ByteArray(400 * 200) { 0xFF.toByte() }

        assertThat(reader.decode(blank, 400, 200)).isNull()
    }

    @Test
    fun `a truncated or empty frame is refused rather than read past`() {
        assertThat(reader.decode(ByteArray(0), 400, 200)).isNull()
        assertThat(reader.decode(ByteArray(100), 400, 200)).isNull()
        assertThat(reader.decode(ByteArray(10), 0, 0)).isNull()
    }

    @Test
    fun `what it decodes is something the app will accept as a product`() {
        val decoded = reader.decode(luminanceOf("5000157024671"), 400, 200)

        assertThat(Barcodes.isProductCode(decoded)).isTrue()
    }
}
