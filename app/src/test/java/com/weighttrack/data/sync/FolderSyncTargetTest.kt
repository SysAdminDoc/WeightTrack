package com.weighttrack.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The folder transport against a folder that answers.
 *
 * A shared folder is written by something outside this app, so what arrives is somebody else's
 * problem right up until this app reads it. The ceiling on a file, the write that replaces rather
 * than adds, and the folder whose permission has gone were all held to nothing but a search of
 * this class's own source text before.
 */
@RunWith(RobolectricTestRunner::class)
class FolderSyncTargetTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var folder: File
    private lateinit var treeUri: Uri

    @Before
    fun setUp() {
        folder = temporary.newFolder("shared")
        FakeDocumentsProvider.root = folder
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(
            ProviderInfo().apply {
                authority = FakeDocumentsProvider.AUTHORITY
                grantUriPermissions = true
                exported = true
            },
        )
        treeUri = DocumentsContract.buildTreeDocumentUri(
            FakeDocumentsProvider.AUTHORITY,
            FakeDocumentsProvider.ROOT_ID,
        )
        // The picker grants the tree, and everything underneath it is addressed by a
        // document uri built from that tree rather than by the tree itself.
        listOf(
            treeUri,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, FakeDocumentsProvider.ROOT_ID),
        ).forEach {
            context.grantUriPermission(
                context.packageName,
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun target(uri: Uri = treeUri) = FolderSyncTarget(context, uri)

    @Test
    fun `it lists what is in the folder`() = runTest {
        File(folder, "weighttrack-me.json").writeText("{}")
        File(folder, "weighttrack-peer.json").writeText("{}")

        val outcome = target().list()

        assertThat((outcome as SyncOutcome.Ok).value)
            .containsExactly("weighttrack-me.json", "weighttrack-peer.json")
    }

    @Test
    fun `it reads a document another device left`() = runTest {
        File(folder, "weighttrack-peer.json").writeText("""{"deviceId":"peer"}""")

        assertThat(target().read("weighttrack-peer.json"))
            .isEqualTo(SyncOutcome.Ok("""{"deviceId":"peer"}"""))
    }

    @Test
    fun `a name that is not there reads as nothing, not as a failure`() = runTest {
        // A device named in a listing that has been tidied away since, which is ordinary.
        assertThat(target().read("weighttrack-gone.json")).isEqualTo(SyncOutcome.Ok(null))
    }

    @Test
    fun `a file past the ceiling is refused and named`() = runTest {
        val enormous = File(folder, "weighttrack-huge.json")
        enormous.outputStream().use { out ->
            repeat(OVERSIZE_MEGABYTES) { out.write(ByteArray(BYTES_PER_MEGABYTE)) }
        }

        val outcome = target().read("weighttrack-huge.json")

        // Refused rather than unreachable: it will be the same size in an hour. A hundred
        // megabytes of anything at all used to take the app down before a line of it had been
        // parsed, and the name is what makes it something somebody can go and delete.
        assertThat(outcome).isInstanceOf(SyncOutcome.Refused::class.java)
        assertThat((outcome as SyncOutcome.Refused).reason).contains("weighttrack-huge.json")
    }

    @Test
    fun `publishing twice leaves one file, with nothing of the longer one behind`() = runTest {
        val long = """{"deviceId":"me","weights":[1,2,3,4,5,6,7,8,9,10]}"""
        assertThat(target().write("weighttrack-me.json", long)).isEqualTo(SyncOutcome.Ok(Unit))
        val short = """{"deviceId":"me"}"""

        assertThat(target().write("weighttrack-me.json", short)).isEqualTo(SyncOutcome.Ok(Unit))

        // Creating over an existing name gives a second file called "weighttrack-me (1).json",
        // and from then on the folder holds two files claiming to be the same device. Writing
        // without truncating leaves the tail of the longer document behind, which stops the
        // file being readable JSON at all.
        assertThat(folder.listFiles().orEmpty().map { it.name })
            .containsExactly("weighttrack-me.json")
        assertThat(target().read("weighttrack-me.json")).isEqualTo(SyncOutcome.Ok(short))
    }

    @Test
    fun `a folder that has gone says so rather than looking empty`() = runTest {
        // What a memory card that has been taken out, or a permission dropped on reinstall,
        // looks like. Reporting it as an empty folder would mean every peer had vanished, and
        // this device would republish over the top of them.
        val gone = DocumentsContract.buildTreeDocumentUri(
            FakeDocumentsProvider.AUTHORITY,
            "root/not-here",
        )

        assertThat(target(gone).list()).isInstanceOf(SyncOutcome.Refused::class.java)
        assertThat(target(gone).read("weighttrack-peer.json"))
            .isInstanceOf(SyncOutcome.Refused::class.java)
    }

    private companion object {
        /** One megabyte past what the app will read, so the bound is crossed and not touched. */
        const val OVERSIZE_MEGABYTES = 33
        const val BYTES_PER_MEGABYTE = 1024 * 1024
    }
}
