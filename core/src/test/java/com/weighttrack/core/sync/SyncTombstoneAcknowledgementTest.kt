package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What it takes for a deletion to be forgotten, and what happens to the phone in the drawer.
 *
 * The rule these cover replaced a calendar. Six months of tombstone is safe only if every device
 * syncs inside six months, and the device that does not is exactly the one still holding the row.
 */
class SyncTombstoneAcknowledgementTest {

    private val now = 1_800_000_000_000L
    private val nineMonths = 270L * 24 * 60 * 60 * 1000
    private val deletedAt = now - nineMonths

    private fun weight(id: String, grams: Int, updatedAt: Long, device: String) = SyncWeight(
        syncId = id,
        profileSyncId = "p1",
        timestampUtcMillis = updatedAt,
        zoneOffsetSeconds = 0,
        localDate = "2026-01-01",
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

    private fun document(
        device: String,
        weights: List<SyncWeight> = emptyList(),
        deletions: List<SyncDeletion> = emptyList(),
        peers: List<SyncPeer> = emptyList(),
        observed: List<SyncObservation> = emptyList(),
    ) = SyncDocument(
        deviceId = device,
        writtenAtUtcMillis = now,
        profiles = listOf(profile()),
        weights = weights,
        deletions = deletions,
        peers = peers,
        observed = observed,
    )

    private val tombstone = SyncDeletion(
        kind = SyncKind.WEIGHT,
        syncId = "w1",
        deletedAtUtcMillis = deletedAt,
        profileSyncId = "p1",
        stampDeviceId = "aaa",
    )

    @Test
    fun `a phone gone nine months cannot bring its deleted reading back`() {
        // The device that deleted it, still syncing, and one that has never acknowledged it.
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = listOf(SyncPeer("aaa", lastSeenAtUtcMillis = now), SyncPeer("bbb")),
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        val afterNineMonths = SyncMerge.merge(listOf(deleter), "aaa", now)
        assertThat(afterNineMonths.deletions).hasSize(1)

        // And now it comes back, still holding the row.
        val returning = document("bbb", weights = listOf(weight("w1", 80_000, deletedAt - 1000, "bbb")))
        val merged = SyncMerge.merge(listOf(afterNineMonths, returning), "aaa", now)

        assertThat(merged.weights).isEmpty()
        assertThat(merged.deletions).hasSize(1)
    }

    @Test
    fun `a deletion everybody has seen is forgotten once it is old enough`() {
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = listOf(SyncPeer("aaa", lastSeenAtUtcMillis = now), SyncPeer("bbb", lastSeenAtUtcMillis = now)),
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )
        val other = document(
            "bbb",
            deletions = listOf(tombstone),
            peers = listOf(SyncPeer("aaa", lastSeenAtUtcMillis = now), SyncPeer("bbb", lastSeenAtUtcMillis = now)),
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        assertThat(SyncMerge.merge(listOf(deleter, other), "aaa", now).deletions).isEmpty()
    }

    @Test
    fun `a deletion everybody has seen is kept while it is younger than the floor`() {
        val fresh = tombstone.copy(deletedAtUtcMillis = now - 1000)
        val peers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer("bbb", lastSeenAtUtcMillis = now),
        )
        val deleter = document("aaa", deletions = listOf(fresh), peers = peers, observed = listOf(SyncObservation("aaa", now)))
        val other = document("bbb", deletions = listOf(fresh), peers = peers, observed = listOf(SyncObservation("aaa", now)))

        assertThat(SyncMerge.merge(listOf(deleter, other), "aaa", now).deletions).hasSize(1)
    }

    @Test
    fun `retiring the missing device is what lets the deletion be forgotten`() {
        val peers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer("bbb", retiredAtUtcMillis = now - 1000, retirementDecidedAtUtcMillis = now - 1000),
        )
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = peers,
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        assertThat(SyncMerge.merge(listOf(deleter), "aaa", now).deletions).isEmpty()
    }

    @Test
    fun `a device retired past the floor cannot be brought back`() {
        // Inside the floor the tombstones are all still here, so the decision costs nothing.
        assertThat(SyncMerge.canReturn(retiredAtUtcMillis = now - 1000, nowUtcMillis = now)).isTrue()
        assertThat(
            SyncMerge.canReturn(
                retiredAtUtcMillis = now - SyncMerge.TOMBSTONE_RETENTION_FLOOR_MILLIS,
                nowUtcMillis = now,
            ),
        ).isTrue()
        // One millisecond past it, the deletions that device never saw may already be gone.
        assertThat(
            SyncMerge.canReturn(
                retiredAtUtcMillis = now - SyncMerge.TOMBSTONE_RETENTION_FLOOR_MILLIS - 1,
                nowUtcMillis = now,
            ),
        ).isFalse()
        // A device that was never retired is not a question about returning at all.
        assertThat(SyncMerge.canReturn(retiredAtUtcMillis = 0, nowUtcMillis = now)).isTrue()
    }

    @Test
    fun `a reading deleted while a device was retired comes back if it is un-retired later`() {
        // The sequence the guard exists to prevent, written down so nobody removes the guard
        // without seeing what it costs. Retire the missing device, delete a reading, let the
        // tombstone be forgotten because nobody is waiting for it any more, then bring the
        // device back still holding the row.
        val retiredPeers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer("bbb", retiredAtUtcMillis = deletedAt, retirementDecidedAtUtcMillis = deletedAt),
        )
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = retiredPeers,
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        val forgotten = SyncMerge.merge(listOf(deleter), "aaa", now)
        assertThat(forgotten.deletions).isEmpty()

        // Nothing survives to contradict the row, so it lands on every device again.
        val returning = document("bbb", weights = listOf(weight("w1", 80_000, deletedAt - 1000, "bbb")))
        val merged = SyncMerge.merge(listOf(forgotten, returning), "aaa", now)

        assertThat(merged.weights).hasSize(1)
        // Which is exactly why the settings screen refuses to un-retire past the floor.
        assertThat(SyncMerge.canReturn(deletedAt, now)).isFalse()
    }

    @Test
    fun `bringing a retired device back makes the others wait for it again`() {
        val peers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer(
                "bbb",
                retiredAtUtcMillis = 0,
                retirementDecidedAtUtcMillis = now - 500,
            ),
        )
        val stillSaysRetired = document(
            "ccc",
            peers = listOf(
                SyncPeer("bbb", retiredAtUtcMillis = now - 1000, retirementDecidedAtUtcMillis = now - 1000),
            ),
        )
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = peers,
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        val merged = SyncMerge.merge(listOf(deleter, stillSaysRetired), "aaa", now)

        assertThat(merged.peers.first { it.deviceId == "bbb" }.isRetired).isFalse()
        assertThat(merged.deletions).hasSize(1)
    }

    @Test
    fun `a device that has not published a file this round is waited for`() {
        // Its file is missing from the folder for a moment. No evidence either way is not
        // permission to forget.
        val deleter = document(
            "aaa",
            deletions = listOf(tombstone),
            peers = listOf(SyncPeer("aaa", lastSeenAtUtcMillis = now), SyncPeer("bbb", lastSeenAtUtcMillis = now)),
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        assertThat(SyncMerge.merge(listOf(deleter), "aaa", now).deletions).hasSize(1)
    }

    @Test
    fun `a peer that has only caught up to before the deletion is still waited for`() {
        val peers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer("bbb", lastSeenAtUtcMillis = now),
        )
        val deleter = document("aaa", deletions = listOf(tombstone), peers = peers, observed = listOf(SyncObservation("aaa", deletedAt)))
        val behind = document("bbb", peers = peers, observed = listOf(SyncObservation("aaa", deletedAt - 1)))

        assertThat(SyncMerge.merge(listOf(deleter, behind), "aaa", now).deletions).hasSize(1)
    }

    @Test
    fun `a device is known from somebody else's list even if this one has never met it`() {
        // Told about "ccc" only by "bbb". Without carrying the list across, this device would
        // forget a deletion that a phone it has never met is still holding the row for.
        val peers = listOf(
            SyncPeer("aaa", lastSeenAtUtcMillis = now),
            SyncPeer("bbb", lastSeenAtUtcMillis = now),
        )
        val deleter = document("aaa", deletions = listOf(tombstone), peers = peers, observed = listOf(SyncObservation("aaa", deletedAt)))
        val other = document(
            "bbb",
            deletions = listOf(tombstone),
            peers = peers + SyncPeer("ccc"),
            observed = listOf(SyncObservation("aaa", deletedAt)),
        )

        val merged = SyncMerge.merge(listOf(deleter, other), "aaa", now)

        assertThat(merged.peers.map { it.deviceId }).containsExactly("aaa", "bbb", "ccc")
        assertThat(merged.deletions).hasSize(1)
    }
}
