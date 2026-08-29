package com.weighttrack.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder on this phone, chosen by the person.
 *
 * Whatever keeps that folder in step with another device is their business: Syncthing, a
 * cloud client with a local folder, a cable and some patience. The app writes one file, reads
 * whatever else is there, and has no opinion about how it travels.
 *
 * The folder is reached through the storage picker, so the app never asks for a storage
 * permission and can only see the one directory it was given.
 */
class FolderSyncTarget(
    private val context: Context,
    private val treeUri: Uri,
) : SyncTarget {

    private fun say(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    override val describe: String
        get() = treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            ?: treeUri.toString()

    private fun folder(): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canRead() }

    override suspend fun list(): SyncOutcome<List<String>> = withContext(Dispatchers.IO) {
        val folder = folder()
            ?: return@withContext SyncOutcome.Refused(
                // A folder on a memory card that has been taken out, or one whose permission was
                // revoked when the app was reinstalled. Worth saying, because only the person
                // can put it right by picking it again.
                say(com.weighttrack.R.string.sync_folder_gone),
            )
        runCatching { folder.listFiles().mapNotNull { it.name } }
            .fold(
                { SyncOutcome.Ok(it) },
                {
                    SyncOutcome.Unreachable(
                        it.message ?: say(com.weighttrack.R.string.sync_could_not_read_folder),
                    )
                },
            )
    }

    override suspend fun read(name: String): SyncOutcome<String?> = withContext(Dispatchers.IO) {
        val folder = folder()
            ?: return@withContext SyncOutcome.Refused(say(com.weighttrack.R.string.sync_folder_gone))
        val file = folder.findFile(name)
        if (file == null || !file.isFile) return@withContext SyncOutcome.Ok(null)
        runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
        }.fold(
            {
                // A file being written by the sync tool at this moment reads as nothing or as
                // half a document. Neither is a reason to stop: the next run picks it up.
                SyncOutcome.Ok(it)
            },
            {
                SyncOutcome.Unreachable(
                    it.message ?: say(com.weighttrack.R.string.sync_could_not_read_file, name),
                )
            },
        )
    }

    override suspend fun write(name: String, content: String): SyncOutcome<Unit> =
        withContext(Dispatchers.IO) {
            val folder = folder()
                ?: return@withContext SyncOutcome.Refused(
                    say(com.weighttrack.R.string.sync_folder_gone),
                )
            runCatching {
                // Reused rather than replaced. Creating over an existing name gives a second file
                // called "weighttrack-abc (1).json", and from then on the folder holds two files
                // claiming to be the same device.
                val existing = folder.findFile(name)?.takeIf { it.isFile }
                val file = existing ?: folder.createFile(MIME, name)
                    ?: error("Could not create $name in that folder.")
                // "wt" truncates. Without it a shorter document leaves the tail of the longer one
                // it replaced, and the file stops being readable JSON.
                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                    it.write(content.encodeToByteArray())
                } ?: error("Could not write to $name.")
                Unit
            }.fold(
                { SyncOutcome.Ok(Unit) },
                {
                    SyncOutcome.Unreachable(
                        it.message ?: say(com.weighttrack.R.string.sync_could_not_write_file, name),
                    )
                },
            )
        }

    private companion object {
        const val MIME = "application/json"
    }
}
