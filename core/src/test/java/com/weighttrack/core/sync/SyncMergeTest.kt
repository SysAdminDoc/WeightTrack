package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class SyncMergeTest {

    private val now = 1_800_000_000_000L

    private fun weight(
        id: String,
        grams: Int,
        updatedAt: Long,
        profile: String = "p1",
    ) = SyncWeight(
        syncId = id,
        profileSyncId = profile,
        timestampUtcMillis = updatedAt,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = grams,
        source = "MANUAL",
        updatedAtUtcMillis = updatedAt,
    )

    private fun profile(id: String = "p1", name: String = "Me", updatedAt: Long = 1_000L) =
        SyncProfile(
            syncId = id,
            name = name,
            position = 0,
            createdAtUtcMillis = 0,
            updatedAtUtcMillis = updatedAt,
        )

    private fun document(
        device: String,
        weights: List<SyncWeight> = emptyList(),
        profiles: List<SyncProfile> = listOf(profile()),
        deletions: List<SyncDeletion> = emptyList(),
        settings: SyncSettings? = null,
    ) = SyncDocument(
        deviceId = device,
        writtenAtUtcMillis = now,
        profiles = profiles,
        weights = weights,
        deletions = deletions,
        settings = settings,
    )

    @Test
    fun `the newer edit wins`() {
        val phone = document("aaa", listOf(weight("w1", 80_000, now - 1000)))
        val tablet = document("bbb", listOf(weight("w1", 81_000, now)))

        val merged = SyncMerge.merge(listOf(phone, tablet), "aaa", now)

        assertThat(merged.weights).hasSize(1)
        assertThat(merged.weights.first().grams).isEqualTo(81_000)
    }

    @Test
    fun `it does not matter which order the files are read in`() {
        val phone = document("aaa", listOf(weight("w1", 80_000, now - 1000), weight("w2", 79_000, now)))
        val tablet = document("bbb", listOf(weight("w1", 81_000, now), weight("w3", 78_000, now)))

        val one = SyncMerge.merge(listOf(phone, tablet), "aaa", now)
        val other = SyncMerge.merge(listOf(tablet, phone), "aaa", now)

        assertThat(one.weights).isEqualTo(other.weights)
        assertThat(one.profiles).isEqualTo(other.profiles)
    }

    @Test
    fun `two devices with the same files reach the same answer`() {
        // The property the whole thing rests on. If they disagree they will hand edits back and
        // forth for as long as both are switched on.
        val phone = document("aaa", listOf(weight("w1", 80_000, now)))
        val tablet = document("bbb", listOf(weight("w1", 81_000, now)))
        val files = listOf(phone, tablet)

        val onPhone = SyncMerge.merge(files, "aaa", now)
        val onTablet = SyncMerge.merge(files, "bbb", now)

        assertThat(onPhone.weights).isEqualTo(onTablet.weights)
    }

    @Test
    fun `a tie is broken the same way on both devices`() {
        // Same millisecond, different values. Somebody has to lose, and both have to agree who.
        val phone = document("aaa", listOf(weight("w1", 80_000, now)))
        val tablet = document("bbb", listOf(weight("w1", 81_000, now)))

        val merged = SyncMerge.merge(listOf(phone, tablet), "aaa", now)

        assertThat(merged.weights.first().grams).isEqualTo(81_000)
    }

    @Test
    fun `merging what was already merged changes nothing`() {
        val phone = document("aaa", listOf(weight("w1", 80_000, now - 1000)))
        val tablet = document("bbb", listOf(weight("w1", 81_000, now), weight("w2", 70_000, now)))

        val once = SyncMerge.merge(listOf(phone, tablet), "aaa", now)
        val twice = SyncMerge.merge(listOf(once), "aaa", now)

        assertThat(twice.weights).isEqualTo(once.weights)
        assertThat(twice.deletions).isEqualTo(once.deletions)
    }

    @Test
    fun `a deleted reading stays deleted`() {
        // The record still exists on the other device, untouched. Without the tombstone it comes
        // straight back, which is the most irritating way for sync to be wrong.
        val stillHasIt = document("aaa", listOf(weight("w1", 80_000, now - 5000)))
        val deletedIt = document(
            "bbb",
            deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now)),
        )

        val merged = SyncMerge.merge(listOf(stillHasIt, deletedIt), "aaa", now)

        assertThat(merged.weights).isEmpty()
    }

    @Test
    fun `correcting a reading after deleting it brings it back`() {
        val deletedIt = document(
            "bbb",
            deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now - 5000)),
        )
        val editedItSince = document("aaa", listOf(weight("w1", 80_000, now)))

        val merged = SyncMerge.merge(listOf(deletedIt, editedItSince), "aaa", now)

        assertThat(merged.weights).hasSize(1)
        assertThat(merged.weights.first().grams).isEqualTo(80_000)
    }

    @Test
    fun `a deletion in the same millisecond as an edit wins`() {
        // Deliberate. Somebody who deleted a row and is unsure whether an edit landed first is
        // better served by it staying gone than by it quietly coming back.
        val edited = document("aaa", listOf(weight("w1", 80_000, now)))
        val deleted = document("bbb", deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now)))

        assertThat(SyncMerge.merge(listOf(edited, deleted), "aaa", now).weights).isEmpty()
    }

    @Test
    fun `old deletions are forgotten`() {
        val ancient = now - SyncMerge.TOMBSTONE_RETENTION_FLOOR_MILLIS - 1
        val document = document("aaa", deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", ancient)))

        assertThat(SyncMerge.merge(listOf(document), "aaa", now).deletions).isEmpty()
    }

    @Test
    fun `a deletion right on the edge is still remembered`() {
        val old = now - SyncMerge.TOMBSTONE_RETENTION_FLOOR_MILLIS
        val document = document("aaa", deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", old)))

        assertThat(SyncMerge.merge(listOf(document), "aaa", now).deletions).hasSize(1)
    }

    @Test
    fun `a deletion dated in the future is kept`() {
        // A phone with a wrong clock. Keeping it holds a delete in place; dropping it would undo
        // one, and undoing a delete is the worse mistake.
        val document = document(
            "aaa",
            weights = listOf(weight("w1", 80_000, now)),
            deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now + 1_000_000)),
        )

        val merged = SyncMerge.merge(listOf(document), "aaa", now)

        assertThat(merged.weights).isEmpty()
        assertThat(merged.deletions).hasSize(1)
    }

    @Test
    fun `the latest deletion of the same record is the one kept`() {
        val early = document("aaa", deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now - 5000)))
        val late = document("bbb", deletions = listOf(SyncDeletion(SyncKind.WEIGHT, "w1", now - 10)))
        val editedBetween = document("ccc", listOf(weight("w1", 80_000, now - 2000)))

        val merged = SyncMerge.merge(listOf(early, late, editedBetween), "aaa", now)

        // The edit sits between the two deletions. The later one still buries it.
        assertThat(merged.weights).isEmpty()
    }

    @Test
    fun `deleting one kind does not delete another with the same identifier`() {
        val document = document(
            "aaa",
            weights = listOf(weight("shared", 80_000, now - 1000)),
            profiles = listOf(profile("shared", updatedAt = now - 1000)),
            deletions = listOf(SyncDeletion(SyncKind.PROFILE, "shared", now)),
        )

        val merged = SyncMerge.merge(listOf(document), "aaa", now)

        assertThat(merged.profiles).isEmpty()
        assertThat(merged.weights).hasSize(1)
    }

    @Test
    fun `settings follow the most recent change`() {
        val old = settings(themeMode = "LIGHT", updatedAt = now - 1000)
        val new = settings(themeMode = "DARK", updatedAt = now)

        val merged = SyncMerge.merge(
            listOf(document("aaa", settings = old), document("bbb", settings = new)),
            "aaa",
            now,
        )

        assertThat(merged.settings!!.themeMode).isEqualTo("DARK")
    }

    @Test
    fun `a device that has never had settings does not wipe them`() {
        val merged = SyncMerge.merge(
            listOf(document("aaa", settings = null), document("bbb", settings = settings())),
            "aaa",
            now,
        )

        assertThat(merged.settings).isNotNull()
    }

    @Test
    fun `a reading whose profile is gone is handed back rather than dropped`() {
        val merged = SyncMerge.merge(
            listOf(
                document("aaa", listOf(weight("w1", 80_000, now, profile = "missing"))),
            ),
            "aaa",
            now,
        )

        val orphans = SyncMerge.orphans(merged)
        assertThat(orphans.isEmpty).isFalse()
        assertThat(orphans.count).isEqualTo(1)
        assertThat(orphans.weights.first().syncId).isEqualTo("w1")
    }

    @Test
    fun `nothing is orphaned when every profile is present`() {
        val merged = SyncMerge.merge(
            listOf(document("aaa", listOf(weight("w1", 80_000, now)))),
            "aaa",
            now,
        )

        assertThat(SyncMerge.orphans(merged).isEmpty).isTrue()
    }

    @Test
    fun `many devices editing at random still settle on one answer`() {
        // The convergence property, under conditions nobody would think to write by hand.
        val random = Random(20260829)
        repeat(60) {
            val devices = List(random.nextInt(2, 5)) { index -> "device-$index" }
            val documents = devices.map { device ->
                val weights = List(random.nextInt(0, 6)) { i ->
                    weight("w${random.nextInt(1, 5)}", random.nextInt(50_000, 120_000), now - random.nextLong(0, 5_000))
                }
                val deletions = List(random.nextInt(0, 3)) {
                    SyncDeletion(SyncKind.WEIGHT, "w${random.nextInt(1, 5)}", now - random.nextLong(0, 5_000))
                }
                document(device, weights, deletions = deletions)
            }

            val answers = devices.map { SyncMerge.merge(documents.shuffled(random), it, now).weights }
            // Every device, reading the files in whatever order it happened to, agrees.
            assertThat(answers.distinct()).hasSize(1)
        }
    }

    @Test
    fun `a device file is recognised by its name`() {
        val name = SyncDocument.fileName("abc123")
        assertThat(name).isEqualTo("weighttrack-abc123.json")
        assertThat(SyncDocument.deviceIdOf(name)).isEqualTo("abc123")
    }

    @Test
    fun `a conflict copy left by a sync tool is ignored`() {
        // Reading one would resurrect whatever it held, which is exactly what the one-file-per
        // -device arrangement exists to avoid.
        assertThat(
            SyncDocument.deviceIdOf("weighttrack-abc.sync-conflict-20260101-120000-XYZ.json"),
        ).isNull()
        assertThat(SyncDocument.deviceIdOf("weighttrack-abc (1).json")).isNull()
        assertThat(SyncDocument.deviceIdOf("notes.json")).isNull()
        assertThat(SyncDocument.deviceIdOf("weighttrack-.json")).isNull()
    }

    @Test
    fun `a file that is not ours is not read`() {
        assertThat(SyncDocument.decode("")).isNull()
        assertThat(SyncDocument.decode("{ not json")).isNull()
        assertThat(SyncDocument.decode("""{"app":"SomethingElse","deviceId":"a","writtenAtUtcMillis":1}"""))
            .isNull()
    }

    @Test
    fun `a file written by a newer version still reads`() {
        val text = """
            {"app":"WeightTrack","formatVersion":99,"deviceId":"aaa","writtenAtUtcMillis":1,
             "somethingNew":{"a":1},"weights":[]}
        """.trimIndent()

        assertThat(SyncDocument.decode(text)).isNotNull()
    }

    @Test
    fun `a document survives being written and read back`() {
        val original = document(
            "aaa",
            weights = listOf(weight("w1", 80_000, now)),
            deletions = listOf(SyncDeletion(SyncKind.WATER, "x1", now)),
            settings = settings(),
        ).copy(
            peers = listOf(SyncPeer("bbb", lastSeenAtUtcMillis = now, retiredAtUtcMillis = 0)),
            observed = listOf(SyncObservation("bbb", now - 500)),
        )

        // Reading a file resolves who made each record, so a document written with the name left
        // blank comes back with the writer's name filled in. Everything else has to survive
        // untouched, and reading a resolved document has to be exactly the identity.
        val settled = original.attributed()
        assertThat(SyncDocument.decode(SyncDocument.encode(original))).isEqualTo(settled)
        assertThat(SyncDocument.decode(SyncDocument.encode(settled))).isEqualTo(settled)
    }

    private fun settings(themeMode: String = "SYSTEM", updatedAt: Long = 1_000L) = SyncSettings(
        weightUnit = "KILOGRAMS",
        lengthUnit = "CENTIMETRES",
        themeMode = themeMode,
        heightMm = 1800,
        sex = "MALE",
        birthYear = 1990,
        activityLevel = "MODERATE",
        trendWindowDays = 30,
        milestoneStepGrams = 2000,
        updatedAtUtcMillis = updatedAt,
    )
}
