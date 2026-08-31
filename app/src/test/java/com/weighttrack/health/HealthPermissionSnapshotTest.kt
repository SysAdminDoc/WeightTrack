package com.weighttrack.health

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The manifest says exactly what the app asks for, and the app asks only for what it uses.
 *
 * Three lists have to agree and nothing made them: the manifest, the sets the app requests at
 * runtime, and what the code actually reads or writes. They drifted. Height sat in all three
 * lists for two releases after the last thing that read one was removed, and because it was in
 * the set gating weight sync, anybody who declined an access the app no longer used had weight
 * sync refused outright with no way past it.
 *
 * This is also the snapshot a Play data-safety declaration is written from: what goes on the
 * listing has to be this list, and a list nothing checks is a list that goes stale.
 */
class HealthPermissionSnapshotTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /** Every health permission the manifest declares, by its short name. */
    private val declared: Set<String> =
        Regex("""android:name="android\.permission\.health\.([A-Z_]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

    /** Every health permission the app asks for at runtime, by the same short name. */
    private val requested: Set<String> = HealthConnectSync.permissions
        .map { it.substringAfterLast('.') }
        .toSet()

    @Test
    fun `the manifest and the request agree exactly`() {
        // Both directions. A permission in the manifest and not the request is one nobody can
        // grant and nothing needs; one in the request and not the manifest can never be granted
        // at all, and the feature behind it fails with no explanation.
        assertThat(declared).containsExactlyElementsIn(requested)
    }

    @Test
    fun `this is the whole list, and every line of it has a use`() {
        // Named rather than counted, so adding one to the manifest fails this until somebody has
        // said out loud what it is for. The rationale screen has to gain a paragraph for it too.
        assertThat(declared).containsExactly(
            "READ_WEIGHT",
            "WRITE_WEIGHT",
            "READ_BODY_FAT",
            "WRITE_BODY_FAT",
            "READ_HEALTH_DATA_IN_BACKGROUND",
            "READ_HEALTH_DATA_HISTORY",
            "WRITE_HYDRATION",
            "WRITE_NUTRITION",
            "READ_STEPS",
            "READ_ACTIVE_CALORIES_BURNED",
            "READ_SLEEP",
        )
    }

    @Test
    fun `nothing asks for a height it no longer uses`() {
        assertThat(declared.none { it.contains("HEIGHT") }).isTrue()
        assertThat(requested.none { it.contains("HEIGHT") }).isTrue()
        val sync = File("src/main/java/com/weighttrack/health/HealthConnectSync.kt").readText()
        assertThat(sync).doesNotContain("HeightRecord::class")
    }

    @Test
    fun `weight sync is not gated on anything optional`() {
        // The core set is what refusing costs the whole feature. Everything else has to be able
        // to be declined without the sync reporting itself as unauthorised.
        val core = HealthConnectSync.corePermissions.map { it.substringAfterLast('.') }.toSet()

        assertThat(core).containsExactly("READ_WEIGHT", "WRITE_WEIGHT")
    }

    @Test
    fun `the rationale explains every access`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val explained = Regex("""<string name="health_rationale_(\w+)_body">""")
            .findAll(strings)
            .map { it.groupValues[1] }
            .toSet()

        // One paragraph per group the app asks for. Health Connect sends people to this screen
        // to ask what an access is for, and a group with no paragraph is a question unanswered.
        assertThat(explained).containsExactly(
            "weight",
            "body_fat",
            "background",
            "history",
            "water",
            "food",
            "movement",
            "sleep",
        )
    }
}
