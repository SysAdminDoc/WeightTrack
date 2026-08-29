package com.weighttrack.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.weighttrack.core.share.MilestoneCard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a card where the share sheet can reach it.
 *
 * Into the cache, not into the pictures. Somebody sharing a card wants to send it, not to find it
 * in their gallery next to their holiday photographs, and a card left in shared storage is a
 * private thing sitting where anything with storage access can read it.
 *
 * There is no social integration here and there will not be. This hands the image to Android's
 * own share sheet and stops. Which app it goes to is the person's business, and an app that
 * knows the answer is an app that told somebody.
 */
@Singleton
class ShareCardStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Renders the card and hands back something the share sheet can open.
     *
     * The previous card is deleted first. Cards accumulate otherwise, and the older ones are
     * somebody's progress sitting in a cache for no reason.
     */
    suspend fun prepare(content: MilestoneCard.Content): Intent? = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, DIRECTORY)
            directory.mkdirs()
            directory.listFiles()?.forEach { it.delete() }

            val file = File(directory, FILE_NAME)
            val bitmap = MilestoneImage.render(content)
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }

            val uri: Uri = FileProvider.getUriForFile(context, authority(), file)
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                // The same claim in words, for anywhere that takes text rather than an image.
                putExtra(Intent.EXTRA_TEXT, content.line)
                // Temporary and per-app. The grant dies with the receiving app's task.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    private fun authority(): String = "${context.packageName}.photos"

    private companion object {
        const val DIRECTORY = "share-cards"
        const val FILE_NAME = "milestone.png"
    }
}
