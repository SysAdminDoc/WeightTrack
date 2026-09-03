package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether every device reaches the same answer, whatever order the files arrive in.
 *
 * Two devices that break a tie differently keep sending each other their own version forever, so
 * this is the property the whole design rests on rather than a nicety.
 */
class SyncStampConvergenceTest {

    private val now = 1_800_000_000_000L

    private fun weight(grams: Int, updatedAt: Long, device: String) = SyncWeight(
        syncId = "w1",
        profileSyncId = "p1",
        timestampUtcMillis = updatedAt,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = grams,
        source = "MANUAL",
        updatedAtUtcMillis = updatedAt,
        stampDeviceId = device,
    )

    private fun profile() = SyncProfile(
        syncId = "p1",
        name = "Me",
        position = 0,
        createdAtUtcMillis = 0,
        updatedAtUtcMillis = 1_000L,
        stampDeviceId = "aaa",
    )

    private fun document(device: String, weights: List<SyncWeight>) = SyncDocument(
        deviceId = device,
        writtenAtUtcMillis = now,
        profiles = listOf(profile()),
        weights = weights,
    )

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head ->
            permutations(items - head).map { listOf(head) + it }
        }

    @Test
    fun `two edits in the same millisecond settle the same way in every order`() {
        val fromA = weight(80_000, now, "aaa")
        val fromB = weight(81_000, now, "bbb")
        val documents = listOf(
            document("aaa", listOf(fromA)),
            document("bbb", listOf(fromB)),
            // A third phone relaying A's edit. Deciding the tie on the file it arrived in rather
            // than on the device that made it puts "ccc" against "bbb" here and "aaa" against
            // "bbb" in A's own file, so the answer would depend on which files were present.
            document("ccc", listOf(fromA)),
        )

        val answers = permutations(documents).map { order ->
            SyncMerge.merge(order, "aaa", now).weights.single().grams
        }

        assertThat(answers.toSet()).hasSize(1)
        assertThat(answers.first()).isEqualTo(81_000)
    }

    @Test
    fun `every device reaches the same answer as every other`() {
        val documents = listOf(
            document("aaa", listOf(weight(80_000, now, "aaa"))),
            document("bbb", listOf(weight(81_000, now, "bbb"))),
            document("ccc", listOf(weight(80_000, now, "aaa"))),
        )

        val perDevice = listOf("aaa", "bbb", "ccc").map { device ->
            SyncMerge.merge(documents, device, now).weights.single().grams
        }

        assertThat(perDevice.toSet()).hasSize(1)
    }

    @Test
    fun `a correction from a phone whose clock went backwards still wins`() {
        // What the hybrid clock is for, seen from the merge. The correcting phone read the other
        // one's stamp first, so its own next stamp is above it however wrong its clock is.
        val clock = HybridClock()
        val remote = weight(80_000, now, "bbb")
        clock.observe(remote.updatedAtUtcMillis, now - 3_600_000)
        val corrected = weight(81_500, clock.next(now - 3_600_000), "aaa")

        val merged = SyncMerge.merge(
            listOf(document("aaa", listOf(corrected)), document("bbb", listOf(remote))),
            "aaa",
            now,
        )

        assertThat(merged.weights.single().grams).isEqualTo(81_500)
    }

    @Test
    fun `a relayed record keeps the name of the device that made it`() {
        val fromA = weight(80_000, now, "aaa")

        val relayed = SyncMerge.merge(listOf(document("zzz", listOf(fromA))), "zzz", now)

        assertThat(relayed.weights.single().stampDeviceId).isEqualTo("aaa")
    }

    @Test
    fun `a file written before stamps existed is read as that device's own`() {
        val unstamped = weight(80_000, now, device = "")

        val merged = SyncMerge.merge(listOf(document("old", listOf(unstamped))), "aaa", now)

        assertThat(merged.weights.single().stampDeviceId).isEqualTo("old")
    }
}
