package com.weighttrack

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * The phone and the watch cannot be published under one number.
 *
 * Play treats the watch APK as a separate artifact of the same application id and rejects one
 * whose version code collides with the phone's. Both modules carried the literal 5, so neither
 * could have been published beside the other, and nothing said so.
 *
 * `./gradlew checkFormFactorVersions` reads what actually reached the built manifests. This is
 * the half that does not need a build: that there is one source of the numbers, and that the
 * bands they live in cannot overlap.
 */
class ReleaseVersionsTest {

    private val properties = Properties().apply {
        File("../gradle.properties").inputStream().use { load(it) }
    }

    private fun number(name: String): Int =
        checkNotNull(properties.getProperty(name)) { "$name is missing" }.trim().toInt()

    @Test
    fun `the watch's band is above every code the phone can reach`() {
        val phone = number("weighttrackVersionCode")
        val band = number("weighttrackWearVersionBand")

        assertThat(phone).isLessThan(band)
        assertThat(band + phone).isNotEqualTo(phone)
    }

    @Test
    fun `neither module writes a version of its own`() {
        // A literal here is how the two drifted apart in the first place, and it would put the
        // watch back on a number the phone is already using without failing anything.
        listOf(File("build.gradle.kts"), File("../wear/build.gradle.kts")).forEach { file ->
            val text = file.readText()
            assertThat(text).doesNotContain("versionCode = 5")
            assertThat(text).contains("Versions.")
        }
    }

    @Test
    fun `the version name is one value both modules read`() {
        val name = checkNotNull(properties.getProperty("weighttrackVersionName")).trim()

        assertThat(name).matches("""\d+\.\d+\.\d+""")
        listOf(File("build.gradle.kts"), File("../wear/build.gradle.kts")).forEach { file ->
            assertThat(file.readText()).doesNotContain("versionName = \"")
        }
    }
}
