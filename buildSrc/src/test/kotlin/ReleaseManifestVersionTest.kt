import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseManifestVersionTest {

    @Test
    fun `an exact version identity is accepted`() {
        assertEquals(
            emptyList(),
            problems(versionCode = "5", versionName = "0.4.0"),
        )
    }

    @Test
    fun `a version name with the expected prefix is still a mismatch`() {
        assertEquals(
            listOf("AndroidManifest.xml for the phone says version name 0.4.0-mismatch, not 0.4.0"),
            problems(versionCode = "5", versionName = "0.4.0-mismatch"),
        )
    }

    @Test
    fun `a different version code is a mismatch`() {
        assertEquals(
            listOf("AndroidManifest.xml for the phone says version code 6, not 5"),
            problems(versionCode = "6", versionName = "0.4.0"),
        )
    }

    @Test
    fun `a missing version name is a mismatch`() {
        assertEquals(
            listOf("AndroidManifest.xml for the phone says version name null, not 0.4.0"),
            ReleaseManifestVersion.problems(
                text = """<manifest android:versionCode="5" />""",
                fileName = "AndroidManifest.xml",
                form = "phone",
                expectedCode = 5,
                expectedName = "0.4.0",
            ),
        )
    }

    private fun problems(versionCode: String, versionName: String): List<String> =
        ReleaseManifestVersion.problems(
            text = """<manifest android:versionCode="$versionCode" android:versionName="$versionName" />""",
            fileName = "AndroidManifest.xml",
            form = "phone",
            expectedCode = 5,
            expectedName = "0.4.0",
        )
}
