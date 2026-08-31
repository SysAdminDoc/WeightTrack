package com.weighttrack.data.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException

/**
 * A storage-picker folder backed by a real directory.
 *
 * The folder transport reaches its files through the documents contract, so nothing about it can
 * be exercised without something on the other end of that: not the ceiling on a file, not the
 * truncating write, not the folder whose permission has gone. The alternative was searching the
 * transport's own source text for the calls it ought to be making, which passes just as happily
 * when one of them has been moved somewhere it never runs.
 *
 * Written against [ContentProvider] rather than the framework's `DocumentsProvider`, which cannot
 * be used here: that class makes the older five-argument query final and throws from it, a real
 * phone converts the older shape into the modern one inside the content resolver, and the test
 * resolver hands it straight through. The support library asks in the older shape, so every
 * answer would be an exception that nothing reports.
 */
class FakeDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(documentId: String): File =
        if (documentId == ROOT_ID) root else File(root, documentId.removePrefix("$ROOT_ID/"))

    private fun idFor(file: File): String =
        if (file == root) ROOT_ID else "$ROOT_ID/" + file.toRelativeString(root).replace('\\', '/')

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val cursor = MatrixCursor(projection ?: COLUMNS)
        if (uri.lastPathSegment == CHILDREN) {
            fileFor(documentId).listFiles().orEmpty().sorted().forEach { add(cursor, it) }
            return cursor
        }
        val file = fileFor(documentId)
        // What a folder on a memory card that has been taken out answers with.
        if (!file.exists()) throw FileNotFoundException(documentId)
        add(cursor, file)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val file = fileFor(DocumentsContract.getDocumentId(uri))
        return if (file.isDirectory) Document.MIME_TYPE_DIR else MIME
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            fileFor(DocumentsContract.getDocumentId(uri)),
            ParcelFileDescriptor.parseMode(mode),
        )

    /** How the support library asks for a new file: a call, not an insert. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != CREATE_DOCUMENT || extras == null) return null
        @Suppress("DEPRECATION")
        val parent = extras.getParcelable<Uri>(EXTRA_URI) ?: return null
        val name = extras.getString(Document.COLUMN_DISPLAY_NAME) ?: return null
        val file = free(File(fileFor(DocumentsContract.getDocumentId(parent)), name))
        file.createNewFile()
        return Bundle().apply {
            putParcelable(
                EXTRA_URI,
                DocumentsContract.buildDocumentUriUsingTree(parent, idFor(file)),
            )
        }
    }

    /**
     * A name nothing is using yet, the way the real picker answers.
     *
     * Creating over a name that is taken does not replace it: the framework hands back
     * "weighttrack-me (1).json" instead, and from then on the folder holds two files each
     * claiming to be the same device.
     */
    private fun free(wanted: File): File {
        if (!wanted.exists()) return wanted
        val stem = wanted.name.substringBeforeLast('.')
        val extension = wanted.name.substringAfterLast('.', "")
        var attempt = 1
        while (true) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            val candidate = File(wanted.parentFile, "$stem ($attempt)$suffix")
            if (!candidate.exists()) return candidate
            attempt++
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * A row over exactly the columns that were asked for.
     *
     * The order matters more than it looks: everything reading one property of a document asks
     * for one column and reads column zero, so a row that always carries every column in its own
     * order hands back a document identifier where a mime type was wanted, and every file in the
     * folder then looks like something that is not a directory and cannot be read.
     */
    private fun add(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        cursor.columnNames.forEach { column ->
            row.add(
                column,
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> idFor(file)
                    Document.COLUMN_DISPLAY_NAME -> file.name
                    Document.COLUMN_MIME_TYPE ->
                        if (file.isDirectory) Document.MIME_TYPE_DIR else MIME
                    Document.COLUMN_SIZE -> file.length()
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    Document.COLUMN_FLAGS ->
                        Document.FLAG_DIR_SUPPORTS_CREATE or
                            Document.FLAG_SUPPORTS_WRITE or
                            Document.FLAG_SUPPORTS_DELETE
                    else -> null
                },
            )
        }
    }

    companion object {
        const val AUTHORITY = "com.weighttrack.test.documents"
        const val ROOT_ID = "root"

        private const val MIME = "application/json"
        private const val CHILDREN = "children"
        private const val CREATE_DOCUMENT = "android:createDocument"

        /** The contract names this one but does not publish it. */
        private const val EXTRA_URI = "uri"

        /** The directory the provider serves. Set by the test before the provider is built. */
        lateinit var root: File

        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
