package com.weighttrack.ui.photos

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PhotoBitmapDecoderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a twelve megapixel photo is decoded near the rendered tile size`() = runTest {
        val source = File.createTempFile("progress-photo", ".jpg", context.cacheDir)
        val original = Bitmap.createBitmap(4_000, 3_000, Bitmap.Config.RGB_565)
        source.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        original.recycle()

        try {
            val decoded = decodeSampledBitmap(source, requestedWidth = 300, requestedHeight = 400)

            assertThat(decoded).isNotNull()
            assertThat(decoded!!.width).isEqualTo(533)
            assertThat(decoded.height).isEqualTo(400)
            assertThat(decoded.byteCount).isLessThan(1_000_000)
            decoded.recycle()
        } finally {
            source.delete()
        }
    }

    @Test
    fun `an empty photo returns no bitmap`() = runTest {
        val source = File.createTempFile("progress-photo", ".jpg", context.cacheDir)

        try {
            assertThat(decodeSampledBitmap(source, 300, 400)).isNull()
        } finally {
            source.delete()
        }
    }
}
